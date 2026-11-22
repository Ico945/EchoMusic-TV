package com.kutv.music.application.playback

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Track
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import com.kutv.music.player.PlaybackQueueCommands
import com.kutv.music.player.PlaybackQueueError
import com.kutv.music.player.PlaybackQueueRuntime
import com.kutv.music.player.PlaybackQueueSnapshot
import com.kutv.music.player.QueueTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCommandsTest {
    @Test
    fun `empty player snapshot clears synchronized queue`() = runBlocking {
        val track = Track(
            id = "a",
            title = "Track A",
            artists = listOf("Artist A"),
            album = "Album A",
            artworkUri = "",
            durationMs = 180_000,
            qualities = listOf("128"),
            source = PlaybackSource.Kugou("a", "HASH_A"),
        )
        val commands = PlaybackQueueRuntimeCommands { track }
        commands.synchronize(
            PlayerPortSnapshot(
                currentTrack = track,
                queue = listOf(track),
                queueIndex = 0,
            ),
        )
        commands.synchronize(PlayerPortSnapshot())

        val failure = runCatching { commands.select("a") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `partial media snapshot reconciles against complete logical queue`() = runBlocking {
        val tracks = listOf(track("a"), track("b"), track("c"), track("d"))
        val logical = PlaybackQueueSnapshot(tracks.map { it.toQueueTrackForTest() }, currentIndex = 0)
        val runtime = FakeRuntimeCommands(logical)
        PlaybackQueueRuntime.attach(runtime, this)
        val commands = PlaybackQueueRuntimeCommands(trackLookup = { id -> tracks.firstOrNull { it.id == id } })

        commands.synchronize(
            PlayerPortSnapshot(
                currentTrack = tracks[0],
                queue = tracks.take(2),
                queueIndex = 0,
            ),
        )
        commands.select("d")

        assertEquals(listOf("a", "b", "c", "d"), runtime.snapshot.value.queue.map { it.id })
        assertEquals("d", runtime.selectedId)
        PlaybackQueueRuntime.detach(runtime)
    }

    @Test
    fun `play next delegates enqueue without resubmitting or seeking`() = runBlocking {
        val tracks = listOf(track("a"), track("b"), track("c"))
        val runtime = FakeRuntimeCommands(
            PlaybackQueueSnapshot(tracks.map { it.toQueueTrackForTest() }, currentIndex = 0),
        )
        PlaybackQueueRuntime.attach(runtime, this)
        val commands = PlaybackQueueRuntimeCommands(trackLookup = { id -> tracks.firstOrNull { it.id == id } })
        commands.synchronize(PlayerPortSnapshot(currentTrack = tracks[0], queue = tracks.take(2), queueIndex = 0))

        commands.playNext("c")

        assertEquals(0, runtime.submitCalls)
        assertEquals(listOf("c"), runtime.enqueued.map { it.id })
        PlaybackQueueRuntime.detach(runtime)
    }

    @Test
    fun `play next followed immediately by quality uses updated logical order`() = runBlocking {
        val tracks = listOf(track("a"), track("b"), track("c"), track("d"))
        val runtime = FakeRuntimeCommands(
            PlaybackQueueSnapshot(tracks.take(3).map { it.toQueueTrackForTest() }, currentIndex = 0),
        )
        PlaybackQueueRuntime.attach(runtime, this)
        val commands = PlaybackQueueRuntimeCommands(trackLookup = { id -> tracks.firstOrNull { it.id == id } })
        commands.synchronize(PlayerPortSnapshot(currentTrack = tracks[0], queue = tracks.take(2), queueIndex = 0))

        commands.playNext("d")
        commands.setQuality(listOf("a", "b"), 0, "320")

        assertEquals(listOf("a", "d", "b", "c"), runtime.snapshot.value.queue.map { it.id })
        assertEquals(0, runtime.submitCalls)
        PlaybackQueueRuntime.detach(runtime)
    }

    @Test
    fun `quality replacement retains logical queue tail without resubmit`() = runBlocking {
        val tracks = listOf(track("a"), track("b"), track("c"), track("d"))
        val runtime = FakeRuntimeCommands(
            PlaybackQueueSnapshot(tracks.map { it.toQueueTrackForTest() }, currentIndex = 0),
        )
        PlaybackQueueRuntime.attach(runtime, this)
        val commands = PlaybackQueueRuntimeCommands(trackLookup = { id -> tracks.firstOrNull { it.id == id } })
        commands.synchronize(PlayerPortSnapshot(currentTrack = tracks[0], queue = tracks.take(2), queueIndex = 0))

        commands.setQuality(listOf("a", "b"), 0, "320")

        assertEquals(listOf("a", "b", "c", "d"), runtime.snapshot.value.queue.map { it.id })
        assertEquals(listOf("128", "320", "flac"), runtime.snapshot.value.queue.first().qualities)
        assertEquals("320", (runtime.snapshot.value.queue.first().source as PlaybackSource.Kugou).preferredQuality)
        PlaybackQueueRuntime.detach(runtime)
    }

    private fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artists = listOf("Artist $id"),
        album = "Album $id",
        artworkUri = "",
        durationMs = 180_000,
        qualities = listOf("128", "320", "flac"),
        source = PlaybackSource.Kugou(id, "HASH_$id"),
    )

    private fun Track.toQueueTrackForTest() = QueueTrack(
        id = id,
        title = title,
        artists = artists,
        artworkUrl = artworkUri,
        album = album,
        source = source,
        durationMs = durationMs,
        qualities = qualities,
    )

    private class FakeRuntimeCommands(initial: PlaybackQueueSnapshot) : PlaybackQueueCommands {
        override val errors: SharedFlow<PlaybackQueueError> = MutableSharedFlow()
        private val mutableSnapshot = MutableStateFlow(initial)
        override val snapshot: StateFlow<PlaybackQueueSnapshot> = mutableSnapshot
        var selectedId: String? = null
        var submitCalls = 0
        var lastSubmitted: List<QueueTrack> = emptyList()
        var lastStartIndex = -1
        val enqueued = mutableListOf<QueueTrack>()

        override fun submitAndPlay(queue: List<QueueTrack>, startIndex: Int): Deferred<Boolean> {
            submitCalls += 1
            lastSubmitted = queue
            lastStartIndex = startIndex
            mutableSnapshot.value = PlaybackQueueSnapshot(queue, startIndex)
            return CompletableDeferred(true)
        }

        override fun requestSelect(trackId: String, positionMs: Long, play: Boolean): Deferred<Boolean> {
            selectedId = trackId
            return CompletableDeferred(true)
        }

        override fun setCurrentQuality(trackId: String, quality: String): Deferred<Boolean> {
            val current = mutableSnapshot.value
            mutableSnapshot.value = current.copy(queue = current.queue.map {
                if (it.id == trackId) it.copy(source =
                    (it.source as PlaybackSource.Kugou).copy(preferredQuality = quality)) else it
            })
            return CompletableDeferred(true)
        }

        override fun enqueueNext(track: QueueTrack): Deferred<Boolean> {
            enqueued += track
            val current = mutableSnapshot.value
            val currentId = current.queue.getOrNull(current.currentIndex)?.id
            val updated = current.queue.toMutableList().apply {
                removeAll { it.id == track.id }
                val index = indexOfFirst { it.id == currentId }
                add((index + 1).coerceIn(0, size), track)
            }
            mutableSnapshot.value = PlaybackQueueSnapshot(
                queue = updated,
                currentIndex = updated.indexOfFirst { it.id == currentId },
            )
            return CompletableDeferred(true)
        }

        override fun requestNext(): Deferred<Boolean> = CompletableDeferred(false)
        override fun requestPrevious(): Deferred<Boolean> = CompletableDeferred(false)
        override fun onMediaItemTransition(trackId: String) = Unit
        override fun clear() = CompletableDeferred(true)
    }
}
