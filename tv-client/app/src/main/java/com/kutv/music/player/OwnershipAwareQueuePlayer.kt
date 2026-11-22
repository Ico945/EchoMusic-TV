package com.kutv.music.player

import com.kutv.music.video.PlaybackOwnership

class MusicPlaybackOwnershipException : IllegalStateException("Music playback is unavailable")

/** Last-line process gate for resolver work that completes after MV takes ownership. */
class OwnershipAwareQueuePlayer(
    private val delegate: QueuePlayerPort,
    private val ownership: PlaybackOwnership,
) : QueuePlayerPort {
    override fun currentQuality(trackId: String): String? =
        if (ownership.allowsMusicPlayback) delegate.currentQuality(trackId) else null
    override fun contains(trackId: String): Boolean = delegate.contains(trackId)

    override fun loadAndPlay(item: ResolvedQueueTrack) = guarded { delegate.loadAndPlay(item) }
    override fun loadPaused(item: ResolvedQueueTrack, positionMs: Long) =
        guarded { delegate.loadPaused(item, positionMs) }
    override fun loadSelection(item: ResolvedQueueTrack, positionMs: Long, play: Boolean) =
        guarded { delegate.loadSelection(item, positionMs, play) }
    override fun replaceCurrent(item: ResolvedQueueTrack) = guarded { delegate.replaceCurrent(item) }
    override fun append(item: ResolvedQueueTrack) = guarded { delegate.append(item) }
    override fun prependBeforeCurrent(item: ResolvedQueueTrack) = guarded { delegate.prependBeforeCurrent(item) }
    override fun insertAfterCurrent(item: ResolvedQueueTrack) = guarded { delegate.insertAfterCurrent(item) }
    override fun seekTo(trackId: String) = guarded { delegate.seekTo(trackId) }
    // Clearing stale account media is always allowed, including while MV owns audio.
    override fun clear() = delegate.clear()
    override fun retainCurrent() = guarded { delegate.retainCurrent() }

    private inline fun guarded(action: () -> Unit) {
        if (!ownership.allowsMusicPlayback) throw MusicPlaybackOwnershipException()
        action()
    }
}
