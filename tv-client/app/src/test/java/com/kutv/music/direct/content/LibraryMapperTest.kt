package com.kutv.music.direct.content

import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.LibrarySection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMapperTest {
    @Test fun `concept liked alias outranks earlier legacy default collection even with a default flag`() {
        // Redacted structural regression based on the observed two-list account;
        // identifiers and flags below are synthetic, not an exported account body.
        val raw = JSONObject("""{"info":[
            {"listid":1,"name":"默认收藏","is_def":1,"list_create_userid":42},
            {"listid":2,"name":"我喜欢","list_create_gid":"likedFixture","list_create_userid":42}
        ]}""")
        val identity = LibraryMapper.favoritePlaylistIdentity(raw)!!
        assertEquals("2", identity.listId)
        assertEquals("likedFixture", identity.collectionId)
    }

    @Test fun `missing creator and zero type cannot prove ownership in a signed in account`() {
        val raw = JSONObject("""{"data":{"info":[
            {"listid":1,"name":"Known owned","list_create_userid":" 42 "},
            {"listid":2,"name":"Missing creator","type":0},
            {"listid":3,"name":"Missing type and creator"}
        ]}}""")
        assertEquals(listOf("1"), LibraryMapper.createdPlaylists(raw, "42").map { it.id })
        assertEquals(listOf("2", "3"), LibraryMapper.favoritePlaylists(raw, "42").map { it.id })
        assertEquals(3, LibraryMapper.playlistCards(raw).size)
        assertEquals(2, LibraryMapper.playlistSyncInfo(raw, "42", 1).missingCreator)
    }

    @Test fun `unnamed playlist remains reachable without breaking the entire page`() {
        val raw = JSONObject("""{"data":{"info":[{"listid":1,"name":" "},{"listid":2,"name":"Named"}]}}""")
        val cards = LibraryMapper.playlistCards(raw)
        assertEquals(2, cards.size)
        assertEquals("未命名歌单", cards[0].title)
        assertEquals("playlist:library_1", cards[0].detailId)
    }

    @Test fun `missing container and missing identity have different safe diagnostic stages`() {
        val shape = runCatching { LibraryMapper.playlistCards(JSONObject("""{"data":{}}""")) }.exceptionOrNull()
        val row = runCatching { LibraryMapper.playlistCards(JSONObject("""{"info":[{"name":"Private title"}]}""")) }.exceptionOrNull()
        assertEquals(LibraryReadException.Stage.RESPONSE_SHAPE, (shape as LibraryReadException).stage)
        assertEquals(LibraryReadException.Stage.ROW_ID, (row as LibraryReadException).stage)
        assertTrue(!row.message.orEmpty().contains("Private title"))
    }

    private val fixtures = JSONObject(
        checkNotNull(javaClass.getResource("/kugou/library-fixtures.json")).readText(),
    )

    private fun fixture(key: String): JSONObject = fixtures.getJSONObject(key)

    @Test
    fun `playlist list splits created favorite and album cards`() {
        val raw = fixture("user_playlist")

        val created = LibraryMapper.createdPlaylists(raw)
        assertEquals(2, created.size)
        assertEquals(ContentKind.PLAYLIST, created[0].kind)
        assertEquals("100", created[0].id)

        val favorite = LibraryMapper.favoritePlaylists(raw)
        assertEquals(1, favorite.size)
        assertEquals("300", favorite[0].id)

        val albums = LibraryMapper.favoriteAlbums(raw)
        assertEquals(1, albums.size)
        assertEquals(ContentKind.ALBUM, albums[0].kind)
        assertEquals("4000", albums[0].id)
    }

    @Test
    fun `favorite playlist id resolves from default collection names`() {
        assertEquals("100", LibraryMapper.favoritePlaylistId(fixture("user_playlist")))
        assertNull(LibraryMapper.favoritePlaylistId(JSONObject("""{"data":{"list":[]}}""")))
    }

    @Test
    fun `track cards map favorite songs and history`() {
        val songs = LibraryMapper.trackCards(fixture("playlist_tracks"))
        assertEquals(2, songs.size)
        assertEquals(ContentKind.SONG, songs[0].kind)
        assertEquals("收藏歌曲一", songs[0].title)

        assertEquals("历史歌曲一", LibraryMapper.trackCards(fixture("history"))[0].title)
    }

    @Test
    fun `cloud items produce cloud prefixed ids and kv id map`() {
        val raw = fixture("cloud_list")
        val items = LibraryMapper.cloudItems(raw)

        assertEquals(2, items.size)
        assertEquals("cloud:901", items[0].id)
        assertEquals(ContentKind.SONG, items[0].kind)

        val kvIds = LibraryMapper.cloudKvIds(raw)
        assertEquals("1001", kvIds["cloud:901"])
        assertEquals("1002", kvIds["cloud:902"])
    }

    @Test
    fun `purchased songs and albums map to song and album cards`() {
        val songs = LibraryMapper.purchasedSongs(fixture("purchased_songs"))
        assertEquals(1, songs.size)
        assertEquals(ContentKind.SONG, songs[0].kind)
        assertEquals("已购歌曲一", songs[0].title)

        val albums = LibraryMapper.purchasedAlbums(fixture("purchased_albums"))
        assertEquals(1, albums.size)
        assertEquals(ContentKind.ALBUM, albums[0].kind)
        assertEquals("500", albums[0].id)
    }

    @Test
    fun `follow artists and collect videos map to artist and mv cards`() {
        val artists = LibraryMapper.followArtists(fixture("follow_artists"))
        assertEquals(2, artists.size)
        assertEquals(ContentKind.ARTIST, artists[0].kind)
        assertEquals("601", artists[0].id)

        val videos = LibraryMapper.collectVideos(fixture("collect_videos"))
        assertEquals(1, videos.size)
        assertEquals(ContentKind.MV, videos[0].kind)
        assertEquals("701", videos[0].id)
    }

    @Test
    fun `profile maps nickname avatar and summary fail closed`() {
        val profile = LibraryMapper.profile(fixture("profile"), "42")
        assertNotNull(profile)
        assertEquals("42", profile!!.id)
        assertEquals("测试用户", profile.nickname)

        assertNull(LibraryMapper.profile(JSONObject("""{"data":{}}"""), "42"))
    }

    @Test
    fun `section cards dispatch favorites users to empty and total is read`() {
        assertTrue(LibraryMapper.sectionCards(LibrarySection.FAVORITE_USERS, fixture("user_playlist")).isEmpty())
        assertEquals(4, LibraryMapper.total(fixture("user_playlist")))
    }
}
