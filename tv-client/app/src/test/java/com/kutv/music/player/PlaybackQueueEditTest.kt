package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueEditTest {
    private fun track(id: String) = QueueTrack(id, id, emptyList(), "", source = PlaybackSource.Local("android.resource://fixture/$id"))
    private class Port : QueuePlayerPort {
        val items = mutableListOf<String>()
        var current = ""
        var loads = 0
        var position = 40000L
        var retains = 0
        override fun contains(trackId: String) = trackId in items
        override fun loadAndPlay(item: ResolvedQueueTrack) { loads++; current = item.track.id; items.clear(); items.add(current) }
        override fun loadPaused(item: ResolvedQueueTrack, positionMs: Long) { loadAndPlay(item); position = positionMs }
        override fun append(item: ResolvedQueueTrack) { items.add(item.track.id) }
        override fun prependBeforeCurrent(item: ResolvedQueueTrack) { items.add(0, item.track.id) }
        override fun insertAfterCurrent(item: ResolvedQueueTrack) { items.add(items.indexOf(current) + 1, item.track.id) }
        override fun seekTo(trackId: String) { current = trackId }
        override fun clear() { items.clear(); current = "" }
        override fun retainCurrent() { retains++; items.clear(); items.add(current) }
    }
    private val resolver = object : QueueTrackResolver {
        override suspend fun resolve(track: QueueTrack) = ResolvedQueueTrack(track, "android.resource://fixture/${track.id}", "LOCAL")
    }

    @Test fun `shuffle preserves prefix membership and current position with no reload`() = runBlocking {
        val port = Port(); val queue = PlaybackQueueCoordinator(resolver, port, this)
        val tracks = (0..20).map { track(it.toString()) }
        assertTrue(queue.submitAndPlay(tracks, 2).await())
        assertTrue(queue.editQueue("shuffle", null).await())
        assertEquals(tracks.take(3), queue.snapshot.value.queue.take(3))
        assertEquals(tracks.toSet(), queue.snapshot.value.queue.toSet())
        assertEquals(2, queue.snapshot.value.currentIndex)
        assertEquals(1, port.loads); assertEquals(40000L, port.position); assertEquals(1, port.retains)
    }
    @Test fun `remove drops stale buffered next and refuses removal of current`() = runBlocking {
        val port = Port(); val queue = PlaybackQueueCoordinator(resolver, port, this)
        queue.submitAndPlay(listOf(track("a"), track("b"), track("c")), 0).await()
        yield(); yield()
        assertFalse(queue.editQueue("remove", "a").await())
        assertTrue(queue.editQueue("remove", "b").await())
        yield(); yield()
        assertEquals(listOf("a", "c"), queue.snapshot.value.queue.map { it.id })
        assertFalse(port.items.contains("b")); assertEquals("a", port.current); assertEquals(1, port.loads)
    }
    @Test fun `clear pending retains current and history and does not clear the player`() = runBlocking {
        val port = Port(); val queue = PlaybackQueueCoordinator(resolver, port, this)
        queue.submitAndPlay(listOf(track("a"), track("b"), track("c")), 1).await()
        assertTrue(queue.editQueue("clearPending", null).await())
        assertEquals(listOf("a", "b"), queue.snapshot.value.queue.map { it.id })
        assertEquals(1, queue.snapshot.value.currentIndex); assertEquals("b", port.current)
        assertFalse(queue.editQueue("remove", "missing").await())
        assertFalse(queue.editQueue("unsupported", null).await())
    }
}
