package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Track
import com.kutv.music.application.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class PlaybackQueueRaceTest {
    @Test fun `real runtime and facade acknowledge stale start without a new error`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("a")
            val errors = PlaybackErrorStore(MonotonicErrorIdentity())
            PlaybackQueueRuntime.attach(f.queue, this, onError = { errors.reportQueue(it) })
            val facade = PlaybackControllerFacade(FakePlayerPort(), PlaybackQueueRuntimeCommands(::domainTrack), FakeLyricsRepository(), this, errorStore = errors)
            try {
                val old = async { facade.playQueue(listOf("a"), 0) }
                f.entered("a")
                assertTrue(facade.playQueue(listOf("c"), 0))
                gate.completeExceptionally(IOException("old failure"))
                assertTrue(old.await())
                f.drain()
                assertEquals(null, errors.state.value.error)
                assertEquals(listOf("c"), f.player.loaded)
                assertTrue(f.errors.isEmpty())
            } finally { facade.close(); PlaybackQueueRuntime.detach(f.queue) }
        }
    }

    @Test fun `real runtime and facade never seek the replacement queue for an old next`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("b")
            val player = FakePlayerPort()
            val errors = PlaybackErrorStore(MonotonicErrorIdentity())
            PlaybackQueueRuntime.attach(f.queue, this, onError = { errors.reportQueue(it) })
            val facade = PlaybackControllerFacade(player, PlaybackQueueRuntimeCommands(::domainTrack), FakeLyricsRepository(), this, errorStore = errors)
            try {
                assertTrue(facade.playQueue(listOf("a", "b"), 0))
                f.entered("b")
                val old = async { facade.next() }
                yield()
                assertTrue(facade.playQueue(listOf("c"), 0))
                gate.completeExceptionally(IOException("old next failure"))
                assertTrue(old.await())
                f.drain()
                assertEquals(null, errors.state.value.error)
                assertEquals(0, player.nextCalls)
                assertTrue(f.player.sought.isEmpty())
                assertTrue(f.errors.isEmpty())
            } finally { facade.close(); PlaybackQueueRuntime.detach(f.queue) }
        }
    }

    @Test fun `cancelling one next waiter preserves shared in flight resolution`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("b")
            assertTrue(f.queue.submitAndPlay(listOf(track("a"), track("b")), 0).await())
            f.entered("b")
            val first = f.queue.requestNext()
            yield()
            first.cancelAndJoin()
            val second = f.queue.requestNext()
            yield()
            assertEquals(1, f.calls["b"])
            gate.complete(Unit)
            assertTrue(second.await())
            f.drain()
            assertEquals(1, f.calls["b"])
            assertEquals(listOf("b"), f.player.sought)
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun `cancelling start externally releases next before the resolver finishes`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("a")
            val start = f.queue.submitAndPlay(listOf(track("a"), track("b")), 0)
            f.entered("a")
            val next = f.queue.requestNext()
            f.entered("b")
            start.cancelAndJoin()
            assertTrue(withTimeout(2_000) { runCatching { next.await() }.exceptionOrNull() } is PlaybackCommandSupersededException)
            gate.complete(Unit)
            f.drain()
            assertTrue(f.errors.isEmpty())
            assertTrue(f.player.loaded.isEmpty())
            assertTrue(f.player.sought.isEmpty())
        }
    }

    @Test fun `late failed start cannot report an error for a newer queue`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("a")
            val old = f.queue.submitAndPlay(listOf(track("a")), 0)
            f.entered("a")
            assertTrue(f.queue.submitAndPlay(listOf(track("c")), 0).await())
            gate.completeExceptionally(IOException("old request failed"))
            assertTrue(runCatching { old.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
            f.drain()
            assertEquals(listOf("c"), f.player.loaded)
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun `late next and prefetch failures cannot report errors for a newer queue`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("b")
            assertTrue(f.queue.submitAndPlay(listOf(track("a"), track("b")), 0).await())
            f.entered("b")
            val old = f.queue.requestNext()
            yield()
            assertTrue(f.queue.submitAndPlay(listOf(track("c")), 0).await())
            gate.completeExceptionally(IOException("old next failed"))
            assertTrue(runCatching { old.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
            f.drain()
            assertTrue(f.player.sought.isEmpty())
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun `late previous failure cannot report an error for a newer queue`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("a")
            assertTrue(f.queue.submitAndPlay(listOf(track("a"), track("b")), 1).await())
            val old = f.queue.requestPrevious()
            f.entered("a")
            assertTrue(f.queue.submitAndPlay(listOf(track("c")), 0).await())
            gate.completeExceptionally(IOException("old previous failed"))
            assertTrue(runCatching { old.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
            f.drain()
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun `late enqueue failure cannot report an error for a newer queue`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("x")
            assertTrue(f.queue.submitAndPlay(listOf(track("a")), 0).await())
            val old = f.queue.enqueueNext(track("x"))
            f.entered("x")
            assertTrue(f.queue.submitAndPlay(listOf(track("c")), 0).await())
            gate.completeExceptionally(IOException("old enqueue failed"))
            assertTrue(runCatching { old.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
            f.drain()
            assertEquals(listOf("c"), f.queue.snapshot.value.queue.map { it.id })
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun `cancelled initial resolution is silent and releases next waiting for start`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("a")
            val start = f.queue.submitAndPlay(listOf(track("a"), track("b")), 0)
            f.entered("a")
            val next = f.queue.requestNext()
            f.entered("b")
            gate.completeExceptionally(CancellationException("cancelled resolution"))
            assertTrue(runCatching { start.await() }.exceptionOrNull() is CancellationException)
            assertTrue(withTimeout(2_000) { runCatching { next.await() }.exceptionOrNull() } is PlaybackCommandSupersededException)
            f.drain()
            assertTrue(f.errors.isEmpty())
            assertTrue(f.player.loaded.isEmpty())
            assertTrue(f.player.sought.isEmpty())
        }
    }

    @Test fun `cancelled next and shared prefetch do not become playback errors`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("b")
            assertTrue(f.queue.submitAndPlay(listOf(track("a"), track("b")), 0).await())
            f.entered("b")
            val next = f.queue.requestNext()
            yield()
            gate.completeExceptionally(CancellationException("cancelled resolution"))
            assertTrue(runCatching { next.await() }.exceptionOrNull() is CancellationException)
            f.drain()
            assertTrue(f.errors.isEmpty())
            assertEquals(1, f.calls["b"])
            assertTrue(f.player.sought.isEmpty())
        }
    }

    @Test fun `cancelled previous is silent`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("a")
            assertTrue(f.queue.submitAndPlay(listOf(track("a"), track("b")), 1).await())
            val previous = f.queue.requestPrevious()
            f.entered("a")
            gate.completeExceptionally(CancellationException("cancelled resolution"))
            assertTrue(runCatching { previous.await() }.exceptionOrNull() is CancellationException)
            f.drain()
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun `cancelled enqueue is silent and leaves the queue intact`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("x")
            assertTrue(f.queue.submitAndPlay(listOf(track("a")), 0).await())
            val enqueue = f.queue.enqueueNext(track("x"))
            f.entered("x")
            gate.completeExceptionally(CancellationException("cancelled resolution"))
            assertTrue(runCatching { enqueue.await() }.exceptionOrNull() is CancellationException)
            f.drain()
            assertTrue(f.errors.isEmpty())
            assertEquals(listOf("a"), f.queue.snapshot.value.queue.map { it.id })
        }
    }

    @Test fun `a genuine current start failure still emits a retryable error`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block("a")
            val start = f.queue.submitAndPlay(listOf(track("a")), 0)
            f.entered("a")
            gate.completeExceptionally(IOException("current request failed"))
            assertFalse(start.await())
            f.drain()
            assertEquals(PlaybackQueueAction.START, f.errors.single().action)
            assertTrue(f.errors.single().retryable)
        }
    }

    private class Fixture(context: CoroutineContext) : AutoCloseable {
        private val job = SupervisorJob(context[Job])
        private val scope = CoroutineScope(context + job)
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val calls = mutableMapOf<String, Int>()
        val errors = mutableListOf<PlaybackQueueError>()
        val player = RecordingPlayer()
        val queue = PlaybackQueueCoordinator(object : QueueTrackResolver {
            override suspend fun resolve(track: QueueTrack): ResolvedQueueTrack {
                calls[track.id] = (calls[track.id] ?: 0) + 1
                gates[track.id]?.await()
                return ResolvedQueueTrack(track, "https://webfs.kugou.com/fixture.mp3", "128")
            }
        }, player, scope)
        private val collector = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            queue.errors.collect { errors += it }
        }
        fun block(id: String) = CompletableDeferred<Unit>().also { gates[id] = it }
        suspend fun entered(id: String) = withTimeout(2_000) { while (calls[id] == null) yield() }
        suspend fun drain() = withTimeout(2_000) {
            while (job.children.any { it.isActive }) yield()
            yield() // Let the already-subscribed error collector consume emissions.
        }
        override fun close() { collector.cancel(); job.cancel() }
    }

    private class RecordingPlayer : QueuePlayerPort {
        val loaded = mutableListOf<String>()
        val sought = mutableListOf<String>()
        private val items = mutableSetOf<String>()
        override fun contains(trackId: String) = trackId in items
        override fun loadAndPlay(item: ResolvedQueueTrack) { loaded += item.track.id; items.clear(); items += item.track.id }
        override fun loadPaused(item: ResolvedQueueTrack, positionMs: Long) { loaded += item.track.id; items.clear(); items += item.track.id }
        override fun append(item: ResolvedQueueTrack) { items += item.track.id }
        override fun prependBeforeCurrent(item: ResolvedQueueTrack) { items += item.track.id }
        override fun insertAfterCurrent(item: ResolvedQueueTrack) { items += item.track.id }
        override fun seekTo(trackId: String) { sought += trackId }
        override fun clear() { items.clear() }
    }

    companion object {
        private fun track(id: String) = QueueTrack(id, "Title $id", emptyList(), "", source = PlaybackSource.Local("content://fixture/$id"))
        private fun domainTrack(id: String) = Track(id, "Title $id", emptyList(), "", "", 180_000L, listOf("128"), PlaybackSource.Local("content://fixture/$id"))
    }
}
