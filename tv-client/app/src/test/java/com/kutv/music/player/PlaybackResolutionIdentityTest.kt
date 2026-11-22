package com.kutv.music.player

import com.kutv.music.application.playback.PlaybackQueueRuntimeCommands
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Track
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class PlaybackResolutionIdentityTest {
    @Test fun `same song with a different requested quality does not reuse the old stream`() =
        verifySourceChange(remote("128"), remote("flac"))

    @Test fun `same song id with a changed catalog hash resolves independently`() =
        verifySourceChange(remote("128"), remote("128").copy(hash = "NEW_HASH"))

    @Test fun `same local id with a changed URI resolves independently`() =
        verifySourceChange(PlaybackSource.Local("content://fixture/old"), PlaybackSource.Local("content://fixture/new"))

    @Test fun `same id changing from local to Kugou does not reuse a local URI`() =
        verifySourceChange(PlaybackSource.Local("content://fixture/old"), remote("128"))

    @Test fun `identical source shares resolution but projects the latest queue metadata`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val source = remote("128")
            val gate = f.block(source)
            val oldTrack = queueTrack(source)
            val updated = oldTrack.copy(title = "New title", artists = listOf("New artist"),
                album = "New album", artworkUrl = "https://img.example/new.jpg", durationMs = 200_000,
                qualities = listOf("128", "flac"))
            val old = f.queue.submitAndPlay(listOf(oldTrack), 0)
            f.entered(source)
            val current = f.queue.submitAndPlay(listOf(updated), 0)
            yield()
            gate.complete(Unit)
            assertTrue(current.await())
            assertTrue(runCatching { old.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
            f.drain()
            assertEquals(1, f.calls.size)
            assertEquals(updated, f.player.loaded.single().track)
        }
    }

    @Test fun `runtime quality command resolves the newly requested quality while old work is pending`() = runBlocking {
        Fixture(coroutineContext).use { f ->
            val initial = remote("128")
            val requested = remote("flac")
            val gate = f.block(initial)
            PlaybackQueueRuntime.attach(f.queue, this)
            val commands = PlaybackQueueRuntimeCommands { id ->
                Track(id, "Song", listOf("Artist"), "Album", "", 180_000,
                    listOf("128", "flac"), initial)
            }
            try {
                val old = async { commands.playQueue(listOf("song"), 0) }
                f.entered(initial)
                withTimeout(2_000) { commands.setQuality(listOf("song"), 0, "flac") }
                assertEquals(requested, f.player.loaded.single().track.source)
                assertEquals("flac", f.player.loaded.single().quality)
                gate.completeExceptionally(IOException("old resolution failed"))
                assertTrue(runCatching { old.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
                f.drain()
                assertEquals(listOf(initial, requested), f.calls)
            } finally { PlaybackQueueRuntime.detach(f.queue) }
        }
    }

    private fun verifySourceChange(initial: PlaybackSource, requested: PlaybackSource) = runBlocking {
        Fixture(coroutineContext).use { f ->
            val gate = f.block(initial)
            val old = f.queue.submitAndPlay(listOf(queueTrack(initial)), 0)
            f.entered(initial)
            assertTrue(withTimeout(2_000) { f.queue.submitAndPlay(listOf(queueTrack(requested)), 0).await() })
            assertEquals(requested, f.player.loaded.single().track.source)
            gate.completeExceptionally(IOException("old source failed"))
            assertTrue(runCatching { old.await() }.exceptionOrNull() is PlaybackCommandSupersededException)
            f.drain()
            assertEquals(listOf(initial, requested), f.calls)
            assertEquals(1, f.player.loaded.size)
            assertTrue(f.errors.isEmpty())
        }
    }

    private class Fixture(context: CoroutineContext) : AutoCloseable {
        private val job = SupervisorJob(context[Job])
        private val scope = CoroutineScope(context + job)
        private val gates = mutableMapOf<PlaybackSource, CompletableDeferred<Unit>>()
        val calls = mutableListOf<PlaybackSource>()
        val errors = mutableListOf<PlaybackQueueError>()
        val player = RecordingPlayer()
        val queue = PlaybackQueueCoordinator(object : QueueTrackResolver {
            override suspend fun resolve(track: QueueTrack): ResolvedQueueTrack {
                calls += track.source
                gates[track.source]?.await()
                val quality = (track.source as? PlaybackSource.Kugou)?.preferredQuality ?: "LOCAL"
                return ResolvedQueueTrack(track, "https://webfs.kugou.com/fixture.$quality", quality)
            }
        }, player, scope)
        private val collector = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            queue.errors.collect { errors += it }
        }
        fun block(source: PlaybackSource) = CompletableDeferred<Unit>().also { gates[source] = it }
        suspend fun entered(source: PlaybackSource) = withTimeout(2_000) { while (source !in calls) yield() }
        suspend fun drain() = withTimeout(2_000) { while (job.children.any { it.isActive }) yield(); yield() }
        override fun close() { collector.cancel(); job.cancel() }
    }

    private class RecordingPlayer : QueuePlayerPort {
        val loaded = mutableListOf<ResolvedQueueTrack>()
        override fun contains(trackId: String) = loaded.lastOrNull()?.track?.id == trackId
        override fun loadAndPlay(item: ResolvedQueueTrack) { loaded += item }
        override fun loadPaused(item: ResolvedQueueTrack, positionMs: Long) { loaded += item }
        override fun append(item: ResolvedQueueTrack) = Unit
        override fun prependBeforeCurrent(item: ResolvedQueueTrack) = Unit
        override fun insertAfterCurrent(item: ResolvedQueueTrack) = Unit
        override fun seekTo(trackId: String) = Unit
        override fun clear() { loaded.clear() }
    }

    companion object {
        private fun remote(quality: String) = PlaybackSource.Kugou("song", "HASH", preferredQuality = quality)
        private fun queueTrack(source: PlaybackSource) = QueueTrack("song", "Old title", listOf("Artist"), "", source = source)
    }
}
