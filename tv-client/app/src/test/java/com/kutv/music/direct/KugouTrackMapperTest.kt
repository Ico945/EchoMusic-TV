package com.kutv.music.direct

import com.kutv.music.core.model.PlaybackSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouTrackMapperTest {
    @Test fun `Echo search PascalCase fields preserve playable identity title artist cover and seconds`() {
        val raw = JSONObject("""{"MixSongID":321,"FileHash":"fixture-hash","SongName":"Search song","SingerName":"Search artist","AlbumID":81,"AlbumName":"Search album","Image":"http://imge.kugou.com/{size}/search.jpg","Duration":210}""")
        val track = checkNotNull(KugouTrackMapper.map(raw))
        assertEquals("321", track.id)
        assertEquals("Search song", track.title)
        assertEquals(listOf("Search artist"), track.artists)
        assertEquals("Search album", track.album)
        assertEquals("https://imge.kugou.com/400/search.jpg", track.artworkUri)
        assertEquals(210000L, track.durationMs)
        assertEquals("81", (track.source as PlaybackSource.Kugou).albumId)
    }

    @Test fun `blank title cannot invalidate the whole Bridge track list`() {
        val track = checkNotNull(KugouTrackMapper.map(JSONObject().put("hash", "fixture-hash").put("title", " ")))
        assertEquals("未命名歌曲", track.title)
        assertEquals(listOf("未知歌手"), track.artists)
    }

    @Test fun `Echo personal playlist hash 128 aliases remain playable`() {
        for (raw in listOf(
            JSONObject("""{"hash_128":"H1","album_audio_id":12,"name":"Liked"}"""),
            JSONObject("""{"audio_info":{"hash_128":"H2","audio_id":13},"name":"Liked"}"""),
        )) {
            val track = checkNotNull(KugouTrackMapper.map(raw))
            assertTrue((track.source as PlaybackSource.Kugou).hash.startsWith("H"))
            assertEquals("Liked", track.title)
        }
    }
    @Test fun `reads Echo feed timelength in milliseconds without scaling it twice`() {
        val raw = JSONObject("""{"hash":"H1","songname":"One","timelength":241763}""")
        assertEquals(241_763L, KugouTrackMapper.map(raw)?.durationMs)
    }

    @Test fun `explicit milliseconds take priority over legacy seconds`() {
        val raw = JSONObject("""{"hash":"H1","songname":"One","durationms":"180500","duration":180}""")
        assertEquals(180_500L, KugouTrackMapper.map(raw)?.durationMs)
    }

    @Test fun `reads Echo time length seconds with an explicit unit`() {
        val raw = JSONObject("""{"hash":"H1","songname":"One","time_length":"210"}""")
        assertEquals(210_000L, KugouTrackMapper.map(raw)?.durationMs)
    }

    @Test fun `invalid durations cannot become negative or hide a valid alias`() {
        val raw = JSONObject("""{"hash":"H1","songname":"One","durationms":-1,"timelength":0,"second":120}""")
        assertEquals(120_000L, KugouTrackMapper.map(raw)?.durationMs)
        assertEquals(0L, KugouTrackMapper.map(JSONObject("""{"hash":"H1","durationms":-1}"""))?.durationMs)
    }

    @Test fun `seconds overflow fails closed instead of wrapping`() {
        val raw = JSONObject().put("hash", "H1").put("duration", Long.MAX_VALUE)
        assertEquals(0L, KugouTrackMapper.map(raw)?.durationMs)
    }

    @Test
    fun `maps real sized and nested cover fields used by EchoMusic`() {
        listOf(
            """{"sizable_cover":"http://imge.kugou.com/{size}/cover.jpg"}""",
            """{"album_sizable_cover":"http://imge.kugou.com/{size}/cover.jpg"}""",
            """{"trans_param":{"union_cover":"http://imge.kugou.com/{size}/cover.jpg"}}""",
            """{"album_info":{"sizable_cover":"http://imge.kugou.com/{size}/cover.jpg"}}""",
        ).forEach { payload ->
            val raw = JSONObject(payload).put("hash", "H1").put("songname", "One")
            assertEquals("https://imge.kugou.com/400/cover.jpg", KugouTrackMapper.map(raw)?.artworkUri)
        }
    }

    @Test
    fun `does not turn absent upstream MV sentinels into focusable video entries`() {
        listOf<Any>(0, -1, "0", "000", "-1", "mv:0", "").forEach { id ->
            val raw = JSONObject().put("hash", "H1").put("songname", "One").put("video_id", id)
            assertEquals("Invalid MV marker must be absent", null, KugouTrackMapper.map(raw)?.mvId)
        }
    }

    @Test
    fun `invalid primary MV id does not hide a valid upstream video id`() {
        val raw = JSONObject("""{"hash":"H1","songname":"One","mv_id":0,"video_id":9001}""")
        assertEquals("9001", KugouTrackMapper.map(raw)?.mvId)
    }

    @Test
    fun `maps only whitelisted fields and keeps exact playback binding`() {
        val raw = JSONObject(
            """{
              "album_audio_id":"audio-7","hash":"ABCDEF","songname":"Song",
              "singername":"Artist A、Artist B","album_id":"album-3","album_name":"Album",
              "duration":180,"img":"https://imge.kugou.com/{size}/cover.jpg","lyric_id":"lyric-9",
              "token":"LEAK-TOKEN","dfid":"LEAK-DFID"
            }""",
        )

        val track = checkNotNull(KugouTrackMapper.map(raw))
        assertNotNull(track)

        assertEquals("audio-7", track.id)
        assertEquals(listOf("Artist A", "Artist B"), track.artists)
        assertEquals(180_000L, track.durationMs)
        assertEquals("https://imge.kugou.com/400/cover.jpg", track.artworkUri)
        val source = track.source as PlaybackSource.Kugou
        assertEquals("ABCDEF", source.hash)
        assertEquals("album-3", source.albumId)
        assertEquals("lyric-9", source.lyricId)
        assertFalse(track.toString().contains("LEAK"))
    }

    @Test
    fun `missing hash fails closed`() {
        assertTrue(KugouTrackMapper.map(JSONObject("""{"songname":"No Hash"}""")) == null)
    }

    @Test
    fun `extracts tracks from supported response containers`() {
        val response = JSONObject(
            """{"data":{"songs":[{"hash":"H1","songname":"One"},{"hash":"H2","songname":"Two"}]}}""",
        )
        assertEquals(listOf("H1", "H2"), KugouTrackMapper.extractTracks(response).map { it.id })
    }

    @Test
    fun `maps the nested album audio shape returned by the real lite endpoint`() {
        val response = JSONObject(
            """{"data":{"songs":[{"audio_info":{"hash":"NESTED_HASH","duration":241},"base":{"album_audio_id":76201,"album_id":3001,"author_name":"Nested Artist","audio_name":"Nested Song"},"album_info":{"album_name":"Nested Album"},"trans_param":{"union_cover":"http://imge.kugou.com/{size}/nested.jpg"}}],"total":1}}""",
        )
        val track = KugouTrackMapper.extractTracks(response).single()
        assertEquals("76201", track.id)
        assertEquals("Nested Song", track.title)
        assertEquals(listOf("Nested Artist"), track.artists)
        assertEquals("Nested Album", track.album)
        assertEquals(241_000L, track.durationMs)
        assertEquals("https://imge.kugou.com/400/nested.jpg", track.artworkUri)
        val source = track.source as PlaybackSource.Kugou
        assertEquals("NESTED_HASH", source.hash)
        assertEquals("3001", source.albumId)
    }

    @Test
    fun `public playlist name supplies artist and clean title when dedicated fields are absent`() {
        val track = checkNotNull(
            KugouTrackMapper.map(JSONObject("""{"hash":"H1","mixsongid":7,"name":"Taylor Swift - Fearless"}""")),
        )
        assertEquals("Fearless", track.title)
        assertEquals(listOf("Taylor Swift"), track.artists)
    }

    @Test
    fun `keeps a real upstream MV id but does not derive one from the audio hash`() {
        val withMv = checkNotNull(KugouTrackMapper.map(JSONObject("""{"hash":"AUDIO_HASH","songname":"Song","mv_id":"9001"}""")))
        val withoutMv = checkNotNull(KugouTrackMapper.map(JSONObject("""{"hash":"AUDIO_HASH","songname":"Song"}""")))

        assertEquals("9001", withMv.mvId)
        assertEquals(null, withoutMv.mvId)
        assertFalse(withMv.mvId!!.contains("AUDIO_HASH"))
    }
}
