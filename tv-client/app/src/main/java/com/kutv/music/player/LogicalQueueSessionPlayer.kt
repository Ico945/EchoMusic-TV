package com.kutv.music.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * ExoPlayer buffers only resolved neighbours. System controllers must see the
 * complete domain queue, never mistake that buffer for the playlist or loop it.
 * All virtual indices are translated here, before reaching the physical player.
 */
@UnstableApi
class LogicalQueueSessionPlayer(
    private val raw: Player,
    private val coordinator: PlaybackQueueCoordinator,
    private val scope: CoroutineScope,
    private val allowsPlayback: () -> Boolean = { true },
) : ForwardingSimpleBasePlayer(raw) {
    private var logicalRepeatMode = raw.repeatMode
    private var releasedState: State? = null
    private val snapshotJob = scope.launch { coordinator.snapshot.collect { invalidateState() } }

    init {
        raw.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (releasedState != null) return
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    raw.currentMediaItem?.mediaId?.let(coordinator::onMediaItemTransition)
                }
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                    raw.playbackState == Player.STATE_ENDED && raw.playWhenReady && allowsPlayback()) {
                    // Leave the event batch before mutating the player. A synchronous
                    // reload inside onPlaybackStateChanged exposes an inconsistent
                    // loading/ended state to the forwarding player's state builder.
                    scope.launch {
                        yield()
                        if (raw.playbackState == Player.STATE_ENDED && raw.playWhenReady && allowsPlayback()) {
                            coordinator.requestNext().await()
                        }
                    }
                }
            }
        })
    }

    override fun getState(): State {
        releasedState?.let { return it }
        val base = super.getState()
        val logical = coordinator.snapshot.value
        val index = logical.queue.indexOfFirst { it.id == raw.currentMediaItem?.mediaId }
        // A new queue can be resolving while the previous track still plays.
        // Do not attach its new title/index to the old audio during that window.
        if (index < 0) return base.buildUpon().setRepeatMode(logicalRepeatMode).build()
        val playlist = logical.queue.mapIndexed { i, track ->
            val item = track.toSessionMediaItem(
                if (i == index) raw.mediaMetadata.extras?.getString(QueueMetadataKeys.QUALITY)
                    ?: (track.source as? com.kutv.music.core.model.PlaybackSource.Kugou)?.preferredQuality.orEmpty()
                else (track.source as? com.kutv.music.core.model.PlaybackSource.Kugou)?.preferredQuality ?: "LOCAL",
            )
            MediaItemData.Builder("${i}:${track.id}")
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setIsSeekable(true)
                .setDurationUs((if (i == index && raw.duration > 0) raw.duration else track.durationMs)
                    .takeIf { it > 0 }?.times(1000) ?: C.TIME_UNSET)
                .apply { if (i == index) setTracks(raw.currentTracks) }
                .build()
        }
        val canNext = index < logical.queue.lastIndex || logicalRepeatMode == Player.REPEAT_MODE_ALL
        val canPrevious = index > 0 || logicalRepeatMode == Player.REPEAT_MODE_ALL
        val commands = base.availableCommands.buildUpon()
            .removeAll(Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, canNext)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, canNext)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, canPrevious)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, canPrevious)
            .build()
        return base.buildUpon().setPlaylist(playlist).setCurrentMediaItemIndex(index)
            .setRepeatMode(logicalRepeatMode).setShuffleModeEnabled(false)
            .setAvailableCommands(commands).build()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        logicalRepeatMode = repeatMode
        coordinator.repeatAll = repeatMode == Player.REPEAT_MODE_ALL
        // ALL is a domain queue operation; looping the resolved subset skips
        // unbuffered items before/after it. ONE remains native for gapless repeat.
        raw.repeatMode = if (repeatMode == Player.REPEAT_MODE_ONE) repeatMode else Player.REPEAT_MODE_OFF
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (!allowsPlayback()) return Futures.immediateFailedFuture<Any>(MusicPlaybackOwnershipException())
        val logical = coordinator.snapshot.value
        val target = logical.queue.getOrNull(mediaItemIndex)
        return when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                awaitCommand(coordinator.requestNext())
            // Use the same explicit previous-track action as the on-screen
            // button, including after Media3's default restart threshold.
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS ->
                awaitCommand(coordinator.requestPrevious())
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                if (target == null) Futures.immediateVoidFuture()
                else if (target.id == raw.currentMediaItem?.mediaId) {
                    raw.seekTo(positionMs.takeIf { it != C.TIME_UNSET } ?: 0L)
                    Futures.immediateVoidFuture()
                } else awaitCommand(coordinator.requestSelect(target.id,
                    positionMs.takeIf { it != C.TIME_UNSET } ?: 0L))
            }
            else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
    }

    private fun awaitCommand(command: Deferred<Boolean>): ListenableFuture<*> {
        val result = SettableFuture.create<Void>()
        scope.launch {
            try {
                command.await()
                // Domain errors are already reported through the shared error
                // store; expose the actual old state on failure, not a fake skip.
                result.set(null)
            } catch (_: CancellationException) {
                result.cancel(false)
            } catch (_: Exception) {
                result.setException(IllegalStateException("Playback command failed"))
            }
        }
        return result
    }

    override fun handleRelease(): ListenableFuture<*> {
        snapshotJob.cancel()
        // ExoPlayer flushes listeners during release, with an intermediate
        // IDLE/loading pair. Publish a valid terminal state before that flush;
        // never inspect or advance a player that is being released.
        releasedState = State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
        return super.handleRelease()
    }
}
