package com.kutv.music.application

import com.kutv.music.core.model.Capability
import com.kutv.music.core.model.HomeContent
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Shelf
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import com.kutv.music.data.MusicRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUseCaseMvTest {
    @Test
    fun `home exposes typed MV detail only for a track with a native MV id`() = runBlocking {
        val nativeMvTrack = track("with-mv", mvId = "9001")
        val audioOnlyTrack = track("audio-only")
        val content = HomeContent(
            hero = audioOnlyTrack,
            secondary = emptyList(),
            shelves = listOf(Shelf("native", "Native", listOf(audioOnlyTrack, nativeMvTrack), Capability.AVAILABLE)),
        )
        val payload = HomeUseCase(StaticRepository(content)).home()
        val tracks = payload.getJSONArray("shelves").getJSONObject(0).getJSONArray("tracks")

        assertFalse(tracks.getJSONObject(0).has("mv"))
        val mv = tracks.getJSONObject(1).getJSONObject("mv")
        assertEquals("mv:9001", mv.getString("id"))
        assertEquals("with-mv title", mv.getString("title"))
        assertEquals("Artist", mv.getString("artist"))
        assertEquals("hd", mv.getString("quality"))
        assertEquals(listOf("hd"), mv.getJSONArray("qualities").let { array -> (0 until array.length()).map(array::getString) })
        assertFalse(payload.toString().contains("AUDIO_SECRET"))
    }

    private fun track(id: String, mvId: String? = null) = Track(
        id = id,
        title = "$id title",
        artists = listOf("Artist"),
        album = "Album",
        artworkUri = "artwork",
        durationMs = 1_000L,
        qualities = listOf("128"),
        source = PlaybackSource.Kugou(id, "AUDIO_SECRET"),
        mvId = mvId,
    )

    private class StaticRepository(private val content: HomeContent) : MusicRepository {
        override suspend fun home() = Result.success(content)
        override suspend fun newReleases() = Result.success(emptyList<Track>())
        override suspend fun library() = Result.success(emptyList<Track>())
        override suspend fun search(query: String) = Result.success(emptyList<Track>())
        override suspend fun lyrics(trackId: String) = Result.success(emptyList<TimedLyricLine>())
    }
}
