package com.kutv.music.application.playback

import com.kutv.music.player.PlaybackQueueAction
import com.kutv.music.player.PlaybackQueueError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorStoreTest {
    @Test fun `failed next identifies its own track without forwarding provider errors`() {
        val store = PlaybackErrorStore(MonotonicErrorIdentity())
        val result = store.reportQueue(PlaybackQueueError(PlaybackQueueAction.NEXT, "next-id", "private provider error", true, "下一首标题\n"))
        assertEquals("下一首标题：下一首暂时无法播放", result?.message)
    }
    @Test
    fun `queue actions publish distinct replayable errors while background prefetch stays silent`() {
        val store = PlaybackErrorStore(MonotonicErrorIdentity())

        val start = store.reportQueue(queueError(PlaybackQueueAction.START))
        val replayed = store.state.value
        val prefetch = store.reportQueue(queueError(PlaybackQueueAction.PREFETCH))
        val next = store.reportQueue(queueError(PlaybackQueueAction.NEXT))
        val previous = store.reportQueue(queueError(PlaybackQueueAction.PREVIOUS))

        assertEquals("PLAYBACK_RESOLVE_FAILED", start?.code)
        assertEquals(1L, start?.errorId)
        assertSame(start, replayed.error)
        assertNull(prefetch)
        assertEquals("PLAYBACK_NEXT_FAILED", next?.code)
        assertEquals(2L, next?.errorId)
        assertEquals("PLAYBACK_PREVIOUS_FAILED", previous?.code)
        assertEquals(3L, previous?.errorId)
        assertSame(previous, store.state.value.error)
    }

    @Test
    fun `clear advances identity and a stale dismiss cannot clear a newer error`() {
        val store = PlaybackErrorStore(MonotonicErrorIdentity())
        val first = checkNotNull(store.reportQueue(queueError(PlaybackQueueAction.START)))

        val cleared = store.clear(first.errorId)
        val second = store.report(PlayerCommandErrors.failed())
        val stale = store.clear(first.errorId)

        assertTrue(cleared.accepted)
        assertEquals(2L, cleared.errorRevision)
        assertEquals(3L, second.errorId)
        assertFalse(stale.accepted)
        assertEquals(second, store.state.value.error)
        assertEquals(second.errorId, stale.errorRevision)
    }

    @Test
    fun `facade recreation replays the latest native error and player snapshots cannot erase it`() = runBlocking {
        val store = PlaybackErrorStore(MonotonicErrorIdentity())
        val error = store.report(PlayerCommandErrors.failed())
        val first = PlaybackControllerFacade(
            FakePlayerPort(), FakeQueueCommands(), FakeLyricsRepository(), this, errorStore = store,
        )
        first.close()

        val player = FakePlayerPort()
        val recreated = PlaybackControllerFacade(
            player, FakeQueueCommands(), FakeLyricsRepository(), this, errorStore = store,
        )
        player.emitTrack("new-track", "New", 1_000L)

        assertEquals(error, recreated.state.value.error)
        assertEquals(error.errorId, recreated.state.value.errorRevision)
        recreated.close()
    }

    private fun queueError(action: PlaybackQueueAction) = PlaybackQueueError(
        action = action,
        trackId = "raw-id-that-must-not-cross",
        message = "untrusted upstream detail",
        retryable = true,
    )
}
