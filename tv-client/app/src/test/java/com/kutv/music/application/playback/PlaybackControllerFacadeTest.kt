package com.kutv.music.application.playback

import androidx.media3.common.Player
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerFacadeTest {
    @Test fun `superseded play is acknowledged without reporting or clearing current errors`() = runBlocking {
        val player = FakePlayerPort()
        val commands = FakeQueueCommands().apply { failure = com.kutv.music.player.PlaybackCommandSupersededException() }
        val errors = PlaybackErrorStore(MonotonicErrorIdentity())
        errors.report(PlayerCommandErrors.failed())
        val before = errors.state.value
        val facade = PlaybackControllerFacade(player, commands, FakeLyricsRepository(), this, errorStore = errors)
        try {
            assertTrue(facade.playQueue(listOf("a"), 0))
            assertEquals(before, errors.state.value)
            assertEquals(0, player.playCalls)
        } finally { facade.close() }
    }

    @Test fun `superseded next and previous do not fall back to seeking in the new queue`() = runBlocking {
        val player = FakePlayerPort()
        val commands = FakeQueueCommands().apply { failure = com.kutv.music.player.PlaybackCommandSupersededException() }
        val facade = PlaybackControllerFacade(player, commands, FakeLyricsRepository(), this)
        try {
            assertTrue(facade.next())
            assertTrue(facade.previous())
            assertEquals(0, player.nextCalls)
            assertEquals(0, player.previousCalls)
            assertEquals(null, facade.state.value.error)
        } finally { facade.close() }
    }

    @Test
    fun `media transition publishes one coherent snapshot`() = runBlocking {
        val player = FakePlayerPort()
        val lyrics = FakeLyricsRepository().apply { setLyrics("t1", emptyList()) }
        val facade = PlaybackControllerFacade(player, FakeQueueCommands(), lyrics, this)
        val transition = async(start = CoroutineStart.UNDISPATCHED) { facade.state.drop(1).first() }

        player.emitTrack(trackId = "t1", title = "飞行模式", durationMs = 296_000)

        val snapshot = transition.await()
        assertEquals("t1", snapshot.currentTrack?.id)
        assertEquals(296_000L, snapshot.durationMs)
        assertEquals(listOf("t1"), snapshot.queue.map(Track::id))
        assertEquals(0, snapshot.queueIndex)
        facade.close()
    }

    @Test
    fun `new transition cancels stale lyric result`() = runBlocking {
        val player = FakePlayerPort()
        val lyrics = FakeLyricsRepository()
        val first = lyrics.respondLater("t1")
        val second = lyrics.respondLater("t2")
        val facade = PlaybackControllerFacade(player, FakeQueueCommands(), lyrics, this)

        player.emitTrack("t1", "First", 100_000)
        awaitCondition { lyrics.requests == listOf("t1") }
        player.emitTrack("t2", "Second", 200_000)
        awaitCondition { lyrics.requests == listOf("t1", "t2") }
        first.complete(listOf(TimedLyricLine(0, 1000, "stale")))
        second.complete(listOf(TimedLyricLine(0, 1000, "current")))

        awaitCondition { facade.state.value.lyrics.singleOrNull()?.text == "current" }
        assertEquals("t2", facade.state.value.currentTrack?.id)
        assertEquals(listOf("current"), facade.state.value.lyrics.map(TimedLyricLine::text))
        facade.close()
    }

    @Test
    fun `A B A transition rejects first A lyric result even when repository ignores cancellation`() = runBlocking {
        val player = FakePlayerPort()
        val lyrics = FakeLyricsRepository()
        val firstA = lyrics.respondLater("a", ignoresCancellation = true)
        lyrics.setLyrics("b", emptyList())
        val secondA = lyrics.respondLater("a")
        val facade = PlaybackControllerFacade(player, FakeQueueCommands(), lyrics, this)

        player.emitTrack("a", "First A", 100_000)
        awaitCondition { lyrics.requests == listOf("a") }
        player.emitTrack("b", "B", 100_000)
        awaitCondition { lyrics.requests == listOf("a", "b") }
        player.emitTrack("a", "Second A", 100_000)
        awaitCondition { lyrics.requests == listOf("a", "b", "a") }
        secondA.complete(listOf(TimedLyricLine(0, 1000, "new A")))
        awaitCondition { facade.state.value.lyrics.singleOrNull()?.text == "new A" }

        firstA.complete(listOf(TimedLyricLine(0, 1000, "stale A")))
        delay(10)

        assertEquals(listOf("new A"), facade.state.value.lyrics.map(TimedLyricLine::text))
        facade.close()
    }

    @Test
    fun `snapshot event rebuilds queue and current index`() = runBlocking {
        val player = FakePlayerPort()
        val facade = PlaybackControllerFacade(player, FakeQueueCommands(), FakeLyricsRepository(), this)
        val queue = listOf(track("a"), track("b"), track("c"))

        player.emitSnapshot(
            PlayerPortSnapshot(
                currentTrack = queue[1],
                queue = queue,
                queueIndex = 1,
                durationMs = queue[1].durationMs,
            ),
        )
        awaitCondition { facade.state.value.queueIndex == 1 }

        assertEquals(listOf("a", "b", "c"), facade.state.value.queue.map(Track::id))
        assertEquals("b", facade.state.value.currentTrack?.id)
        facade.close()
    }

    @Test
    fun `position polling runs only while requested`() = runBlocking {
        val player = FakePlayerPort()
        val facade = PlaybackControllerFacade(
            player = player,
            queueCommands = FakeQueueCommands(),
            lyricsRepository = FakeLyricsRepository(),
            scope = this,
            positionIntervalMs = 10,
        )
        val initialReads = player.snapshotReads
        player.updatePosition(42_000)

        delay(25)
        assertEquals(initialReads, player.snapshotReads)
        facade.setPositionUpdatesRequested(true)
        awaitCondition { facade.state.value.positionMs == 42_000L }
        assertTrue(player.snapshotReads > initialReads)
        facade.setPositionUpdatesRequested(false)
        val stoppedReads = player.snapshotReads
        delay(25)

        assertEquals(stoppedReads, player.snapshotReads)
        facade.close()
    }

    @Test
    fun `logical navigation failure never falls back into the physical buffer`() = runBlocking {
        val player = FakePlayerPort()
        val queue = object : QueueCommands {
            override val ownsNavigation = true
            override suspend fun playQueue(trackIds: List<String>, startIndex: Int) = Unit
            override suspend fun playNext(trackId: String) = Unit
            override suspend fun select(trackId: String) = Unit
        }
        val facade = PlaybackControllerFacade(player, queue, FakeLyricsRepository(), this)
        assertFalse(facade.next())
        assertFalse(facade.previous())
        assertEquals(0, player.nextCalls)
        assertEquals(0, player.previousCalls)
        facade.close()
    }

    @Test
    fun `commands delegate without taking playback authority`() = runBlocking {
        val player = FakePlayerPort()
        val queue = FakeQueueCommands()
        val facade = PlaybackControllerFacade(player, queue, FakeLyricsRepository(), this)
        player.emitSnapshot(
            PlayerPortSnapshot(
                currentTrack = track("b"),
                isPlaying = false,
                positionMs = 5_000,
                durationMs = 20_000,
                queue = listOf(track("a"), track("b")),
                queueIndex = 1,
            ),
        )
        awaitCondition { facade.state.value.currentTrack?.id == "b" }

        assertTrue(facade.toggle())
        assertTrue(facade.seekBy(10_000))
        assertTrue(facade.next())
        assertTrue(facade.previous())
        assertTrue(facade.setRepeatMode("one"))
        assertTrue(facade.playQueue(listOf("a", "b"), 1))
        assertTrue(facade.playNext("c"))
        assertTrue(facade.selectQueueItem("a"))
        assertTrue(facade.setQuality("320"))

        assertEquals(1, player.playCalls)
        assertEquals(listOf(15_000L), player.seekPositions)
        assertEquals(1, player.nextCalls)
        assertEquals(1, player.previousCalls)
        assertEquals(listOf(Player.REPEAT_MODE_ONE), player.repeatModes)
        assertEquals(listOf(FakeQueueCommands.QueueRequest(listOf("a", "b"), 1)), queue.playQueueRequests)
        assertEquals(listOf("c"), queue.playNextRequests)
        assertEquals(listOf("a"), queue.selectRequests)
        assertEquals(
            listOf(FakeQueueCommands.QualityRequest(listOf("a", "b"), 1, "320")),
            queue.qualityRequests,
        )
        facade.close()
    }

    @Test
    fun `retry restores the last projected position after the queue resolves`() = runBlocking {
        val player = FakePlayerPort()
        val queue = FakeQueueCommands()
        val facade = PlaybackControllerFacade(player, queue, FakeLyricsRepository(), this)
        player.emitSnapshot(
            PlayerPortSnapshot(
                currentTrack = track("resume"),
                positionMs = 42_500L,
                durationMs = 180_000L,
                queue = listOf(track("resume")),
                queueIndex = 0,
            ),
        )
        awaitCondition { facade.state.value.positionMs == 42_500L }

        assertTrue(facade.retryCurrent())

        assertEquals(1, queue.retryCurrentCalls)
        assertEquals(listOf(42_500L), player.seekPositions)
        facade.close()
    }

    @Test
    fun `rapid toggle and seek commands read latest player state`() = runBlocking {
        val player = FakePlayerPort()
        val facade = PlaybackControllerFacade(player, FakeQueueCommands(), FakeLyricsRepository(), this)
        player.emitSnapshot(
            PlayerPortSnapshot(
                currentTrack = track("a"),
                positionMs = 5_000,
                durationMs = 30_000,
                queue = listOf(track("a")),
                queueIndex = 0,
            ),
        )
        awaitCondition { facade.state.value.currentTrack?.id == "a" }

        assertTrue(facade.toggle())
        assertTrue(facade.toggle())
        assertTrue(facade.seekBy(10_000))
        assertTrue(facade.seekBy(10_000))

        assertEquals(1, player.playCalls)
        assertEquals(1, player.pauseCalls)
        assertEquals(listOf(15_000L, 25_000L), player.seekPositions)
        facade.close()
    }

    @Test
    fun `command cancellation propagates without publishing playback error`() = runBlocking {
        val queue = FakeQueueCommands().apply { failure = CancellationException("scope closed") }
        val facade = PlaybackControllerFacade(FakePlayerPort(), queue, FakeLyricsRepository(), this)

        val failure = runCatching { facade.playQueue(listOf("a"), 0) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(null, facade.state.value.error)
        facade.close()
    }

    @Test
    fun `one player event causes one coherent state emission`() = runBlocking {
        val player = FakePlayerPort()
        val facade = PlaybackControllerFacade(player, FakeQueueCommands(), FakeLyricsRepository(), this)
        val emissions = mutableListOf<PlayerSnapshot>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            facade.state.drop(1).collect { emissions += it }
        }

        player.emitTrack("a", "A", 100_000)
        awaitCondition { facade.state.value.currentTrack?.id == "a" }
        delay(10)

        assertEquals(1, emissions.size)
        assertEquals("a", emissions.single().currentTrack?.id)
        collector.cancel()
        facade.close()
    }

    @Test
    fun `close is idempotent and stops player events position and lyrics`() = runBlocking {
        val player = FakePlayerPort()
        val lyrics = FakeLyricsRepository()
        val pendingLyrics = lyrics.respondLater("a")
        val facade = PlaybackControllerFacade(player, FakeQueueCommands(), lyrics, this, positionIntervalMs = 5)
        player.emitTrack("a", "A", 100_000)
        awaitCondition { lyrics.requests == listOf("a") }
        facade.setPositionUpdatesRequested(true)
        awaitCondition { player.snapshotReads > 1 }

        facade.close()
        facade.close()
        val readsAfterClose = player.snapshotReads
        player.emitTrack("b", "B", 100_000)
        pendingLyrics.complete(listOf(TimedLyricLine(0, 1000, "late")))
        delay(15)

        assertEquals(1, player.closeCalls)
        assertEquals(readsAfterClose, player.snapshotReads)
        assertEquals("a", facade.state.value.currentTrack?.id)
        assertTrue(facade.state.value.lyrics.isEmpty())
    }

    @Test
    fun `failed command publishes a sanitized command error`() = runBlocking {
        val queue = FakeQueueCommands().apply { failure = IllegalStateException("secret URL") }
        val facade = PlaybackControllerFacade(FakePlayerPort(), queue, FakeLyricsRepository(), this)

        assertFalse(facade.playQueue(listOf("a"), 0))

        assertEquals("PLAYBACK_COMMAND_FAILED", facade.state.value.error?.code)
        assertEquals("暂时无法完成播放操作", facade.state.value.error?.message)
        assertFalse(facade.state.value.error?.message.orEmpty().contains("secret"))
        facade.close()
    }

    @Test
    fun `sound effect command remains unavailable until service controller exists`() = runBlocking {
        val facade = PlaybackControllerFacade(
            FakePlayerPort(),
            FakeQueueCommands(),
            FakeLyricsRepository(),
            this,
        )

        assertFalse(facade.setSoundEffect("bass"))
        assertEquals("SOUND_EFFECT_UNAVAILABLE", facade.state.value.error?.code)
        facade.close()
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            yield()
            delay(1)
        }
        error("Condition was not met")
    }

    private fun track(id: String) = Track(
        id = id,
        title = "Title $id",
        artists = listOf("Artist $id"),
        album = "Album $id",
        artworkUri = "https://img.example/$id.jpg",
        durationMs = 180_000,
        qualities = listOf("128", "320"),
        source = PlaybackSource.Kugou(id, "HASH_$id"),
    )
}
