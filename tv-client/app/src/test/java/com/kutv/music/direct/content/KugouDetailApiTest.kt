package com.kutv.music.direct.content

import com.kutv.music.core.model.CommentTarget
import com.kutv.music.core.model.CommentTargetKind
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouResponse
import com.kutv.music.direct.KugouSigner
import com.kutv.music.direct.KugouCredentials
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDetailApiTest {
    private val mid = "9876543210"

    private val emptyBody = JSONObject("""{"status":1,"error_code":0,"data":{}}""")

    private fun api(credentials: KugouCredentials? = null, capture: (KugouRequest) -> Unit): KugouDetailApi = KugouDetailApi(
        executor = KugouRequestExecutor { request ->
            capture(request)
            KugouResponse(statusCode = 200, body = emptyBody)
        },
        midProvider = { mid },
        credentialsProvider = { credentials },
    )

    private fun commentApi(capture: (KugouRequest) -> Unit): KugouCommentApi = KugouCommentApi(
        KugouRequestExecutor { request ->
            capture(request)
            KugouResponse(statusCode = 200, body = emptyBody)
        },
    )

    @Test
    fun `playlist meta posts get list info with global collection id`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.playlistMeta("9001")
        val r = checkNotNull(request)
        assertEquals("POST", r.method)
        assertEquals("https://gateway.kugou.com", r.baseUrl)
        assertEquals("/v3/get_list_info", r.path)
        assertEquals("pubsongs.kugou.com", r.headers["x-router"])
        assertEquals("9001", (r.body as JSONObject).getJSONArray("data").getJSONObject(0).getString("global_collection_id"))
        assertEquals(0L, (r.body as JSONObject).getLong("userid"))
        assertEquals("", (r.body as JSONObject).getString("token"))
        assertEquals(false, r.includeCredentials)
    }

    @Test
    fun `playlist meta includes the signed in account expected by the reference endpoint`() = runBlocking {
        var request: KugouRequest? = null
        api(KugouCredentials("42", "fixture-token")) { request = it }.playlistMeta("9001")
        val r = checkNotNull(request)
        val body = r.body as JSONObject
        assertEquals(42L, body.getLong("userid"))
        assertEquals("fixture-token", body.getString("token"))
        assertTrue(r.includeCredentials)
    }

    @Test
    fun `playlist tracks use the public collection endpoint with offset pagination`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.playlistTracks("9001", 3)
        val r = checkNotNull(request)
        assertEquals("GET", r.method)
        assertEquals("/pubsongs/v2/get_other_list_file_nofilt", r.path)
        assertEquals("9001", r.query["global_collection_id"])
        assertEquals(60, r.query["begin_idx"])
        assertEquals(30, r.query["pagesize"])
        assertEquals("abtags,hot_cmt,popularization", r.query["extend_fields"])
    }

    @Test
    fun `album meta posts kmr albums with fields and tid`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.albumMeta("3001")
        val r = checkNotNull(request)
        assertEquals("/kmr/v2/albums", r.path)
        assertEquals("openapi.kugou.com", r.headers["x-router"])
        assertEquals("255", r.headers["kg-tid"])
        val body = r.body as JSONObject
        assertEquals("3001", body.getJSONArray("data").getJSONObject(0).getString("album_id"))
        assertEquals(0, body.getInt("is_buy"))
        assertTrue(body.getString("fields").contains("sizable_cover"))
    }

    @Test
    fun `album tracks posts lite body`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.albumTracks("3001", 2)
        val r = checkNotNull(request)
        assertEquals("/v1/album_audio/lite", r.path)
        assertEquals("openapi.kugou.com", r.headers["x-router"])
        assertEquals("255", r.headers["kg-tid"])
        val body = r.body as JSONObject
        assertEquals("3001", body.getString("album_id"))
        assertEquals(0, body.getInt("is_buy"))
        assertEquals(2, body.getInt("page"))
        assertEquals(30, body.getInt("pagesize"))
    }

    @Test
    fun `artist meta posts author with tid 36`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.artistMeta("9")
        val r = checkNotNull(request)
        assertEquals("/kmr/v3/author", r.path)
        assertEquals("openapi.kugou.com", r.headers["x-router"])
        assertEquals("36", r.headers["kg-tid"])
        assertEquals("9", (r.body as JSONObject).getString("author_id"))
    }

    @Test
    fun `artist songs signs seconds on openapi base`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.artistSongs("9", 1)
        val r = checkNotNull(request)
        assertEquals("POST", r.method)
        assertEquals("https://openapi.kugou.com", r.baseUrl)
        assertEquals("/kmr/v1/audio_group/author", r.path)
        assertEquals("openapi.kugou.com", r.headers["x-router"])
        assertEquals("220", r.headers["kg-tid"])
        val body = r.body as JSONObject
        assertEquals(KugouSigner.APP_ID, body.getInt("appid"))
        assertEquals(KugouSigner.CLIENT_VERSION, body.getInt("clientver"))
        assertEquals(mid, body.getString("mid"))
        assertEquals("9", body.getString("author_id"))
        assertEquals(1, body.getInt("sort"))
        assertEquals("all", body.getString("area_code"))
        assertSecondsSigned(body)
    }

    @Test
    fun `artist albums posts sort and category`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.artistAlbums("9", 1)
        val r = checkNotNull(request)
        assertEquals("/kmr/v1/author/albums", r.path)
        assertEquals("openapi.kugou.com", r.headers["x-router"])
        assertEquals("36", r.headers["kg-tid"])
        val body = r.body as JSONObject
        assertEquals("9", body.getString("author_id"))
        assertEquals(1, body.getInt("sort"))
        assertEquals(1, body.getInt("category"))
        assertEquals("all", body.getString("area_code"))
    }

    @Test
    fun `artist videos get on openapicdn with empty tag`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.artistVideos("9", 2)
        val r = checkNotNull(request)
        assertEquals("GET", r.method)
        assertEquals("https://openapicdn.kugou.com", r.baseUrl)
        assertEquals("/kmr/v1/author/videos", r.path)
        assertEquals("9", r.query["author_id"])
        assertEquals("", r.query["is_fanmade"])
        assertEquals("", r.query["tag_idx"])
        assertEquals(2, r.query["page"] as Int)
        assertEquals(30, r.query["pagesize"] as Int)
    }

    @Test
    fun `song meta numeric id posts kmr audio with entity id`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.songMeta("76201")
        val r = checkNotNull(request)
        assertEquals("/kmr/v2/audio", r.path)
        assertEquals("openapi.kugou.com", r.headers["x-router"])
        assertEquals("238", r.headers["KG-TID"])
        val body = r.body as JSONObject
        assertEquals(76201L, body.getJSONArray("data").getJSONObject(0).getLong("entity_id"))
    }

    @Test
    fun `song meta hash id posts kmr audio with millis key`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.songMeta("f0e0f1e2d3c4b5a69788796a5b4c3d2e")
        val r = checkNotNull(request)
        assertEquals("https://kmr.service.kugou.com", r.baseUrl)
        assertEquals("/v1/audio/audio", r.path)
        assertEquals("kmr.service.kugou.com", r.headers["x-router"])
        assertEquals("application/json", r.headers["content-type"])
        val body = r.body as JSONObject
        assertEquals(KugouSigner.APP_ID, body.getInt("appid"))
        assertEquals(KugouSigner.CLIENT_VERSION, body.getInt("clientver"))
        assertEquals("-", body.getString("dfid"))
        assertEquals(mid, body.getString("mid"))
        assertEquals(
            "f0e0f1e2d3c4b5a69788796a5b4c3d2e",
            body.getJSONArray("data").getJSONObject(0).getString("hash"),
        )
        assertEquals(0, body.getJSONArray("data").getJSONObject(0).getInt("audio_id"))
        assertMillisSigned(body)
    }

    @Test
    fun `mv meta posts video with md5 mid uuid and seconds key`() = runBlocking {
        var request: KugouRequest? = null
        api { request = it }.mvMeta("5001")
        val r = checkNotNull(request)
        assertEquals("POST", r.method)
        assertEquals("/v1/video", r.path)
        assertEquals("kmr.service.kugou.com", r.headers["x-router"])
        val body = r.body as JSONObject
        assertEquals(KugouSigner.APP_ID, body.getInt("appid"))
        assertEquals(KugouSigner.CLIENT_VERSION, body.getInt("clientver"))
        assertEquals(mid, body.getString("mid"))
        assertEquals(KugouSigner.md5(mid), body.getString("uuid"))
        assertEquals("-", body.getString("dfid"))
        assertEquals(1, body.getInt("show_resolution"))
        assertEquals("5001", body.getJSONArray("data").getJSONObject(0).getString("video_id"))
        assertSecondsSigned(body)
    }

    @Test
    fun `song comments post query params to mcomment`() = runBlocking {
        var request: KugouRequest? = null
        commentApi { request = it }.comments(CommentTarget(CommentTargetKind.SONG, "76201"), 2)
        val r = checkNotNull(request)
        assertEquals("POST", r.method)
        assertEquals("/mcomment/v1/cmtlist", r.path)
        assertEquals("76201", r.query["mixsongid"])
        assertEquals(2, r.query["p"] as Int)
        assertEquals(1, r.query["need_show_image"] as Int)
        assertEquals(30, r.query["pagesize"] as Int)
        assertEquals("0", r.query["extdata"])
        assertEquals("fc4be23b4e972707f36b8a828a93ba8a", r.query["code"])
    }

    @Test
    fun `playlist comments post childrenid with tag and code`() = runBlocking {
        var request: KugouRequest? = null
        commentApi { request = it }.comments(CommentTarget(CommentTargetKind.PLAYLIST, "9001"), 1)
        val r = checkNotNull(request)
        assertEquals("/m.comment.service/v1/cmtlist", r.path)
        assertEquals("9001", r.query["childrenid"])
        assertEquals(0, r.query["content_type"] as Int)
        assertEquals(5, r.query["tag"] as Int)
        assertEquals("ca53b96fe5a1d9c22d71c8f522ef7c4f", r.query["code"])
    }

    @Test
    fun `album comments reuse playlist shape with album code`() = runBlocking {
        var request: KugouRequest? = null
        commentApi { request = it }.comments(CommentTarget(CommentTargetKind.ALBUM, "3001"), 1)
        val r = checkNotNull(request)
        assertEquals("/m.comment.service/v1/cmtlist", r.path)
        assertEquals("3001", r.query["childrenid"])
        assertEquals(0, r.query["content_type"] as Int)
        assertEquals(5, r.query["tag"] as Int)
        assertEquals("94f1792ced1df89aa68a7939eaf2efca", r.query["code"])
    }

    private fun assertSecondsSigned(body: JSONObject) {
        val clientTime = body.getLong("clienttime")
        assertTrue("expected seconds value, got $clientTime", clientTime in 1_000_000_000..10_000_000_000L)
        assertEquals(KugouSigner.signParamsKey(clientTime), body.getString("key"))
    }

    private fun assertMillisSigned(body: JSONObject) {
        val clientTime = body.getLong("clienttime")
        assertTrue("expected millis value, got $clientTime", clientTime in 1_000_000_000_000L..9_000_000_000_000L)
        assertEquals(KugouSigner.signParamsKey(clientTime), body.getString("key"))
    }
}
