package com.kutv.music.core.model

import com.kutv.music.bridge.mappers.BridgeTrackIds
import com.kutv.music.bridge.mappers.ContentJsonMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentContractTest {
    private val mapper = ContentJsonMapper

    @Test
    fun `paged result preserves cursor and stable ids`() {
        val page = PagedResult(
            items = listOf(ContentCard("playlist:42", "夜晚歌单", ContentKind.PLAYLIST)),
            nextCursor = "p2",
        )
        val json = mapper.page(page)
        assertEquals("playlist:42", json.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals("p2", json.getString("nextCursor"))
    }

    @Test
    fun `song card id is the registered opaque bridge id and resolves back`() {
        val nativeId = "76201"
        val json = mapper.card(ContentCard(nativeId, "若把你", ContentKind.SONG))
        val publicId = json.getString("id")
        assertTrue(publicId.startsWith("tv_"))
        assertEquals(nativeId, BridgeTrackIds.nativeId(publicId))
    }

    @Test
    fun `unknown search category is rejected`() {
        val rejected = runCatching { SearchCategory.valueOf("USER") }
        assertTrue(rejected.isFailure)
    }

    @Test
    fun `playlist detail exposes songs comments and introduction tabs`() {
        val detail = PlaylistDetail(
            id = "playlist:1",
            title = "夜晚歌单",
            tracks = PagedResult(emptyList()),
            introduction = "",
        )
        assertEquals(listOf("songs", "comments", "introduction"), detail.tabs)
    }

    @Test
    fun `artist detail keeps songs albums and mv tabs`() {
        val detail = ArtistDetail(
            id = "artist:9",
            title = "若把你",
            hotTracks = emptyList(),
        )
        assertEquals(listOf("songs", "albums", "mv"), detail.tabs)
    }

    @Test
    fun `content projections never emit sensitive fields`() {
        val candidates = listOf(
            mapper.card(ContentCard("song:1", "t", ContentKind.SONG, artworkUrl = "https://img.example/x.png")),
            mapper.page(PagedResult(listOf(ContentCard("playlist:2", "p", ContentKind.PLAYLIST)), nextCursor = "c")),
            mapper.detail(PlaylistDetail("playlist:1", "p", tracks = PagedResult(emptyList()), introduction = "")),
            mapper.comment(Comment("c1", UserProfile("u1", "nick"), "不错", 1_700_000_000L, 3)),
            mapper.librarySummary(LibrarySummary(emptyMap<LibrarySection, Int>(), emptyList<ContentCard>())),
            mapper.shareTarget(ShareTarget(ContentKind.SONG, "song:1")),
        )
        val forbidden = setOf("url", "streamUrl", "hash", "secret", "source", "token", "signature", "qrcode")
        candidates.forEach { json ->
            json.keys().asSequence().forEach { key ->
                assertFalse("key $key must not appear in $json", key in forbidden)
                val nested = json.optJSONObject(key) ?: json.optJSONArray(key) ?: return@forEach
                forbidden.forEach { name -> assertFalse(containsKey(nested, name)) }
            }
        }
    }

    @Test
    fun `library sections keep the approved order`() {
        assertEquals(
            listOf(
                "FAVORITE_SONGS",
                "FAVORITE_ARTISTS",
                "FAVORITE_USERS",
                "FAVORITE_ALBUMS",
                "FAVORITE_MVS",
                "HISTORY",
                "CLOUD",
                "PURCHASED_SONGS",
                "PURCHASED_ALBUMS",
                "CREATED_PLAYLISTS",
                "FAVORITE_PLAYLISTS",
            ),
            LibrarySection.entries.map(LibrarySection::name),
        )
    }

    private fun containsKey(value: Any, name: String): Boolean = when (value) {
        is JSONObject -> value.has(name)
        is org.json.JSONArray -> (0 until value.length()).any { index ->
            value.optJSONObject(index)?.let { containsKey(it, name) } == true
        }
        else -> false
    }
}
