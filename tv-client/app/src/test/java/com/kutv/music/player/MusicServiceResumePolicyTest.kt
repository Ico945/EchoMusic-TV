package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicServiceResumePolicyTest {
    private val queue = listOf(
        QueueTrack(
            id = "track",
            title = "Track",
            artists = emptyList(),
            artworkUrl = "",
            source = PlaybackSource.Kugou("track", "HASH"),
            durationMs = 180_000L,
        ),
    )
    private val stored = PlaybackResumeState(
        queue = queue,
        currentIndex = 0,
        positionMs = 42_500L,
        repeatMode = 0,
        savedAtEpochMs = 1L,
    )

    @Test
    fun `retries only while the unresolved persisted queue still owns playback`() {
        val pending = PlaybackQueueSnapshot(queue, currentIndex = 0, pendingRestorePositionMs = 42_500L)

        assertTrue(shouldRetryPlaybackRestore(stored, pending, hasPhysicalMediaItem = false))
        assertFalse(shouldRetryPlaybackRestore(stored, pending, hasPhysicalMediaItem = true))
        assertFalse(
            shouldRetryPlaybackRestore(
                stored,
                pending.copy(pendingRestorePositionMs = null),
                hasPhysicalMediaItem = false,
            ),
        )
        assertFalse(
            shouldRetryPlaybackRestore(
                stored,
                pending.copy(currentIndex = -1),
                hasPhysicalMediaItem = false,
            ),
        )
    }
}
