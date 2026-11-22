package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Test
import com.kutv.music.video.PlaybackOwnership

class PlaybackQueueRuntimeTest {

    @Test
    fun `commands return null while no service coordinator is attached`() {
        val detached = FakeCommands("detached")
        PlaybackQueueRuntime.detach(detached)

        assertNull(PlaybackQueueRuntime.submitAndPlay(listOf(track("a")), 0))
        assertNull(PlaybackQueueRuntime.requestNext())
        assertNull(PlaybackQueueRuntime.requestPrevious())
    }

    @Test
    fun `detach only removes the same attached coordinator`() = runBlocking {
        val first = FakeCommands("first")
        val second = FakeCommands("second")
        PlaybackQueueRuntime.attach(first, this)
        PlaybackQueueRuntime.attach(second, this)

        PlaybackQueueRuntime.detach(first)

        assertSame(second.submitResult, PlaybackQueueRuntime.submitAndPlay(listOf(track("a")), 0))
        assertEquals(1, second.submitCalls)
        PlaybackQueueRuntime.detach(second)
    }

    @Test
    fun `runtime forwards queue errors from attached coordinator`() = runBlocking {
        val commands = FakeCommands("active")
        PlaybackQueueRuntime.attach(commands, this)
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            PlaybackQueueRuntime.errors.first()
        }
        yield()

        val expected = PlaybackQueueError(
            action = PlaybackQueueAction.PREFETCH,
            trackId = "b",
            message = "下一首加载失败",
            retryable = true,
        )
        commands.mutableErrors.emit(expected)

        assertEquals(expected, received.await())
        PlaybackQueueRuntime.detach(commands)
    }

    @Test
    fun `service error sink receives every queue error without an Activity collector`() = runBlocking {
        val commands = FakeCommands("active")
        val observed = mutableListOf<PlaybackQueueError>()
        PlaybackQueueRuntime.attach(commands, this, onError = { observed += it })
        val expected = PlaybackQueueError(
            action = PlaybackQueueAction.NEXT,
            trackId = "raw-next-id",
            message = "untrusted detail",
            retryable = true,
        )

        commands.mutableErrors.emit(expected)
        yield()

        assertEquals(listOf(expected), observed)
        PlaybackQueueRuntime.detach(commands)
    }

    @Test
    fun `runtime exposes complete logical queue snapshot and forwards enqueue next`() = runBlocking {
        val commands = FakeCommands("active")
        commands.mutableSnapshot.value = PlaybackQueueSnapshot(
            queue = listOf(track("a"), track("b"), track("c")),
            currentIndex = 0,
        )
        PlaybackQueueRuntime.attach(commands, this)
        yield()

        assertEquals(listOf("a", "b", "c"), PlaybackQueueRuntime.snapshot.value.queue.map { it.id })
        assertSame(commands.enqueueResult, PlaybackQueueRuntime.enqueueNext(track("d")))
        assertEquals(listOf("d"), commands.enqueuedIds)

        PlaybackQueueRuntime.detach(commands)
    }

    @Test
    fun `current snapshot reads attached coordinator synchronously`() = runBlocking {
        val commands = FakeCommands("active")
        PlaybackQueueRuntime.attach(commands, this)
        val latest = PlaybackQueueSnapshot(
            queue = listOf(track("a"), track("d"), track("b")),
            currentIndex = 0,
        )

        commands.mutableSnapshot.value = latest

        assertEquals(latest, PlaybackQueueRuntime.currentSnapshot())
        PlaybackQueueRuntime.detach(commands)
    }

    @Test
    fun `MV ownership refuses every process queue entry without touching coordinator`() = runBlocking {
        val commands = FakeCommands("active")
        val ownership = PlaybackOwnership()
        PlaybackQueueRuntime.attach(commands, this, allowsMusicPlayback = { ownership.allowsMusicPlayback })
        ownership.claimMv(Any())

        assertNull(PlaybackQueueRuntime.submitAndPlay(listOf(track("a")), 0))
        assertNull(PlaybackQueueRuntime.enqueueNext(track("b")))
        assertNull(PlaybackQueueRuntime.requestNext())
        assertNull(PlaybackQueueRuntime.requestPrevious())
        assertSame(commands.clearResult, PlaybackQueueRuntime.clear())
        assertEquals(0, commands.submitCalls)
        assertEquals(0, commands.enqueueCalls)
        assertEquals(0, commands.nextCalls)
        assertEquals(0, commands.previousCalls)
        assertEquals(1, commands.clearCalls)
        assertFalse(ownership.allowsMusicPlayback)
        PlaybackQueueRuntime.detach(commands)
    }

    private fun track(id: String) = QueueTrack(
        id = id,
        title = "Title $id",
        artists = listOf("Artist $id"),
        artworkUrl = "https://img.example/$id.jpg",
        source = PlaybackSource.Local("android.resource://com.kutv.music/raw/$id"),
    )

    private class FakeCommands(private val name: String) : PlaybackQueueCommands {
        val mutableErrors = MutableSharedFlow<PlaybackQueueError>(extraBufferCapacity = 1)
        override val errors: SharedFlow<PlaybackQueueError> = mutableErrors
        val mutableSnapshot = MutableStateFlow(PlaybackQueueSnapshot())
        override val snapshot: StateFlow<PlaybackQueueSnapshot> = mutableSnapshot
        val submitResult = CompletableDeferred(true)
        val enqueueResult = CompletableDeferred(true)
        private val nextResult = CompletableDeferred(true)
        private val previousResult = CompletableDeferred(true)
        val clearResult = CompletableDeferred(true)
        var submitCalls = 0
        var enqueueCalls = 0
        var nextCalls = 0
        var previousCalls = 0
        var clearCalls = 0
        val enqueuedIds = mutableListOf<String>()

        override fun submitAndPlay(queue: List<QueueTrack>, startIndex: Int): Deferred<Boolean> {
            submitCalls += 1
            return submitResult
        }

        override fun requestNext(): Deferred<Boolean> { nextCalls += 1; return nextResult }
        override fun requestPrevious(): Deferred<Boolean> { previousCalls += 1; return previousResult }
        override fun enqueueNext(track: QueueTrack): Deferred<Boolean> {
            enqueueCalls += 1
            enqueuedIds += track.id
            return enqueueResult
        }
        override fun onMediaItemTransition(trackId: String) = Unit
        override fun clear(): Deferred<Boolean> { clearCalls += 1; return clearResult }
        override fun toString(): String = name
    }
}
