package com.kutv.music.direct.content

import com.kutv.music.core.model.SearchCategory
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouResponse
import com.kutv.music.direct.KugouSigner
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KugouBrowseApiTest {
    private class RecordingExecutor(
        private val responder: (KugouRequest) -> KugouResponse,
    ) : KugouRequestExecutor {
        val requests = mutableListOf<KugouRequest>()
        override suspend fun execute(request: KugouRequest): KugouResponse {
            requests.add(request)
            return responder(request)
        }
    }

    private fun ok(body: String = """{"status":1,"error_code":0,"data":{}}""") =
        KugouResponse(200, JSONObject(body))

    @Test
    fun `search song hits v3 search song with keyword and complexsearch router`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.search("示例关键词", SearchCategory.SONG, 2)

        val request = executor.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://gateway.kugou.com", request.baseUrl)
        assertEquals("/v3/search/song", request.path)
        assertEquals("示例关键词", request.query["keyword"])
        assertEquals("2", request.query["page"].toString())
        assertEquals("30", request.query["pagesize"].toString())
        assertEquals("AndroidFilter", request.query["platform"])
        assertEquals("0", request.query["albumhide"].toString())
        assertEquals("1", request.query["iscorrection"].toString())
        assertEquals("0", request.query["nocollect"].toString())
        assertEquals("complexsearch.kugou.com", request.headers["x-router"])
    }

    @Test
    fun `search maps the six categories to their v3 or v1 paths`() = runBlocking {
        val expected = mapOf(
            SearchCategory.SONG to "/v3/search/song",
            SearchCategory.PLAYLIST to "/v1/search/special",
            SearchCategory.ALBUM to "/v1/search/album",
            SearchCategory.ARTIST to "/v1/search/author",
            SearchCategory.LYRIC to "/v1/search/lyric",
            SearchCategory.MV to "/v1/search/mv",
        )
        for ((category, path) in expected) {
            val executor = RecordingExecutor { ok() }
            KugouBrowseApi(executor) { MID }.search("q", category, 1)
            assertEquals(path, executor.requests.single().path)
            assertTrue(executor.requests.single().includeCredentials)
        }
    }

    @Test
    fun `hot search uses hot_tab endpoint and msearch router`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.hotSearch()

        val request = executor.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/v3/search/hot_tab", request.path)
        assertEquals("1", request.query["navid"].toString())
        assertEquals("2", request.query["plat"].toString())
        assertEquals("msearch.kugou.com", request.headers["x-router"])
        assertTrue(request.includeCredentials)
    }

    @Test
    fun `daily recommendations posts empty body with ios platform`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.dailyRecommendations()

        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/everyday_song_recommend", request.path)
        assertEquals("ios", request.query["platform"])
        assertEquals("everydayrec.service.kugou.com", request.headers["x-router"])
        assertEquals(null, request.body)
        assertTrue(request.includeCredentials)
        assertFalse(request.query.containsKey("token"))
        assertFalse(request.query.containsKey("userid"))
    }

    @Test
    fun `private fm signs key with the same millisecond clienttime`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.privateFm("play")

        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v2/personal_recommend", request.path)
        assertEquals("persnfm.service.kugou.com", request.headers["x-router"])

        val body = request.body as JSONObject
        assertEquals(MID, body.optString("mid"))
        assertEquals(KugouSigner.APP_ID, body.optInt("appid"))
        assertEquals(KugouSigner.CLIENT_VERSION, body.optInt("clientver"))
        assertEquals("play", body.optString("action"))
        assertEquals("ios", body.optString("platform"))
        assertEquals("normal", body.optString("mode"))
        assertEquals(0, body.optInt("recommend_source_locked"))
        assertEquals(0, body.optInt("song_pool_id"))
        assertEquals(0, body.optInt("callerid"))
        assertEquals(1, body.optInt("m_type"))
        assertEquals(1, body.optInt("area_code"))
        assertEquals(0, body.optInt("remain_songcnt"))
        assertEquals(0, body.optInt("is_overplay"))
        assertEquals("ca981cfc583a4c37f28d2d49000013c16a0a", body.optString("fakem"))

        val clienttime = body.optLong("clienttime")
        assertTrue(clienttime > 0)
        assertEquals(KugouSigner.signParamsKey(clienttime), body.optString("key"))
        assertFalse(body.has("hash"))
        assertFalse(body.has("songid"))
    }

    @Test
    fun `private fm discard attaches hash and songid`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.privateFm(
            action = "discard",
            hash = "00000000000000000000000000000000",
            songId = "7001",
        )

        val body = executor.requests.single().body as JSONObject
        assertEquals("discard", body.optString("action"))
        assertEquals("00000000000000000000000000000000", body.optString("hash"))
        assertEquals("7001", body.optString("songid"))
    }

    @Test
    fun `rankings lists ocean v6 rank list`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.rankings()

        val request = executor.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/ocean/v6/rank/list", request.path)
        assertEquals("2", request.query["plat"].toString())
        assertEquals("1", request.query["withsong"].toString())
        assertEquals("0", request.query["parentid"].toString())
    }

    @Test
    fun `ranking tracks posts kmr rank audio body with kg-tid header`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.rankingTracks("8888", 3)

        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/openapi/kmr/v2/rank/audio", request.path)
        assertEquals("369", request.headers["kg-tid"])

        val body = request.body as JSONObject
        assertEquals("8888", body.optString("rank_id"))
        assertEquals(3, body.optInt("page"))
        assertEquals(30, body.optInt("pagesize"))
        assertEquals(1, body.optInt("show_portrait_mv"))
        assertEquals(1, body.optInt("show_type_total"))
        assertEquals(1, body.optInt("filter_original_remarks"))
        assertEquals(1, body.optInt("area_code"))
        assertEquals(0, body.optInt("rank_cid"))
        assertEquals(1, body.optInt("type"))
    }

    @Test
    fun `new songs posts newsong publish with rank id and empty tags`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.newSongs(1)

        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/musicadservice/container/v1/newsong_publish", request.path)

        val body = request.body as JSONObject
        assertEquals(21608, body.optInt("rank_id"))
        assertEquals(0, body.optInt("userid"))
        assertEquals(1, body.optInt("page"))
        assertEquals(30, body.optInt("pagesize"))
        assertEquals("[]", body.optJSONArray("tags").toString())
    }

    @Test
    fun `new albums posts mobile_newalbum_sp body`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.newAlbums(2)

        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/musicadservice/v1/mobile_newalbum_sp", request.path)

        val body = request.body as JSONObject
        assertEquals(20, body.optInt("apiver"))
        assertEquals(2, body.optInt("page"))
        assertEquals(30, body.optInt("pagesize"))
        assertEquals(1, body.optInt("withpriv"))
    }

    @Test
    fun `new playlists signs key with the same second clienttime`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        KugouBrowseApi(executor) { MID }.newPlaylists(1)

        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v2/special_recommend", request.path)
        assertEquals("specialrec.service.kugou.com", request.headers["x-router"])

        val body = request.body as JSONObject
        assertEquals(MID, body.optString("mid"))
        assertEquals(KugouSigner.APP_ID, body.optInt("appid"))
        assertEquals(KugouSigner.CLIENT_VERSION, body.optInt("clientver"))
        assertEquals("android", body.optString("platform"))
        assertEquals(0, body.optInt("userid"))
        assertEquals(1, body.optInt("module_id"))
        assertEquals(1, body.optInt("page"))
        assertEquals(30, body.optInt("pagesize"))
        assertEquals(1, body.optInt("req_multi"))
        assertEquals(5, body.optInt("retrun_min"))
        assertEquals(1, body.optInt("return_special_falg"))

        val clienttime = body.optLong("clienttime")
        assertTrue(clienttime > 0)
        assertEquals(KugouSigner.signParamsKey(clienttime), body.optString("key"))

        val special = body.optJSONObject("special_recommend")
        assertNotNull(special)
        assertEquals(1, special.optInt("withtag"))
        assertEquals(1, special.optInt("withsong"))
        assertEquals(1, special.optInt("sort"))
        assertEquals(1, special.optInt("ugc"))
        assertEquals(0, special.optInt("is_selected"))
        assertEquals(1, special.optInt("withrecommend"))
        assertEquals(1, special.optInt("area_code"))
        assertEquals(0, special.optInt("categoryid"))
    }

    @Test
    fun `invalid inputs fail before any transport call`() = runBlocking {
        val executor = RecordingExecutor { ok() }
        val api = KugouBrowseApi(executor) { MID }

        val cases: List<suspend () -> Unit> = listOf(
            { api.search("", SearchCategory.SONG, 1) },
            { api.search("   ", SearchCategory.SONG, 1) },
            { api.search("q", SearchCategory.SONG, 0) },
            { api.search("q", SearchCategory.SONG, -1) },
            { api.privateFm("") },
            { api.rankingTracks("", 1) },
            { api.rankingTracks("x", 0) },
            { api.newSongs(0) },
            { api.newAlbums(-1) },
            { api.newPlaylists(0) },
        )
        for (case in cases) {
            try {
                case()
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(0, executor.requests.size)
    }

    private companion object {
        const val MID = "12345678901234567890"
    }
}
