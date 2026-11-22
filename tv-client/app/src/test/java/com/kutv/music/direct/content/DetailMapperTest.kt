package com.kutv.music.direct.content

import com.kutv.music.core.model.Comment
import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.PagedResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailMapperTest {
    @Test fun `numeric song metadata reads nested base without inventing a playback source`() {
        val detail = DetailMapper.song("76201", JSONObject("""{"data":[{"base":{"album_audio_id":76201,"songname":"Fixture title","album_name":"Fixture album","author_name":"Fixture artist"},"album_info":{"sizable_cover":"http://img.example.com/{size}.jpg"}}]}"""), JSONObject())
        assertEquals("Fixture title", detail.title)
        assertEquals("https://img.example.com/400.jpg", detail.artworkUrl)
        assertNull(detail.track)
    }

    @Test fun `song metadata supports base audio name alias`() {
        val detail = DetailMapper.song("76201", JSONObject("""{"data":[{"base":{"album_audio_id":76201,"audio_name":"Base title"}}]}"""), JSONObject())
        assertEquals("Base title", detail.title)
    }

    @Test fun `public root array cards use kind identity and page hint not the embedded audio identity`() {
        val body = JSONObject("""{"status":1,"total":65,"data":[
          {"video_id":5001,"album_audio_id":76201,"album_id":3001,"video_name":"Live MV","author_name":"Artist","hdpic":"http://imge.kugou.com/{size}/mv.jpg","cover":"400"},
          {"video_id":5002,"album_audio_id":76201,"album_id":3001,"video_name":"Official MV","author_name":"Artist"},
          {"video_id":5002,"album_audio_id":76201,"video_name":"Duplicate"}
        ]}""")
        val page = DetailMapper.cardPage(body, ContentKind.MV, "mv:", pageHint = 2)
        assertEquals(listOf("mv:5001", "mv:5002"), page.items.map { it.id })
        assertEquals("Live MV", page.items.first().title)
        assertEquals("Artist", page.items.first().subtitle)
        assertEquals("https://imge.kugou.com/400/mv.jpg", page.items.first().artworkUrl)
        assertEquals("3", page.nextCursor)
        assertEquals(65, page.total)
        assertNull(DetailMapper.cardPage(body, ContentKind.MV, "mv:", pageHint = 3).nextCursor)
    }

    @Test fun `public albums prefer album id and title over the author and song fields`() {
        val page = DetailMapper.cardPage(JSONObject("""{"total":49,"data":[{"album_id":3001,"album_audio_id":76201,"name":"Album title","author_name":"Artist","sizable_cover":"http://imge.kugou.com/{size}/album.jpg"}]}"""),
            ContentKind.ALBUM, "album:")
        assertEquals("album:3001", page.items.single().id)
        assertEquals("Album title", page.items.single().title)
        assertEquals("2", page.nextCursor)
    }

    @Test fun `video cards never substitute an unrelated song id`() {
        val page = DetailMapper.cardPage(JSONObject("""{"data":[{"album_audio_id":76201,"album_id":3001,"video_name":"No video identity"}]}"""), ContentKind.MV, "mv:")
        assertEquals(0, page.items.size)
    }

    private val mapper = DetailMapper

    @Test
    fun `playlist maps meta creator counts tags and fixed tabs`() {
        val detail = mapper.playlist(
            id = "playlist:9001",
            metaJson = KugouDetailFixtures.get("playlistMeta"),
            tracksJson = KugouDetailFixtures.get("playlistTracks"),
        )
        assertEquals("playlist:9001", detail.id)
        assertEquals("夜晚歌单", detail.title)
        assertEquals(listOf("songs", "comments", "introduction"), detail.tabs)
        assertEquals("造歌单的人", detail.creator?.nickname)
        assertEquals(123456L, detail.playCount)
        assertEquals(listOf("夜晚", "民谣", "安静"), detail.tags)
        assertEquals("夜晚专属歌单", detail.introduction)
        assertEquals(45, detail.trackCount)
        assertEquals(1, detail.tracks.items.size)
    }

    @Test
    fun `playlist maps the flexible cover used by public collection metadata`() {
        val meta = JSONObject("""{"data":[{"name":"Public list","flexible_cover":"http://img.example.com/{size}/public.jpg"}]}""")
        val detail = mapper.playlist("collection_fixture", meta, JSONObject())
        assertEquals("https://img.example.com/400/public.jpg", detail.artworkUrl)
    }

    @Test
    fun `artist maps name pic intro and fixed tabs`() {
        val detail = mapper.artist("artist:9", KugouDetailFixtures.get("artistMeta"))
        assertEquals("刘瑾睿", detail.title)
        assertEquals(listOf("songs", "albums", "mv"), detail.tabs)
        assertEquals("创作型歌手", detail.summary)
    }

    @Test
    fun `album maps title cover date artist intro`() {
        val detail = mapper.album("album:3001", KugouDetailFixtures.get("albumMeta"))
        assertEquals("山野", detail.title)
        assertEquals("某个乐队", detail.artist)
        assertEquals("2024-03-01", detail.publishDate)
        assertEquals("山野之间", detail.introduction)
    }

    @Test
    fun `song maps track from krm audio and related cards`() {
        val detail = mapper.song(
            id = "song:76201",
            metaJson = KugouDetailFixtures.get("songMetaKrm"),
            relatedPage = KugouDetailFixtures.get("relatedCards"),
        )
        assertEquals("若把你", detail.title)
        assertEquals("76201", detail.track?.id)
        assertEquals("刘瑾睿", detail.track?.artists?.first())
        assertEquals(1, detail.related.items.size)
        assertEquals(ContentKind.SONG, detail.related.items.first().kind)
    }

    @Test
    fun `mv maps fields and converts seconds to millis`() {
        val detail = mapper.mv(
            id = "mv:5001",
            metaJson = KugouDetailFixtures.get("mvMeta"),
            relatedPage = JSONObject("""{"status":1,"error_code":0,"data":{}}"""),
        )
        assertEquals("若把你 MV", detail.title)
        assertEquals("刘瑾睿", detail.artist)
        assertEquals(240_000L, detail.durationMs)
        assertEquals(9999L, detail.playCount)
        assertEquals("官方 MV", detail.introduction)
        assertEquals(0, detail.related.items.size)
    }

    @Test
    fun `track page computes total and next cursor`() {
        val page = mapper.trackPage(KugouDetailFixtures.get("playlistTracks"))
        assertEquals(1, page.items.size)
        assertEquals(45, page.total)
        assertEquals("2", page.nextCursor)
    }

    @Test
    fun `full public playlist page without a total still exposes the next cursor`() {
        val songs = org.json.JSONArray()
        repeat(30) { index ->
            songs.put(JSONObject().put("hash", "H$index").put("mixsongid", index + 1).put("name", "Song $index"))
        }
        val page = mapper.trackPage(JSONObject().put("data", JSONObject().put("songs", songs)), pageHint = 3)
        assertEquals(30, page.items.size)
        assertEquals("4", page.nextCursor)
        assertNull(page.total)
    }

    @Test
    fun `card page probes artist song container and prefixes id`() {
        val page = mapper.cardPage(KugouDetailFixtures.get("artistSongs"), ContentKind.SONG, "song:")
        val card: ContentCard = page.items.first()
        assertEquals("song:76201", card.id)
        assertEquals("若把你", card.title)
        assertEquals("刘瑾睿", card.subtitle)
        assertEquals(ContentKind.SONG, card.kind)
    }

    @Test
    fun `comment page maps entries seconds to ms and ignores hotword`() {
        val page = mapper.commentPage(KugouDetailFixtures.get("commentsSong"))
        assertEquals(1, page.items.size)
        assertEquals(88, page.total)
        val comment: Comment = page.items.first()
        assertEquals("c1", comment.id)
        assertEquals("歌迷甲", comment.user.nickname)
        assertEquals("太好听了", comment.content)
        assertEquals(1_700_000_000_000L, comment.timeEpochMs)
        assertEquals(12L, comment.likeCount)
        assertNull(comment.replies)
    }

    @Test
    fun `empty response yields empty pages fail closed`() {
        val empty = JSONObject("""{"status":1,"error_code":0,"data":{}}""")
        val tracks: PagedResult<*> = mapper.trackPage(empty)
        val cards: PagedResult<ContentCard> = mapper.cardPage(empty, ContentKind.SONG, "song:")
        val comments: PagedResult<Comment> = mapper.commentPage(empty)
        assertEquals(0, tracks.items.size)
        assertEquals(0, cards.items.size)
        assertEquals(0, comments.items.size)
        assertNull(tracks.nextCursor)
    }

    @Test
    fun `last page reports no next cursor`() {
        val json = JSONObject("""{"status":1,"error_code":0,"data":{"list":[],"total":30,"page":1,"pagesize":30}}""")
        assertNull(mapper.commentPage(json).nextCursor)
    }
}
