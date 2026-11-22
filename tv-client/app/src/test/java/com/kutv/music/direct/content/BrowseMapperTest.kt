package com.kutv.music.direct.content

import com.kutv.music.core.model.ContentKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseMapperTest {
    @Test fun `search album and author PascalCase records do not disappear during mapping`() {
        val albums = BrowseMapper.cardPage(JSONObject("""{"data":{"lists":[{"AlbumId":81,"AlbumName":"Album","SingerName":"Artist","Image":"http://imge.kugou.com/{size}/a.jpg"}]}}"""), ContentKind.ALBUM, "album:").items
        assertEquals("album:81", albums.single().id)
        assertEquals("Album", albums.single().title)
        assertEquals("Artist", albums.single().subtitle)
        assertEquals("https://imge.kugou.com/400/a.jpg", albums.single().artworkUrl)
        val artists = BrowseMapper.cardPage(JSONObject("""{"data":{"lists":[{"AuthorId":92,"AuthorName":"Artist","Avatar":"http://imge.kugou.com/{size}/b.jpg"}]}}"""), ContentKind.ARTIST, "artist:").items
        assertEquals("artist:92", artists.single().id)
        assertEquals("Artist", artists.single().title)
        assertEquals("https://imge.kugou.com/400/b.jpg", artists.single().artworkUrl)
    }

    @Test
    fun `live ranking schema preserves square cover and nested audio records across pages`() {
        val rankings = BrowseMapper.rankings(JSONObject("""{"data":{"info":[{"rankid":8888,"rankname":"Chart","imgurl":"http://imge.kugou.com/mcommon/{size}/cover.png","banner_9":"http://imge.kugou.com/wide.png"}]}}"""))
        assertEquals("https://imge.kugou.com/mcommon/400/cover.png", rankings.single().artworkUrl)
        val songs = JSONArray()
        repeat(30) { index ->
            songs.put(JSONObject().put("album_audio_id", index + 1).put("songname", "Song $index")
                .put("author_name", "Artist")
                .put("audio_info", JSONObject().put("hash_128", "%032x".format(index)).put("duration_128", 215000))
                .put("album_info", JSONObject().put("album_name", "Album").put("sizable_cover", "http://imge.kugou.com/{size}/song.jpg")))
        }
        val payload = JSONObject().put("status", 1).put("data", JSONObject().put("total", 90).put("songlist", songs))
        val page = BrowseMapper.trackPage(payload, requestedPage = 2)
        assertEquals(30, page.items.size)
        assertEquals("3", page.nextCursor)
        assertEquals(215000L, page.items.first().durationMs)
        assertEquals("https://imge.kugou.com/400/song.jpg", page.items.first().artworkUri)
        assertNull(BrowseMapper.trackPage(payload, requestedPage = 3).nextCursor)
    }

    @Test
    fun `new songs data array uses the same ids and covers as registered tracks`() {
        val payload = JSONObject("""{"status":1,"data":[{"album_audio_id":71,"hash":"00000000000000000000000000000071","songname":"Song","author_name":"Artist","trans_param":{"union_cover":"http://img.example/{size}/cover.jpg"}}]}""")
        val tracks = BrowseMapper.cardsFromTracks(payload)
        val cards = BrowseMapper.cardPage(payload, ContentKind.SONG, "").items
        assertEquals(1, cards.size)
        assertEquals(tracks.map { it.id }, cards.map { it.id })
        assertEquals(tracks[0].artworkUri, cards[0].artworkUrl)
        assertEquals("Artist", cards[0].subtitle)
        assertEquals("https://img.example/400/cover.jpg", cards[0].artworkUrl)
    }

    @Test
    fun `new albums flatten language groups and preserve Echo albumname and imgurl`() {
        val payload = JSONObject("""{"data":{"chn":[{"albumid":1,"albumname":"Chinese","imgurl":"http://img.example/{size}/a.jpg"}],"eur":[{"albumid":2,"albumname":"European"}],"jpn":[{"albumid":3,"albumname":"Japanese"}],"kor":[{"albumid":4,"albumname":"Korean"}]}}""")
        val cards = BrowseMapper.cardPage(payload, ContentKind.ALBUM, "album:").items
        assertEquals(listOf("album:1", "album:2", "album:3", "album:4"), cards.map { it.id })
        assertEquals(listOf("Chinese", "European", "Japanese", "Korean"), cards.map { it.title })
        assertEquals("https://img.example/400/a.jpg", cards[0].artworkUrl)
    }

    @Test
    fun `album groups accept Echo numeric aliases and nested lists without duplicating aliases`() {
        val payload = JSONObject("""{"data":{"chn":[{"albumid":1,"albumname":"One"}],"1":[{"albumid":9}],"2":{"list":[{"albumid":2}]},"3":{"info":[{"albumid":3}]},"4":{"data":[{"albumid":4}]}}}""")
        assertEquals(listOf("album:1", "album:2", "album:3", "album:4"), BrowseMapper.cardPage(payload, ContentKind.ALBUM, "album:").items.map { it.id })
    }

    @Test
    fun `recommended playlists read special list without discarding covers`() {
        val payload = JSONObject("""{"data":{"special_list":[{"specialid":71,"specialname":"Playlist","imgurl":"http://img.example/{size}/p.jpg"}]}}""")
        val cards = BrowseMapper.cardPage(payload, ContentKind.PLAYLIST, "playlist:").items
        assertEquals(1, cards.size)
        assertEquals("playlist:71", cards[0].id)
        assertEquals("Playlist", cards[0].title)
        assertEquals("https://img.example/400/p.jpg", cards[0].artworkUrl)
    }

    @Test
    fun `recommended playlists prefer the public global collection identity`() {
        val payload = JSONObject(
            """{"data":{"special_list":[{"specialid":71,"global_collection_id":"collection_fixture_71","specialname":"Playlist"}]}}""",
        )
        val card = BrowseMapper.cardPage(payload, ContentKind.PLAYLIST, "playlist:").items.single()
        assertEquals("playlist:collection_fixture_71", card.id)
    }

    private fun fixture(key: String): JSONObject {
        val text = checkNotNull(javaClass.getResource("/kugou/browse-fixtures.json")).readText()
        return JSONObject(text).getJSONObject(key)
    }

    @Test
    fun `cardsFromTracks extracts from a JSONObject response`() {
        val tracks = BrowseMapper.cardsFromTracks(fixture("everyday_recommend"))
        assertEquals(listOf("6001", "6002"), tracks.map { it.id })
    }

    @Test
    fun `cardsFromTracks extracts from a raw JSONArray`() {
        val array = JSONArray(
            """[
                {"album_audio_id":"1","hash":"00000000000000000000000000000000","songname":"One"},
                {"album_audio_id":"2","hash":"00000000000000000000000000000001","songname":"Two"}
            ]""",
        )
        assertEquals(listOf("1", "2"), BrowseMapper.cardsFromTracks(array).map { it.id })
    }

    @Test
    fun `trackPage probes supported containers and reads total`() {
        val page = BrowseMapper.trackPage(fixture("rank_audio"))
        assertEquals(listOf("8101", "8102"), page.items.map { it.id })
        assertEquals(45, page.total)
        // Fewer than a full page never advertises a next cursor.
        assertNull(page.nextCursor)
    }

    @Test
    fun `trackPage advertises next cursor only for a full page with more remaining`() {
        val data = JSONObject()
            .put("page", 2)
            .put("pagesize", 30)
            .put("total", 75)
        val list = JSONArray()
        repeat(30) { index ->
            list.put(
                JSONObject()
                    .put("album_audio_id", (index + 1).toString())
                    .put("hash", "%032x".format(index))
                    .put("songname", "Song$index"),
            )
        }
        data.put("list", list)

        val page = BrowseMapper.trackPage(JSONObject().put("data", data))
        assertEquals(30, page.items.size)
        assertEquals("3", page.nextCursor)
        assertEquals(75, page.total)
    }

    @Test
    fun `trackPage fails closed on missing containers`() {
        val page = BrowseMapper.trackPage(JSONObject("""{"status":1,"data":{}}"""))
        assertTrue(page.items.isEmpty())
        assertNull(page.total)
        assertNull(page.nextCursor)
    }

    @Test
    fun `cardPage maps song cards with lossless and vip badges`() {
        val page = BrowseMapper.cardPage(fixture("search_song"), ContentKind.SONG, "song:")
        assertEquals(listOf("song:1001", "song:1002"), page.items.map { it.id })
        assertEquals("示例歌曲A", page.items[0].title)
        assertEquals("歌手A", page.items[0].subtitle)
        assertEquals("无损", page.items[0].badge)
        assertEquals("VIP", page.items[1].badge)
        assertEquals("https://imge.kugou.com/400/a.jpg", page.items[0].artworkUrl)
    }

    @Test
    fun `cardPage maps playlist cards from specialid and listid`() {
        val page = BrowseMapper.cardPage(fixture("search_special"), ContentKind.PLAYLIST, "playlist:")
        assertEquals(listOf("playlist:2001", "playlist:2002"), page.items.map { it.id })
        assertEquals("示例歌单A", page.items[0].title)
        assertEquals("示例歌单B", page.items[1].title)
    }

    @Test
    fun `cardPage maps album cards`() {
        val page = BrowseMapper.cardPage(fixture("search_album"), ContentKind.ALBUM, "album:")
        assertEquals(listOf("album:3001", "album:3002"), page.items.map { it.id })
        assertEquals("示例专辑A", page.items[0].title)
        assertEquals("歌手A", page.items[0].subtitle)
    }

    @Test
    fun `cardPage maps artist cards from author_id and singer_id`() {
        val page = BrowseMapper.cardPage(fixture("search_author"), ContentKind.ARTIST, "artist:")
        assertEquals(listOf("artist:4001", "artist:4002"), page.items.map { it.id })
        assertEquals("示例歌手A", page.items[0].title)
        assertEquals("示例歌手B", page.items[1].title)
    }

    @Test
    fun `cardPage maps mv cards from video_id and mv_id`() {
        val page = BrowseMapper.cardPage(fixture("search_mv"), ContentKind.MV, "mv:")
        assertEquals(listOf("mv:5001", "mv:5002"), page.items.map { it.id })
        assertEquals("示例MV-A", page.items[0].title)
        assertEquals("歌手B", page.items[1].subtitle)
    }

    @Test
    fun `cardPage fails closed on unknown kind`() {
        val page = BrowseMapper.cardPage(fixture("search_song"), ContentKind.USER, "user:")
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `rankings maps rankid rankname and artwork`() {
        val rankings = BrowseMapper.rankings(fixture("rank_list"))
        assertEquals(listOf("8001", "8002"), rankings.map { it.id })
        assertEquals("热歌榜", rankings[0].title)
        assertEquals("新歌榜", rankings[1].title)
        assertEquals("https://imge.kugou.com/rank/8001.jpg", rankings[0].artworkUrl)
        assertEquals("https://imge.kugou.com/rank/8002.jpg", rankings[1].artworkUrl)
    }

    @Test
    fun `rankings fails closed when data is not a list`() {
        assertTrue(BrowseMapper.rankings(JSONObject("""{"status":1,"data":{"empty":true}}""")).isEmpty())
    }

    @Test
    fun `hotWords probes the hot_tab words structure`() {
        assertEquals(listOf("热歌榜", "新歌首发"), BrowseMapper.hotWords(fixture("hot_tab")))
    }

    @Test
    fun `hotWords reads Echo category list and keyword rows`() {
        val response = JSONObject("""{"status":1,"data":{"list":[{"name":"热搜","keywords":[{"keyword":"轻音乐"},{"keyword":"电影原声"}]}]}}""")
        assertEquals(listOf("轻音乐", "电影原声"), BrowseMapper.hotWords(response))
    }

    @Test
    fun `hotWords fails closed on missing structure`() {
        assertTrue(BrowseMapper.hotWords(JSONObject("""{"status":1,"data":{}}""")).isEmpty())
    }

    @Test
    fun `song card falls back to hash id when audio id is missing`() {
        val json = JSONObject(
            """{"status":1,"data":{"info":[{"hash":"00000000000000000000000000000000","songname":"OnlyHash"}]}}""",
        )
        val page = BrowseMapper.cardPage(json, ContentKind.SONG, "song:")
        assertEquals(listOf("song:00000000000000000000000000000000"), page.items.map { it.id })
    }
}
