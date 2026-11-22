package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.video.PlaybackOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OwnershipAwareQueuePlayerTest {
    @Test
    fun `all queue mutations are rejected while MV owns process playback`() {
        val delegate = RecordingQueuePlayer()
        val ownership = PlaybackOwnership()
        val guarded = OwnershipAwareQueuePlayer(delegate, ownership)
        val item = ResolvedQueueTrack(
            QueueTrack("track", "title", listOf("artist"), "", source = PlaybackSource.Local("android.resource://test")),
            "android.resource://test",
            "local",
        )
        ownership.claimMv(Any())

        assertThrows(MusicPlaybackOwnershipException::class.java) { guarded.loadAndPlay(item) }
        assertThrows(MusicPlaybackOwnershipException::class.java) { guarded.replaceCurrent(item) }
        assertThrows(MusicPlaybackOwnershipException::class.java) { guarded.loadSelection(item, 0L, true) }
        assertThrows(MusicPlaybackOwnershipException::class.java) { guarded.append(item) }
        assertThrows(MusicPlaybackOwnershipException::class.java) { guarded.prependBeforeCurrent(item) }
        assertThrows(MusicPlaybackOwnershipException::class.java) { guarded.insertAfterCurrent(item) }
        assertThrows(MusicPlaybackOwnershipException::class.java) { guarded.seekTo("track") }
        assertEquals(0, delegate.mutations)
    }

    private class RecordingQueuePlayer : QueuePlayerPort {
        var mutations = 0
        override fun contains(trackId: String) = false
        override fun loadAndPlay(item: ResolvedQueueTrack) { mutations += 1 }
        override fun loadPaused(item: ResolvedQueueTrack, positionMs: Long) { mutations += 1 }
        override fun append(item: ResolvedQueueTrack) { mutations += 1 }
        override fun prependBeforeCurrent(item: ResolvedQueueTrack) { mutations += 1 }
        override fun insertAfterCurrent(item: ResolvedQueueTrack) { mutations += 1 }
        override fun seekTo(trackId: String) { mutations += 1 }
        override fun clear() { mutations += 1 }
    }
}
