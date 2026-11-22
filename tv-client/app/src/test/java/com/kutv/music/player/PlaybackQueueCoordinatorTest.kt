package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class PlaybackQueueCoordinatorTest {
    @Test fun `resolution enriches current and prefetched logical capabilities but preserves caller metadata`() = runBlocking {
        val resolver = object : QueueTrackResolver {
            override suspend fun resolve(track: QueueTrack) = ResolvedQueueTrack(
                track.copy(title = "not caller metadata", qualities = listOf("128", "320", "flac")),
                "https://webfs.kugou.com/fixture.mp3", "128", availableQualities = listOf("128", "320", "flac"))
        }
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)
        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b")), 0).await())
        yieldUntil { player.appendedIds == listOf("b") }
        assertEquals(listOf("Title a", "Title b"), coordinator.snapshot.value.queue.map { it.title })
        assertTrue(coordinator.snapshot.value.queue.all { it.qualities == listOf("128", "320", "flac") })
        assertTrue(coordinator.enqueueNext(track("c")).await())
        assertEquals(listOf("128", "320", "flac"), coordinator.snapshot.value.queue[1].qualities)
        assertEquals("Title c", coordinator.snapshot.value.queue[1].title)
    }

    @Test
    fun `restore rebuilds complete logical queue paused at the persisted position`() = runBlocking {
        val player = RecordingPlayer()
        val coordinator = coordinator(FakeResolver(), player, this)
        val queue = listOf(track("a"), track("b"), track("c"))

        assertTrue(coordinator.restorePaused(queue, 1, 42_500L).await())
        yieldUntil { player.appendedIds == listOf("c") }

        assertEquals(listOf("a", "b", "c"), coordinator.snapshot.value.queue.map { it.id })
        assertEquals(1, coordinator.snapshot.value.currentIndex)
        assertNull(coordinator.snapshot.value.pendingRestorePositionMs)
        assertEquals(listOf("b" to 42_500L), player.pausedLoads)
        assertEquals(emptyList<String>(), player.loadedIds)
    }

    @Test
    fun `failed restore retains persisted position until media resolution succeeds`() = runBlocking {
        val player = RecordingPlayer()
        val coordinator = coordinator(FakeResolver(failuresRemaining = mapOf("b" to 1)), player, this)
        val queue = listOf(track("a"), track("b"), track("c"))

        assertFalse(coordinator.restorePaused(queue, 1, 42_500L).await())

        assertEquals(listOf("a", "b", "c"), coordinator.snapshot.value.queue.map { it.id })
        assertEquals(1, coordinator.snapshot.value.currentIndex)
        assertEquals(42_500L, coordinator.snapshot.value.pendingRestorePositionMs)
        assertEquals(42_500L, coordinator.snapshot.value.effectivePositionMs(0L, hasPhysicalMediaItem = false))
        assertEquals(0L, coordinator.snapshot.value.effectivePositionMs(0L, hasPhysicalMediaItem = true))
    }

    @Test
    fun `clear invalidates logical queue and removes physical media`() = runBlocking {
        val player = RecordingPlayer()
        val coordinator = coordinator(FakeResolver(), player, this)
        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b")), 0).await())

        assertTrue(coordinator.clear().await())

        assertEquals(PlaybackQueueSnapshot(), coordinator.snapshot.value)
        assertEquals(1, player.clearCalls)
    }

    @Test
    fun `prefetch appends next item without seeking or starting playback`() = runBlocking {
        val resolver = FakeResolver()
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)

        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b")), 0).await())
        yieldUntil { player.appendedIds == listOf("b") }

        assertEquals(listOf("a"), player.loadedIds)
        assertEquals(emptyList<String>(), player.soughtIds)
        assertEquals(listOf("b"), player.appendedIds)
    }

    @Test
    fun `next waits for shared prefetch then seeks exactly once`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val resolver = FakeResolver(gates = mapOf("b" to gate))
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)

        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b")), 0).await())
        val next = coordinator.requestNext()
        gate.complete(Unit)

        assertTrue(next.await())
        assertEquals(1, resolver.calls.getValue("b"))
        assertEquals(listOf("b"), player.soughtIds)
    }

    @Test
    fun `next pressed during initial resolution waits until new queue is loaded`() = runBlocking {
        val currentGate = CompletableDeferred<Unit>()
        val resolver = FakeResolver(gates = mapOf("a" to currentGate))
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)

        val start = coordinator.submitAndPlay(listOf(track("a"), track("b")), 0)
        yieldUntil { resolver.calls["a"] == 1 }
        val next = coordinator.requestNext()
        yieldUntil { "b" in resolver.completedIds }
        yield()

        assertEquals("初始媒体尚未装载时不得提前定位", emptyList<String>(), player.soughtIds)
        currentGate.complete(Unit)
        assertTrue(start.await())
        assertTrue(next.await())
        assertEquals(listOf("b"), player.soughtIds)
    }

    @Test
    fun `prefetch failure leaves current item and index unchanged`() = runBlocking {
        val resolver = FakeResolver(failuresRemaining = mapOf("b" to 1))
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)
        val error = async(start = CoroutineStart.UNDISPATCHED) { coordinator.errors.first() }

        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b")), 0).await())
        assertEquals(PlaybackQueueAction.PREFETCH, error.await().action)
        assertEquals(listOf("a"), player.loadedIds)
        assertEquals(emptyList<String>(), player.appendedIds)
        assertEquals(emptyList<String>(), player.soughtIds)

        assertTrue(coordinator.requestNext().await())
        assertEquals(2, resolver.calls.getValue("b"))
        assertEquals(listOf("b"), player.soughtIds)
    }

    @Test
    fun `new queue generation discards old prefetch result`() = runBlocking {
        val oldPrefetchGate = CompletableDeferred<Unit>()
        val resolver = FakeResolver(gates = mapOf("b" to oldPrefetchGate))
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)

        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b")), 0).await())
        yieldUntil { resolver.calls["b"] == 1 }
        assertTrue(coordinator.submitAndPlay(listOf(track("c")), 0).await())
        oldPrefetchGate.complete(Unit)
        yieldUntil { "b" in resolver.completedIds }
        yield()

        assertEquals(listOf("a", "c"), player.loadedIds)
        assertFalse("旧队列结果不得写入播放器", "b" in player.appendedIds)
    }

    @Test
    fun `starting in middle resolves previous and prepends before seeking`() = runBlocking {
        val resolver = FakeResolver()
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)

        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b"), track("c")), 1).await())
        assertTrue(coordinator.requestPrevious().await())

        assertEquals(listOf("a"), player.prependedIds)
        assertEquals(listOf("a"), player.soughtIds)
    }

    @Test
    fun `transition advances logical index and prefetches one adjacent item`() = runBlocking {
        val resolver = FakeResolver()
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)

        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b"), track("c")), 0).await())
        yieldUntil { player.appendedIds == listOf("b") }
        coordinator.onMediaItemTransition("b")
        yieldUntil { player.appendedIds == listOf("b", "c") }

        assertEquals(1, resolver.calls.getValue("c"))
        assertEquals(emptyList<String>(), player.soughtIds)
    }

    @Test
    fun `logical snapshot keeps the complete queue while media player only prefetches one item`() = runBlocking {
        val player = RecordingPlayer()
        val coordinator = coordinator(FakeResolver(), player, this)
        val queue = listOf(track("a"), track("b"), track("c"), track("d"))

        assertTrue(coordinator.submitAndPlay(queue, 0).await())
        yieldUntil { player.appendedIds == listOf("b") }

        assertEquals(listOf("a", "b", "c", "d"), coordinator.snapshot.value.queue.map { it.id })
        assertEquals(0, coordinator.snapshot.value.currentIndex)
    }

    @Test
    fun `enqueue next inserts after current without replay seek or playback state changes`() = runBlocking {
        val player = RecordingPlayer()
        val coordinator = coordinator(FakeResolver(), player, this)

        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b"), track("c")), 0).await())
        yieldUntil { player.appendedIds == listOf("b") }
        val loadCount = player.loadedIds.size

        assertTrue(coordinator.enqueueNext(track("d")).await())

        assertEquals(loadCount, player.loadedIds.size)
        assertEquals(emptyList<String>(), player.soughtIds)
        assertEquals(listOf("d"), player.insertedAfterCurrentIds)
        assertEquals(listOf("a", "d", "b", "c"), coordinator.snapshot.value.queue.map { it.id })
        assertEquals(0, coordinator.snapshot.value.currentIndex)
    }

    @Test
    fun `enqueue existing item from before current preserves current track and inserts immediately after it`() = runBlocking {
        val player = RecordingPlayer()
        val coordinator = coordinator(FakeResolver(), player, this)

        assertTrue(
            coordinator.submitAndPlay(
                listOf(track("x"), track("a"), track("b"), track("c")),
                1,
            ).await(),
        )

        assertTrue(coordinator.enqueueNext(track("x")).await())

        assertEquals(listOf("a", "x", "b", "c"), coordinator.snapshot.value.queue.map { it.id })
        assertEquals(0, coordinator.snapshot.value.currentIndex)
        assertEquals(listOf("x"), player.insertedAfterCurrentIds)
        assertEquals(emptyList<String>(), player.soughtIds)
    }

    @Test
    fun `failed physical insertion restores the complete original logical queue`() = runBlocking {
        val player = RecordingPlayer(failInsert = true)
        val coordinator = coordinator(FakeResolver(), player, this)

        assertTrue(coordinator.submitAndPlay(listOf(track("x"), track("a"), track("b")), 1).await())

        assertFalse(coordinator.enqueueNext(track("x")).await())

        assertEquals(listOf("x", "a", "b"), coordinator.snapshot.value.queue.map { it.id })
        assertEquals(1, coordinator.snapshot.value.currentIndex)
        assertEquals(emptyList<String>(), player.soughtIds)
    }

    @Test
    fun `failed concurrent enqueue cannot roll back a later successful enqueue`() = runBlocking {
        val player = RecordingPlayer(failInsertIds = setOf("x"))
        val coordinator = coordinator(FakeResolver(), player, this)
        assertTrue(coordinator.submitAndPlay(listOf(track("a"), track("b")), 0).await())

        val failed = coordinator.enqueueNext(track("x"))
        val successful = coordinator.enqueueNext(track("y"))

        assertFalse(failed.await())
        assertTrue(successful.await())
        assertEquals(listOf("a", "y", "b"), coordinator.snapshot.value.queue.map { it.id })
        assertEquals(0, coordinator.snapshot.value.currentIndex)
        assertEquals(listOf("y"), player.insertedAfterCurrentIds)
    }

    @Test
    fun `invalid start index emits current play error without touching player`() = runBlocking {
        val resolver = FakeResolver()
        val player = RecordingPlayer()
        val coordinator = coordinator(resolver, player, this)
        val error = async(start = CoroutineStart.UNDISPATCHED) { coordinator.errors.first() }

        assertFalse(coordinator.submitAndPlay(listOf(track("a")), 2).await())

        assertEquals(PlaybackQueueAction.START, error.await().action)
        assertEquals("播放失败，请重试", error.await().message)
        assertEquals(emptyList<String>(), player.loadedIds)
        assertEquals(emptyList<String>(), player.appendedIds)
        assertEquals(emptyList<String>(), player.soughtIds)
    }

    private fun coordinator(
        resolver: QueueTrackResolver,
        player: QueuePlayerPort,
        scope: CoroutineScope,
    ) = PlaybackQueueCoordinator(resolver, player, scope)

    private fun track(id: String) = QueueTrack(
        id = id,
        title = "Title $id",
        artists = listOf("Artist $id"),
        artworkUrl = "https://img.example/$id.jpg",
        source = PlaybackSource.Local("android.resource://com.kutv.music/raw/$id"),
    )

    private class FakeResolver(
        private val gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        failuresRemaining: Map<String, Int> = emptyMap(),
    ) : QueueTrackResolver {
        val calls = ConcurrentHashMap<String, Int>()
        val completedIds = ConcurrentHashMap.newKeySet<String>()
        private val remainingFailures = ConcurrentHashMap(failuresRemaining)

        override suspend fun resolve(track: QueueTrack): ResolvedQueueTrack {
            calls.compute(track.id) { _, count -> (count ?: 0) + 1 }
            gates[track.id]?.await()
            val failures = remainingFailures[track.id] ?: 0
            if (failures > 0) {
                remainingFailures[track.id] = failures - 1
                throw IllegalStateException("resolve failed for ${track.id}")
            }
            return ResolvedQueueTrack(
                track = track,
                streamUrl = "https://audio.example/${track.id}.mp3",
                quality = "128",
            ).also { completedIds += track.id }
        }
    }

    private class RecordingPlayer(
        private val failInsert: Boolean = false,
        private val failInsertIds: Set<String> = emptySet(),
    ) : QueuePlayerPort {
        val loadedIds = mutableListOf<String>()
        val appendedIds = mutableListOf<String>()
        val prependedIds = mutableListOf<String>()
        val insertedAfterCurrentIds = mutableListOf<String>()
        val soughtIds = mutableListOf<String>()
        val pausedLoads = mutableListOf<Pair<String, Long>>()
        var clearCalls = 0

        override fun contains(trackId: String): Boolean =
            trackId in loadedIds || trackId in appendedIds || trackId in prependedIds

        override fun loadAndPlay(item: ResolvedQueueTrack) {
            loadedIds += item.track.id
            appendedIds.clear()
            prependedIds.clear()
        }

        override fun loadPaused(item: ResolvedQueueTrack, positionMs: Long) {
            pausedLoads += item.track.id to positionMs
            appendedIds.clear()
            prependedIds.clear()
        }

        override fun append(item: ResolvedQueueTrack) {
            appendedIds += item.track.id
        }

        override fun prependBeforeCurrent(item: ResolvedQueueTrack) {
            prependedIds += item.track.id
        }

        override fun insertAfterCurrent(item: ResolvedQueueTrack) {
            if (failInsert || item.track.id in failInsertIds) throw IllegalStateException("insert failed")
            insertedAfterCurrentIds += item.track.id
        }

        override fun seekTo(trackId: String) {
            soughtIds += trackId
        }

        override fun clear() {
            clearCalls += 1
            loadedIds.clear()
            appendedIds.clear()
            prependedIds.clear()
        }
    }

    private suspend fun yieldUntil(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) yield()
        }
    }
}
