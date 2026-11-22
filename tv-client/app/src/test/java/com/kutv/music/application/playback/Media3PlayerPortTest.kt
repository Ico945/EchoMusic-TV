package com.kutv.music.application.playback

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Track
import com.kutv.music.player.PlaybackQueueSnapshot
import com.kutv.music.player.QueueTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlayerPortTest {
    @Test
    fun `latest event channel retains newest snapshot after burst`() = runBlocking {
        val events = LatestPlayerPortEvents()

        repeat(100) { position ->
            events.emit(PlayerPortEvent.Snapshot(PlayerPortSnapshot(positionMs = position.toLong())))
        }

        val latest = events.flow.first() as PlayerPortEvent.Snapshot
        assertEquals(99L, latest.value.positionMs)
    }

    @Test
    fun `audio event does not displace retained latest player snapshot`() = runBlocking {
        val events = LatestPlayerPortEvents()
        events.emit(PlayerPortEvent.Snapshot(PlayerPortSnapshot(positionMs = 42L)))
        events.emit(PlayerPortEvent.AudioSessionChanged(7))

        val retained = events.flow.filterIsInstance<PlayerPortEvent.Snapshot>().first()

        assertEquals(42L, retained.value.positionMs)
    }

    @Test
    fun `logical queue wins over partial Media3 timeline and keeps qualities`() {
        val logical = PlaybackQueueSnapshot(
            queue = listOf(queueTrack("a"), queueTrack("b"), queueTrack("c"), queueTrack("d")),
            currentIndex = 0,
        )
        val physical = listOf(track("a"), track("b"))

        val reconciled = reconcilePlayerQueue(logical, physical, currentTrackId = "a")

        assertEquals(listOf("a", "b", "c", "d"), reconciled.queue.map { it.id })
        assertEquals(listOf("128", "320", "flac"), reconciled.queue.last().qualities)
        assertEquals(0, reconciled.currentIndex)
    }

    @Test
    fun `persisted logical queue is visible while restored media is still resolving`() {
        val logical = PlaybackQueueSnapshot(
            queue = listOf(queueTrack("a"), queueTrack("b"), queueTrack("c")),
            currentIndex = 1,
        )

        val reconciled = reconcilePlayerQueue(logical, emptyList(), currentTrackId = null)

        assertEquals(listOf("a", "b", "c"), reconciled.queue.map { it.id })
        assertEquals(1, reconciled.currentIndex)
    }

    @Test
    fun `logical queue projection strips playback uri and Kugou hash`() {
        val logical = PlaybackQueueSnapshot(
            queue = listOf(
                queueTrack("remote"),
                QueueTrack(
                    id = "local",
                    title = "Local",
                    artists = emptyList(),
                    artworkUrl = "",
                    source = PlaybackSource.Local("android.resource://com.kutv.music/raw/private"),
                    qualities = listOf("LOCAL"),
                ),
            ),
            currentIndex = 0,
        )

        val projection = reconcilePlayerQueue(logical, emptyList(), currentTrackId = "remote").queue

        assertEquals("", (projection[0].source as PlaybackSource.Kugou).hash)
        assertEquals("", (projection[1].source as PlaybackSource.Local).uri)
        assertTrue(projection.flatMap { it.qualities }.containsAll(listOf("128", "320", "flac", "LOCAL")))
    }

    private fun queueTrack(id: String) = QueueTrack(
        id = id,
        title = "Track $id",
        artists = listOf("Artist $id"),
        artworkUrl = "",
        album = "Album $id",
        source = PlaybackSource.Kugou(id, "HASH_$id"),
        durationMs = 180_000,
        qualities = listOf("128", "320", "flac"),
    )

    private fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artists = listOf("Artist $id"),
        album = "Album $id",
        artworkUri = "",
        durationMs = 180_000,
        qualities = listOf("128"),
        source = PlaybackSource.Kugou(id, ""),
    )
}
