package com.kutv.music.application.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Track
import com.kutv.music.player.QueueMetadataKeys
import com.kutv.music.player.PlaybackQueueRuntime
import com.kutv.music.player.PlaybackQueueSnapshot
import com.kutv.music.player.QueueTrack
import com.kutv.music.player.effectivePositionMs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.channels.BufferOverflow

internal class LatestPlayerPortEvents {
    private val latestSnapshot = MutableStateFlow<PlayerPortEvent.Snapshot?>(null)
    private val audioEvents = MutableSharedFlow<PlayerPortEvent.AudioSessionChanged>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val flow: Flow<PlayerPortEvent> = merge(latestSnapshot.filterNotNull(), audioEvents)

    fun emit(event: PlayerPortEvent) {
        when (event) {
            is PlayerPortEvent.Snapshot -> latestSnapshot.value = event
            is PlayerPortEvent.AudioSessionChanged -> check(audioEvents.tryEmit(event))
        }
    }
}

internal data class ReconciledPlayerQueue(
    val queue: List<Track>,
    val currentIndex: Int,
)

internal fun reconcilePlayerQueue(
    logical: PlaybackQueueSnapshot,
    physical: List<Track>,
    currentTrackId: String?,
): ReconciledPlayerQueue {
    val logicalTracks = logical.queue.map(QueueTrack::toProjectionTrack)
    val useSeededLogicalQueue = physical.isEmpty() && logical.currentIndex in logicalTracks.indices
    val queue = logicalTracks.takeIf {
        it.isNotEmpty() && (currentTrackId in it.map(Track::id) || useSeededLogicalQueue)
    } ?: physical
    val currentIndex = currentTrackId?.let { id -> queue.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?: logical.currentIndex.takeIf { useSeededLogicalQueue && it in queue.indices }
        ?: -1
    return ReconciledPlayerQueue(queue, currentIndex)
}

private fun QueueTrack.toProjectionTrack() = Track(
    id = id,
    title = title,
    artists = artists,
    album = album,
    artworkUri = artworkUrl,
    durationMs = durationMs,
    qualities = qualities,
    source = when (val value = source) {
        is PlaybackSource.Local -> PlaybackSource.Local("")
        is PlaybackSource.Kugou -> value.copy(hash = "")
    },
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3PlayerPort(
    private val controller: MediaController,
    private val logicalSnapshot: () -> PlaybackQueueSnapshot = PlaybackQueueRuntime::currentSnapshot,
    logicalEvents: Flow<PlaybackQueueSnapshot> = PlaybackQueueRuntime.snapshot,
) : PlayerPort {
    private val eventChannel = LatestPlayerPortEvents()
    override val events: Flow<PlayerPortEvent> = merge(
        eventChannel.flow,
        logicalEvents.map { logical ->
            PlayerPortEvent.Snapshot(readSnapshot(controller, logical))
        },
    )

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            eventChannel.emit(PlayerPortEvent.Snapshot(readSnapshot(player)))
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            eventChannel.emit(PlayerPortEvent.AudioSessionChanged(audioSessionId))
        }
    }

    init {
        controller.addListener(listener)
    }

    override val snapshot: PlayerPortSnapshot
        get() = readSnapshot(controller)

    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    override fun seekToNext() = controller.seekToNextMediaItem()
    override fun seekToPrevious() = controller.seekToPreviousMediaItem()
    override fun setRepeatMode(mode: Int) = controller.setRepeatMode(mode)

    override fun close() {
        controller.removeListener(listener)
        controller.release()
    }

    private fun readSnapshot(
        player: Player,
        logical: PlaybackQueueSnapshot = logicalSnapshot(),
    ): PlayerPortSnapshot {
        val physicalQueue = (0 until player.mediaItemCount).map { index ->
            player.getMediaItemAt(index).toTrack()
        }
        val currentTrackId = player.currentMediaItem?.mediaId
        val reconciled = reconcilePlayerQueue(logical, physicalQueue, currentTrackId)
        val queue = reconciled.queue
        val queueIndex = reconciled.currentIndex
        val currentTrack = queue.getOrNull(queueIndex)
        val durationMs = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L)
            ?: currentTrack?.durationMs?.coerceAtLeast(0L)
            ?: 0L
        val quality = player.currentMediaItem?.mediaMetadata?.extras
            ?.getString(QueueMetadataKeys.QUALITY)
            .orEmpty()
        return PlayerPortSnapshot(
            currentTrack = currentTrack,
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            positionMs = logical.effectivePositionMs(
                playerPositionMs = player.currentPosition.coerceAtLeast(0L),
                hasPhysicalMediaItem = physicalQueue.isNotEmpty(),
            ),
            durationMs = durationMs,
            queue = queue,
            queueIndex = queueIndex,
            repeatMode = player.repeatMode,
            quality = quality,
            buffering = player.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun MediaItem.toTrack(): Track {
        val metadata = mediaMetadata
        val extras = metadata.extras
        val quality = extras?.getString(QueueMetadataKeys.QUALITY).orEmpty()
        val qualities = extras?.getStringArrayList(QueueMetadataKeys.QUALITIES)
            ?.filter(String::isNotBlank)
            .orEmpty()
        val source = when (extras?.getString(QueueMetadataKeys.SOURCE_KIND)) {
            QueueMetadataKeys.SOURCE_LOCAL -> PlaybackSource.Local(
                extras.getString(QueueMetadataKeys.SOURCE_URI).orEmpty(),
            )
            QueueMetadataKeys.SOURCE_KUGOU -> PlaybackSource.Kugou(
                trackId = extras.getString(QueueMetadataKeys.SOURCE_TRACK_ID).orEmpty().ifBlank { mediaId },
                hash = extras.getString(QueueMetadataKeys.SOURCE_HASH).orEmpty(),
                albumId = extras.getString(QueueMetadataKeys.SOURCE_ALBUM_ID),
                lyricId = extras.getString(QueueMetadataKeys.SOURCE_LYRIC_ID),
                preferredQuality = extras.getString(QueueMetadataKeys.SOURCE_QUALITY).orEmpty().ifBlank { quality },
            )
            else -> PlaybackSource.Kugou(trackId = mediaId, hash = "", preferredQuality = quality)
        }
        return Track(
            id = mediaId,
            title = metadata.title?.toString().orEmpty(),
            artists = metadata.artist?.toString()
                ?.split(" · ")
                ?.filter(String::isNotBlank)
                .orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            artworkUri = metadata.artworkUri?.toString().orEmpty(),
            durationMs = extras?.getLong(QueueMetadataKeys.DURATION, 0L) ?: 0L,
            qualities = qualities.ifEmpty { listOfNotNull(quality.takeIf(String::isNotBlank)) },
            source = source,
        )
    }
}
