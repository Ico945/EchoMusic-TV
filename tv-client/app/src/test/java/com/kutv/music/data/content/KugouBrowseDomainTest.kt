package com.kutv.music.data.content

import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ExploreSection
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.SearchCategory
import com.kutv.music.core.model.Track
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouResponse
import com.kutv.music.direct.content.KugouBrowseApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouBrowseDomainTest {
    @Test fun `playlist cards prefer the available static cover on the approved CDN`() = runBlocking {
        val result = domain({ request ->
            if (request.path == "/v2/special_recommend") KugouResponse(200, JSONObject("""{"data":{"special_list":[{"global_collection_id":"p1","name":"Playlist","pic":"https://imge.kugou.com/{size}/static.jpg","flexible_cover":"https://c1.kgimg.com/animated.jpg","imgurl":"https://c1.kgimg.com/animated.jpg"}]}}"""))
            else ok()
        }, mutableListOf()).explore().getOrThrow()
        assertEquals("https://imge.kugou.com/400/static.jpg", result.newPlaylists.single().artworkUrl)
    }
    @Test fun `artist groups are fetched and deduplicated instead of a placeholder shelf`() = runBlocking {
        val result = domain({ request ->
            if (request.path == "/ocean/v6/singer/list") {
                assertEquals(30, request.query["hotsize"])
                KugouResponse(200, JSONObject("""{"data":{"info":[{"title":"热门","singer":[{"singerid":12,"singername":"Artist","singerimg":"https://img.kugou.com/{size}/a.jpg"}]},{"title":"A","singer":[{"singerid":12,"singername":"Artist"}]}]}}"""))
            } else ok()
        }, mutableListOf()).explore().getOrThrow()
        assertEquals(listOf("artist:12"), result.artists.map { it.id })
        assertTrue(result.artists.single().artworkUrl.contains("/400/"))
    }
    private class RecordingExecutor(
        private val responder: (KugouRequest) -> KugouResponse,
    ) : KugouRequestExecutor {
        val requests = mutableListOf<KugouRequest>()
        override suspend fun execute(request: KugouRequest): KugouResponse {
            requests.add(request)
            return responder(request)
        }
    }

    private fun fixture(key: String): JSONObject {
        val text = checkNotNull(javaClass.getResource("/kugou/browse-fixtures.json")).readText()
        return JSONObject(text).getJSONObject(key)
    }

    private fun ok() = KugouResponse(200, JSONObject("""{"status":1,"error_code":0,"data":{}}"""))

    private fun domain(
        responder: (KugouRequest) -> KugouResponse,
        remembered: MutableList<List<Track>>,
        lookup: (String) -> Track? = { null },
    ): KugouBrowseDomain {
        val api = KugouBrowseApi(RecordingExecutor(responder)) { MID }
        return KugouBrowseDomain(
            api = api,
            rememberTracks = { tracks -> remembered.add(tracks); tracks },
            trackLookup = lookup,
        )
    }

    @Test
    fun `explore aggregates shelves and tolerates a failing shelf`() = runBlocking {
        val responder = { request: KugouRequest ->
            when (request.path) {
                "/musicadservice/container/v1/newsong_publish" -> KugouResponse(200, fixture("newsong_publish"))
                "/musicadservice/v1/mobile_newalbum_sp" -> KugouResponse(200, fixture("mobile_newalbum_sp"))
                "/v2/special_recommend" -> throw RuntimeException("shelf down")
                "/ocean/v6/rank/list" -> KugouResponse(200, fixture("rank_list"))
                else -> ok()
            }
        }
        val remembered = mutableListOf<List<Track>>()
        val explore = domain(responder, remembered).explore().getOrThrow()

        assertEquals(listOf("9101", "9102"), explore.newSongs.map { it.id })
        assertEquals(listOf("album:9201", "album:9202"), explore.newAlbums.map { it.id })
        assertTrue(explore.newPlaylists.isEmpty())
        assertEquals(setOf(ExploreSection.NEW_PLAYLISTS), explore.failedSections)
        assertEquals(listOf("8001", "8002"), explore.rankings.map { it.id })
        assertTrue(explore.artists.isEmpty())
        // New-song tracks are registered so their card ids stay playable.
        assertEquals(listOf(listOf("9101", "9102")), remembered.map { it.map(Track::id) })
    }

    @Test
    fun `failed explore sections remain distinguishable from successful empty data`() = runBlocking {
        val failed = domain({ throw IllegalStateException("offline fixture") }, mutableListOf()).explore().getOrThrow()
        assertEquals(ExploreSection.entries.toSet(), failed.failedSections)
        val empty = domain({ ok() }, mutableListOf()).explore().getOrThrow()
        assertTrue(empty.failedSections.isEmpty())
    }

    @Test
    fun `explore cancellation is propagated instead of becoming empty success`() = runBlocking {
        val outcome = runCatching {
            domain({ throw CancellationException("cancel fixture") }, mutableListOf()).explore()
        }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `slow explore section respects budget while healthy sections survive`() = runBlocking {
        val executor = object : KugouRequestExecutor {
            override suspend fun execute(request: KugouRequest): KugouResponse {
                if (request.path.endsWith("newsong_publish")) delay(20_000)
                return when (request.path) {
                    "/ocean/v6/rank/list" -> KugouResponse(200, fixture("rank_list"))
                    else -> ok()
                }
            }
        }
        val browse = KugouBrowseDomain(KugouBrowseApi(executor) { MID }, rememberTracks = { it }, trackLookup = { null })
        val start = System.nanoTime()
        val result = browse.explore().getOrThrow()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("A slow section must not block healthy shelves for 20 seconds", elapsedMs < 15_000)
        assertEquals(setOf(ExploreSection.NEW_SONGS), result.failedSections)
        assertEquals(2, result.rankings.size)
    }

    @Test
    fun `daily recommendations registers tracks`() = runBlocking {
        val remembered = mutableListOf<List<Track>>()
        val result = domain({ KugouResponse(200, fixture("everyday_recommend")) }, remembered)
            .dailyRecommendations()
            .getOrThrow()

        assertEquals(listOf("6001", "6002"), result.map { it.id })
        assertEquals(listOf(listOf("6001", "6002")), remembered.map { it.map(Track::id) })
    }

    @Test
    fun `private fm registers tracks`() = runBlocking {
        val remembered = mutableListOf<List<Track>>()
        val result = domain({ KugouResponse(200, fixture("personal_fm")) }, remembered)
            .privateFm()
            .getOrThrow()

        assertEquals(listOf("7001", "7002"), result.map { it.id })
        assertEquals(listOf(listOf("7001", "7002")), remembered.map { it.map(Track::id) })
    }

    @Test
    fun `discard private fm looks up hash and posts discard action`() = runBlocking {
        var action: String? = null
        var hash: String? = null
        var songId: String? = null
        val responder = { request: KugouRequest ->
            val body = request.body as JSONObject
            action = body.optString("action")
            hash = body.optString("hash")
            songId = body.optString("songid")
            KugouResponse(200, fixture("personal_fm"))
        }
        val track = Track(
            id = "7001",
            title = "t",
            artists = emptyList(),
            album = "",
            artworkUri = "",
            durationMs = 0,
            qualities = emptyList(),
            source = PlaybackSource.Kugou(trackId = "7001", hash = "00000000000000000000000000000004"),
        )
        val result = domain(responder, mutableListOf(), lookup = { if (it == "7001") track else null })
            .discardPrivateFm("7001")

        assertTrue(result.isSuccess)
        assertEquals("discard", action)
        assertEquals("00000000000000000000000000000004", hash)
        assertEquals("7001", songId)
    }

    @Test
    fun `discard private fm fails when track is unknown`() = runBlocking {
        val result = domain({ ok() }, mutableListOf(), lookup = { null }).discardPrivateFm("nope")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `hot search returns words`() = runBlocking {
        val result = domain({ KugouResponse(200, fixture("hot_tab")) }, mutableListOf())
            .hotSearch()
            .getOrThrow()
        assertEquals(listOf("热歌榜", "新歌首发"), result)
    }

    @Test
    fun `rankings returns summaries`() = runBlocking {
        val result = domain({ KugouResponse(200, fixture("rank_list")) }, mutableListOf())
            .rankings()
            .getOrThrow()
        assertEquals(listOf("8001", "8002"), result.map { it.id })
        assertEquals(listOf("热歌榜", "新歌榜"), result.map { it.title })
    }

    @Test
    fun `ranking parses cursor and returns paged tracks`() = runBlocking {
        var capturedPage: Int? = null
        val responder = { request: KugouRequest ->
            capturedPage = (request.body as JSONObject).optInt("page")
            KugouResponse(200, fixture("rank_audio"))
        }
        val remembered = mutableListOf<List<Track>>()
        val page = domain(responder, remembered).ranking("8888", "2").getOrThrow()

        assertEquals(2, capturedPage)
        assertEquals(listOf("8101", "8102"), page.items.map { it.id })
        assertEquals(45, page.total)
        assertEquals(listOf(listOf("8101", "8102")), remembered.map { it.map(Track::id) })
    }

    @Test
    fun `ranking rejects an invalid cursor`() = runBlocking {
        val result = domain({ ok() }, mutableListOf()).ranking("8888", "abc")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `search returns song cards and registers tracks`() = runBlocking {
        val remembered = mutableListOf<List<Track>>()
        val page = domain({ KugouResponse(200, fixture("search_song")) }, remembered)
            .search("示例关键词", SearchCategory.SONG, "1")
            .getOrThrow()

        assertEquals(listOf("1001", "1002"), page.items.map { it.id })
        assertEquals("无损", page.items[0].badge)
        assertEquals("VIP", page.items[1].badge)
        assertEquals(listOf(listOf("1001", "1002")), remembered.map { it.map(Track::id) })
    }

    @Test
    fun `lyric search maps to song cards`() = runBlocking {
        val page = domain({ KugouResponse(200, fixture("search_lyric")) }, mutableListOf())
            .search("歌词", SearchCategory.LYRIC, "1")
            .getOrThrow()
        assertEquals(listOf("1001"), page.items.map { it.id })
        assertEquals(ContentKind.SONG, page.items[0].kind)
    }

    @Test
    fun `search rejects an invalid cursor`() = runBlocking {
        val result = domain({ ok() }, mutableListOf()).search("q", SearchCategory.SONG, "0")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    private companion object {
        const val MID = "12345678901234567890"
    }
}
