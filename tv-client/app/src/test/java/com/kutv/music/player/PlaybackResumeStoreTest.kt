package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumeStoreTest {
    @Test
    fun `writer close serializes final state after an older in flight write`() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val writes = mutableListOf<PlaybackResumeState?>()
        val store = object : PlaybackResumeStore {
            override fun load(): PlaybackResumeState? = null
            override fun save(state: PlaybackResumeState) {
                if (first.compareAndSet(true, false)) {
                    firstEntered.countDown()
                    check(releaseFirst.await(2, TimeUnit.SECONDS))
                }
                synchronized(writes) { writes += state }
            }
            override fun clear() {
                synchronized(writes) { writes += null }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = PlaybackResumeWriter(store, scope)
        val old = stateAt(1_000L)
        val latest = stateAt(9_000L)

        writer.submit(old)
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        val close = async(Dispatchers.Default) { writer.closeAndSave(latest) }
        releaseFirst.countDown()
        close.await()
        writer.submit(old)
        delay(50)

        assertEquals(latest, synchronized(writes) { writes.last() })
        scope.cancel()
    }

    @Test
    fun `codec round trips complete queue sources and playback position`() {
        val state = PlaybackResumeState(
            queue = listOf(
                QueueTrack(
                    id = "song-a",
                    title = "Song A",
                    artists = listOf("Artist A", "Artist B"),
                    artworkUrl = "https://imge.kugou.com/a.jpg",
                    album = "Album",
                    source = PlaybackSource.Kugou(
                        trackId = "audio-a",
                        hash = "HASH_A",
                        albumId = "album-a",
                        lyricId = "lyric-a",
                        preferredQuality = "320",
                    ),
                    durationMs = 180_000L,
                    qualities = listOf("128", "320"),
                ),
                QueueTrack(
                    id = "local-b",
                    title = "Local B",
                    artists = emptyList(),
                    artworkUrl = "",
                    source = PlaybackSource.Local("android.resource://com.kutv.music/raw/b"),
                ),
            ),
            currentIndex = 1,
            positionMs = 12_345L,
            repeatMode = 2,
            savedAtEpochMs = 1_700_000_000_000L,
        )

        val raw = PlaybackResumeCodec.encode(state)

        assertEquals(state, PlaybackResumeCodec.decode(raw))
        assertFalse(raw.contains("webfs.kugou.com"))
        assertFalse(raw.contains("token", ignoreCase = true))
    }

    @Test
    fun `codec rejects invalid index negative position unknown source and oversized payload`() {
        val base = """{"version":1,"currentIndex":0,"positionMs":0,"repeatMode":0,"savedAtEpochMs":1,"queue":[{"id":"a","title":"A","artists":[],"artworkUrl":"","album":"","durationMs":0,"qualities":[],"source":{"kind":"kugou","trackId":"a","hash":"HASH","preferredQuality":"128"}}]}"""

        assertNull(PlaybackResumeCodec.decode(base.replace("\"currentIndex\":0", "\"currentIndex\":2")))
        assertNull(PlaybackResumeCodec.decode(base.replace("\"positionMs\":0", "\"positionMs\":-1")))
        assertNull(PlaybackResumeCodec.decode(base.replace("\"kind\":\"kugou\"", "\"kind\":\"unknown\"")))
        assertNull(PlaybackResumeCodec.decode(" ".repeat(1_000_001)))
    }

    @Test
    fun `encoded resume payload contains source identity but no resolved URL field`() {
        val state = PlaybackResumeState(
            queue = listOf(
                QueueTrack(
                    id = "song",
                    title = "Song",
                    artists = listOf("Artist"),
                    artworkUrl = "",
                    source = PlaybackSource.Kugou("audio", "HASH"),
                ),
            ),
            currentIndex = 0,
            positionMs = 99L,
            repeatMode = 0,
            savedAtEpochMs = 100L,
        )

        val raw = PlaybackResumeCodec.encode(state)

        assertTrue(raw.contains("HASH"))
        assertFalse(raw.contains("streamUrl"))
        assertFalse(raw.contains("https://webfs"))
    }

    private fun stateAt(positionMs: Long) = PlaybackResumeState(
        queue = listOf(
            QueueTrack(
                id = "song",
                title = "Song",
                artists = emptyList(),
                artworkUrl = "",
                source = PlaybackSource.Kugou("song", "HASH"),
            ),
        ),
        currentIndex = 0,
        positionMs = positionMs,
        repeatMode = 0,
        savedAtEpochMs = positionMs,
    )
}
