package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackQualitySwitchTest {
    @Test fun `same preferred quality retries after the previous stream fell back`() = runBlocking {
        var fallback = true
        val resolver = object : QueueTrackResolver {
            override suspend fun resolve(track: QueueTrack) = ResolvedQueueTrack(track,
                "https://webfs.kugou.com/fixture.mp3", if (fallback) "128" else "flac")
        }
        val port = RecordingPort()
        val coordinator = PlaybackQueueCoordinator(resolver, port, this)
        coordinator.submitAndPlay(listOf(track("a").copy(source = PlaybackSource.Kugou("a", "fixture", preferredQuality = "flac"))), 0).await()
        fallback = false
        assertTrue(coordinator.setCurrentQuality("a", "flac").await())
        assertEquals(listOf("a:flac"), port.replacements)
    }
    @Test fun `quality replacement retains full order and does not load or seek`() = runBlocking {
        val port = RecordingPort()
        val coordinator = PlaybackQueueCoordinator(Resolver(), port, this)
        coordinator.submitAndPlay(listOf(track("a"), track("b"), track("c")), 1).await()
        assertTrue(coordinator.setCurrentQuality("b", "320").await())
        assertEquals(listOf("a", "b", "c"), coordinator.snapshot.value.queue.map { it.id })
        assertEquals(1, coordinator.snapshot.value.currentIndex)
        assertEquals("320", quality(coordinator.snapshot.value.queue[1]))
        assertEquals(listOf("b"), port.loads)
        assertEquals(listOf("b:320"), port.replacements)
        assertEquals(emptyList<String>(), port.seeks)
    }

    @Test fun `same quality and unavailable quality never touch physical player`() = runBlocking {
        val port = RecordingPort()
        val resolver = Resolver()
        val coordinator = PlaybackQueueCoordinator(resolver, port, this)
        coordinator.submitAndPlay(listOf(track("a")), 0).await()
        assertTrue(coordinator.setCurrentQuality("a", "128").await())
        assertFalse(coordinator.setCurrentQuality("a", "unknown").await())
        assertEquals(listOf("a:128"), resolver.calls)
        assertTrue(port.replacements.isEmpty())
    }

    @Test fun `failed resolution keeps original source queue and player`() = runBlocking {
        val port = RecordingPort()
        val resolver = Resolver().apply { failQuality = "320" }
        val coordinator = PlaybackQueueCoordinator(resolver, port, this)
        coordinator.submitAndPlay(listOf(track("a")), 0).await()
        assertFalse(coordinator.setCurrentQuality("a", "320").await())
        assertEquals("128", quality(coordinator.snapshot.value.queue.single()))
        assertTrue(port.replacements.isEmpty())
        assertEquals(listOf("a"), port.loads)
    }

    @Test fun `later quality wins when responses arrive out of order`() = runBlocking {
        val resolver = Resolver()
        val port = RecordingPort()
        val coordinator = PlaybackQueueCoordinator(resolver, port, this)
        coordinator.submitAndPlay(listOf(track("a")), 0).await()
        val gate = CompletableDeferred<Unit>().also { resolver.gates["320"] = it }
        val first = coordinator.setCurrentQuality("a", "320")
        yieldUntil { "a:320" in resolver.calls }
        assertTrue(coordinator.setCurrentQuality("a", "flac").await())
        gate.complete(Unit)
        assertTrue(runCatching { first.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
        assertEquals(listOf("a:flac"), port.replacements)
    }

    @Test fun `choosing current quality cancels a pending different quality`() = runBlocking {
        val resolver = Resolver()
        val port = RecordingPort()
        val coordinator = PlaybackQueueCoordinator(resolver, port, this)
        coordinator.submitAndPlay(listOf(track("a")), 0).await()
        val gate = CompletableDeferred<Unit>().also { resolver.gates["320"] = it }
        val pending = coordinator.setCurrentQuality("a", "320")
        yieldUntil { "a:320" in resolver.calls }
        assertTrue(coordinator.setCurrentQuality("a", "128").await())
        gate.complete(Unit)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
        assertTrue(port.replacements.isEmpty())
    }

    @Test fun `track transition invalidates even a failed late quality response`() = runBlocking {
        val resolver = Resolver().apply { failQuality = "320" }
        val port = RecordingPort()
        val coordinator = PlaybackQueueCoordinator(resolver, port, this)
        coordinator.submitAndPlay(listOf(track("a"), track("b")), 0).await()
        val gate = CompletableDeferred<Unit>().also { resolver.gates["320"] = it }
        val pending = coordinator.setCurrentQuality("a", "320")
        yieldUntil { "a:320" in resolver.calls }
        coordinator.onMediaItemTransition("b")
        yieldUntil { coordinator.snapshot.value.currentIndex == 1 }
        gate.complete(Unit)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
        assertTrue(port.replacements.isEmpty())
    }

    @Test fun `clear and queue replacement invalidate pending quality`() = runBlocking {
        for (clear in listOf(false, true)) {
            val resolver = Resolver()
            val port = RecordingPort()
            val coordinator = PlaybackQueueCoordinator(resolver, port, this)
            coordinator.submitAndPlay(listOf(track("a")), 0).await()
            val gate = CompletableDeferred<Unit>().also { resolver.gates["320"] = it }
            val pending = coordinator.setCurrentQuality("a", "320")
            yieldUntil { "a:320" in resolver.calls }
            if (clear) coordinator.clear().await() else coordinator.submitAndPlay(listOf(track("b")), 0).await()
            gate.complete(Unit)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
            assertTrue(port.replacements.isEmpty())
        }
    }

    @Test fun `port failure leaves logical quality unchanged`() = runBlocking {
        val port = RecordingPort().apply { refuseReplacement = true }
        val coordinator = PlaybackQueueCoordinator(Resolver(), port, this)
        coordinator.submitAndPlay(listOf(track("a")), 0).await()
        assertFalse(coordinator.setCurrentQuality("a", "320").await())
        assertEquals("128", quality(coordinator.snapshot.value.queue.single()))
    }

    @Test fun `select and repeat wrap preserve the logical order without catalog lookup`() = runBlocking {
        val port = RecordingPort()
        val coordinator = PlaybackQueueCoordinator(Resolver(), port, this)
        coordinator.submitAndPlay(listOf(track("a"), track("b"), track("c")), 1).await()
        assertTrue(coordinator.requestSelect("c").await())
        assertEquals(listOf("a", "b", "c"), coordinator.snapshot.value.queue.map { it.id })
        assertFalse(coordinator.requestNext().await())
        coordinator.repeatAll = true
        assertTrue(coordinator.requestNext().await())
        assertEquals(0, coordinator.snapshot.value.currentIndex)
        assertTrue(coordinator.requestPrevious().await())
        assertEquals(2, coordinator.snapshot.value.currentIndex)
        assertEquals(listOf("c", "a", "c"), port.selections)
    }

    @Test fun `late next resolution cannot undo a newer previous command`() = runBlocking {
        val resolver = Resolver()
        val gate = CompletableDeferred<Unit>().also { resolver.idGates["c"] = it }
        val port = RecordingPort()
        val coordinator = PlaybackQueueCoordinator(resolver, port, this)
        coordinator.submitAndPlay(listOf(track("a"), track("b"), track("c")), 1).await()
        val next = coordinator.requestNext()
        yieldUntil { "c:128" in resolver.calls }
        yield()
        assertTrue(coordinator.requestPrevious().await())
        gate.complete(Unit)
        assertTrue(runCatching { next.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
        assertEquals(listOf("a"), port.seeks)
    }

    private suspend fun yieldUntil(test: () -> Boolean) = withTimeout(2_000) { while (!test()) yield() }

    private class Resolver : QueueTrackResolver {
        val calls = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val idGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var failQuality: String? = null
        override suspend fun resolve(track: QueueTrack): ResolvedQueueTrack {
            val quality = quality(track)
            calls += "${track.id}:$quality"
            gates[quality]?.await()
            idGates[track.id]?.await()
            if (failQuality == quality) throw PlaybackResolutionException("fixture failure")
            return ResolvedQueueTrack(track, "https://webfs.kugou.com/fixture.mp3", quality)
        }
    }

    private class RecordingPort : QueuePlayerPort {
        var activeQuality: Pair<String, String>? = null
        override fun currentQuality(trackId: String) = activeQuality?.takeIf { it.first == trackId }?.second
        val loads = mutableListOf<String>()
        val replacements = mutableListOf<String>()
        val selections = mutableListOf<String>()
        val seeks = mutableListOf<String>()
        val physical = mutableSetOf<String>()
        var refuseReplacement = false
        override fun contains(trackId: String) = trackId in physical
        override fun loadAndPlay(item: ResolvedQueueTrack) { activeQuality = item.track.id to item.quality; loads += item.track.id; physical.clear(); physical += item.track.id }
        override fun loadPaused(item: ResolvedQueueTrack, positionMs: Long) = loadAndPlay(item)
        override fun loadSelection(item: ResolvedQueueTrack, positionMs: Long, play: Boolean) {
            selections += item.track.id; physical.clear(); physical += item.track.id
        }
        override fun replaceCurrent(item: ResolvedQueueTrack) {
            if (refuseReplacement) throw MusicPlaybackOwnershipException()
            replacements += "${item.track.id}:${item.quality}"
            activeQuality = item.track.id to item.quality
        }
        override fun append(item: ResolvedQueueTrack) { physical += item.track.id }
        override fun prependBeforeCurrent(item: ResolvedQueueTrack) = append(item)
        override fun insertAfterCurrent(item: ResolvedQueueTrack) = append(item)
        override fun seekTo(trackId: String) { seeks += trackId }
        override fun clear() { physical.clear() }
    }

    companion object {
        private fun quality(track: QueueTrack) = (track.source as PlaybackSource.Kugou).preferredQuality
        private fun track(id: String) = QueueTrack(id, id, emptyList(), "",
            source = PlaybackSource.Kugou(id, "fixture", preferredQuality = "128"),
            qualities = listOf("128", "320", "flac"))
    }
}
