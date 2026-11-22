package com.kutv.music.direct.content

import com.kutv.music.data.DirectLoginRequiredException
import com.kutv.music.direct.KugouCredentials
import com.kutv.music.direct.KugouDeviceIdentity
import com.kutv.music.direct.KugouRawRequestExecutor
import com.kutv.music.direct.KugouRawResponse
import com.kutv.music.direct.KugouCryptoExtensions
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouResponse
import com.kutv.music.direct.KugouSigner
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouLibraryApiTest {
    @Test fun `song mutations require a positive upstream acknowledgement`() = runBlocking {
        for (body in listOf("{}", """{"status":0,"error_code":0}""", """{"status":1,"errcode":9}""")) {
            val executor = RecordingExecutor().apply { next = { KugouResponse(200, JSONObject(body)) } }
            val api = api(executor, RecordingRawExecutor())
            assertTrue(runCatching { api.playlistTracksAdd("100", "song", "hash", "1", "111") }.isFailure)
            assertTrue(runCatching { api.playlistTracksDel("100", "9876") }.isFailure)
        }
    }

    @Test fun `invalid delete id fails before sending any mutation`() = runBlocking {
        val executor = RecordingExecutor()
        val api = api(executor, RecordingRawExecutor())
        for (id in listOf("0", "-1", "tv_opaque", "hash")) assertTrue(runCatching { api.playlistTracksDel("100", id) }.isFailure)
        assertTrue(executor.requests.isEmpty())
    }
    @Test fun `unsuccessful list status is not a successful empty inventory`() = runBlocking {
        val executor = RecordingExecutor().apply { next = { KugouResponse(200, JSONObject("""{"status":0,"info":[]}""")) } }
        val error = runCatching { api(executor, RecordingRawExecutor()).userPlaylists(null, 1, 30) }.exceptionOrNull()
        assertEquals(LibraryReadException.Stage.UPSTREAM_STATUS, (error as LibraryReadException).stage)
    }

    @Test fun `collection reads preserve gid and match Echo pagination parameters`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).playlistCollectionTracks("gidXYZ", 3, 30)
        val request = executor.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/pubsongs/v2/get_other_list_file_nofilt", request.path)
        assertEquals("gidXYZ", request.query["global_collection_id"])
        assertEquals(60, request.query["begin_idx"])
        assertEquals(1, request.query["personal_switch"])
        assertTrue(request.includeCredentials)
        assertFalse(request.query.containsKey("listid"))
    }
    private val device = KugouDeviceIdentity(
        guid = "a".repeat(32),
        mid = "b".repeat(32),
        uuid = "uuid",
        dfid = "dfid",
    )
    private val credentials = KugouCredentials(userId = "42", token = "synthetic-token")

    private class RecordingExecutor : KugouRequestExecutor {
        val requests = mutableListOf<KugouRequest>()
        var next: (KugouRequest) -> KugouResponse = {
            KugouResponse(200, JSONObject("""{"status":1,"error_code":0,"data":{}}"""))
        }

        override suspend fun execute(request: KugouRequest): KugouResponse {
            requests += request
            return next(request)
        }
    }

    private class RecordingRawExecutor : KugouRawRequestExecutor {
        val requests = mutableListOf<KugouRequest>()
        var next: (KugouRequest) -> KugouRawResponse = {
            KugouRawResponse(200, """{"status":1,"error_code":0,"data":{}}""", bodyBytes = """{"status":1,"error_code":0,"data":{}}""".toByteArray())
        }

        override suspend fun executeRaw(request: KugouRequest): KugouRawResponse {
            requests += request
            return next(request)
        }
    }

    private fun api(
        executor: RecordingExecutor,
        rawExecutor: RecordingRawExecutor,
        credentialsProvider: () -> KugouCredentials? = { credentials },
    ) = KugouLibraryApi(
        executor = executor,
        rawExecutor = rawExecutor,
        credentialsProvider = credentialsProvider,
        deviceProvider = { device },
        clockSeconds = { 1_700_000_000L },
    )

    private fun bodyOf(request: KugouRequest): JSONObject = request.body as JSONObject

    // ---- 端点形状 ----

    @Test
    fun `history posts the playhistory endpoint with required body`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).history()

        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/playhistory/v1/get_songs", request.path)
        assertEquals("synthetic-token", bodyOf(request).optString("token"))
        assertEquals(42L, bodyOf(request).optLong("userid"))
        assertEquals("app", bodyOf(request).optString("source_classify"))
        assertEquals(1, bodyOf(request).optInt("to_subdivide_sr"))
    }

    @Test
    fun `user playlists posts get_all_list with cloudlist router`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).userPlaylists(42L, 1, 30)

        val request = executor.requests.single()
        assertEquals("/v7/get_all_list", request.path)
        assertEquals("cloudlist.service.kugou.com", request.headers["x-router"])
        assertEquals(979, bodyOf(request).optInt("total_ver"))
        assertEquals(2, bodyOf(request).optInt("type"))
        assertEquals(1, request.query["plat"])
    }

    @Test
    fun `purchased songs posts copyright audio get_goods`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).purchasedSongs(1, 50)

        val request = executor.requests.single()
        assertEquals("/openapi/copyright/v1/audio/get_goods", request.path)
        assertEquals("1", bodyOf(request).optString("area_code"))
        assertEquals(1, bodyOf(request).optInt("need_audio_info"))
        assertEquals(0, bodyOf(request).optInt("deleted"))
    }

    @Test
    fun `purchased albums posts copyright get_album_goods`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).purchasedAlbums(1, 15)

        val request = executor.requests.single()
        assertEquals("/openapi/v1/copyright/get_album_goods", request.path)
        assertEquals(15, bodyOf(request).optInt("pagesize"))
    }

    @Test
    fun `profile posts get_my_info with raw rsa p and usercenter router`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).profile(42L)

        val request = executor.requests.single()
        assertEquals("/v3/get_my_info", request.path)
        assertEquals("usercenter.kugou.com", request.headers["x-router"])
        assertEquals(1_700_000_000L, bodyOf(request).optLong("visit_time"))
        assertEquals(1, bodyOf(request).optInt("usertype"))
        val p = bodyOf(request).optString("p")
        assertTrue(p.isNotBlank())
        assertEquals(256, p.length)
    }

    @Test
    fun `follow artists posts follow_list with relationuser router`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).followArtists()

        val request = executor.requests.single()
        assertEquals("/v4/follow_list", request.path)
        assertEquals("relationuser.kugou.com", request.headers["x-router"])
        assertEquals(2, bodyOf(request).optInt("merge"))
        assertTrue(bodyOf(request).optString("p").isNotBlank())
    }

    @Test
    fun `collect videos posts collect_list_mixvideo with plat query`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).collectVideos(1, 30)

        val request = executor.requests.single()
        assertEquals("/collectservice/v2/collect_list_mixvideo", request.path)
        assertEquals(1, request.query["plat"])
    }

    @Test
    fun `playlist tracks add posts add_song with gztx area and track fields`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).playlistTracksAdd(
            listId = "100",
            name = "收藏歌曲一",
            hash = "hash111",
            albumId = "11",
            mixsongId = "111",
        )

        val request = executor.requests.single()
        assertEquals("/cloudlist.service/v6/add_song", request.path)
        assertEquals("gztx", request.query["last_area"])
        assertEquals("100", bodyOf(request).optString("listid"))
        val data = bodyOf(request).getJSONArray("data").getJSONObject(0)
        assertEquals("hash111", data.optString("hash"))
        assertEquals(11, data.optInt("album_id"))
        assertEquals(111, data.optInt("mixsongid"))
    }

    @Test
    fun `playlist tracks del posts delete_songs with cloudlist router`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).playlistTracksDel(listId = "100", fileId = "111")

        val request = executor.requests.single()
        assertEquals("/v4/delete_songs", request.path)
        assertEquals("cloudlist.service.kugou.com", request.headers["x-router"])
        assertEquals(111, bodyOf(request).getJSONArray("data").getJSONObject(0).optInt("fileid"))
    }

    @Test
    fun `add playlist posts add_list with source and list create id`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).addPlaylist(
            listCreateListId = "999",
            type = 1,
            source = 2,
            listCreateUserId = null,
        )

        val request = executor.requests.single()
        assertEquals("/cloudlist.service/v5/add_list", request.path)
        assertEquals(1, bodyOf(request).optInt("type"))
        assertEquals(2, bodyOf(request).optInt("source"))
        assertEquals("999", bodyOf(request).optString("list_create_listid"))
    }

    @Test
    fun `artist follow posts follow_singer with aes params and rsa p`() = runBlocking {
        val executor = RecordingExecutor()
        api(executor, RecordingRawExecutor()).artistFollow("601")

        val request = executor.requests.single()
        assertEquals("/followservice/v3/follow_singer", request.path)
        assertEquals(0, bodyOf(request).optInt("plat"))
        assertEquals(601L, bodyOf(request).optLong("singerid"))
        assertEquals(7, bodyOf(request).optInt("source"))
        assertTrue(bodyOf(request).optString("p").isNotBlank())
        assertTrue(bodyOf(request).optString("params").matches(Regex("[0-9a-f]+")))
    }

    // ---- 云盘加密 ----

    @Test
    fun `cloud list sends encrypted body with consistent clienttime and key`() = runBlocking {
        val rawExecutor = RecordingRawExecutor()
        api(RecordingExecutor(), rawExecutor).cloudList(1, 30)

        val request = rawExecutor.requests.single()
        assertEquals("https://mcloudservice.kugou.com", request.baseUrl)
        assertEquals("/v1/get_list", request.path)

        val clientTime = request.query["clienttime"] as Long
        assertEquals(1_700_000_000L, clientTime)
        assertEquals(
            KugouSigner.signParamsKey(clientTime.toString()),
            request.query["key"],
        )

        val p = request.query["p"] as String
        assertTrue(p.isNotBlank())
        assertEquals(256, p.length)
        assertTrue(p == p.uppercase())

        val body = request.body as String
        assertNotNull(Base64.getDecoder().decode(body))
    }

    @Test
    fun `cloud delete posts del_files with encrypted body`() = runBlocking {
        val rawExecutor = RecordingRawExecutor()
        api(RecordingExecutor(), rawExecutor).cloudDelete(kvId = "1001", albumAudioId = "901")

        val request = rawExecutor.requests.single()
        assertEquals("/v1/del_files", request.path)
        assertEquals(1_700_000_000L, request.query["clienttime"])
        assertTrue((request.query["p"] as String).isNotBlank())
        val body = request.body as String
        assertNotNull(Base64.getDecoder().decode(body))
    }

    @Test
    fun `crypto aes encrypt matches reference golden vector`() {
        val encrypted = KugouCryptoExtensions.cryptoAesEncrypt(
            JSONObject().put("singerid", 123).put("token", "token"),
            optKey = "0123456789abcdef", // gitleaks:allow — synthetic AES test vector.
        )

        assertEquals("0123456789abcdef", encrypted.key)
        assertEquals(
            "247e5d83ee230b417c1dbe2e1b9cbf859c7b3c1bd530b9820763e9b3a1e51d92ef955c79ca1e0c695ecb544f4e66c1d9",
            encrypted.str,
        )
        assertEquals(123, (KugouCryptoExtensions.cryptoAesDecrypt(encrypted.str, encrypted.key) as JSONObject).getInt("singerid"))
    }

    // ---- 无凭据 ----

    @Test
    fun `login required endpoints fail with DirectLoginRequiredException`() = runBlocking {
        val executor = RecordingExecutor()
        val rawExecutor = RecordingRawExecutor()
        val anonymous = api(executor, rawExecutor, credentialsProvider = { null })

        val cases = listOf(
            runCatching { anonymous.history() },
            runCatching { anonymous.userPlaylists(42L, 1, 30) },
            runCatching { anonymous.playlistTracks("100", 1, 30) },
            runCatching { anonymous.cloudList(1, 30) },
            runCatching { anonymous.cloudDelete("1", "1") },
            runCatching { anonymous.purchasedSongs(1, 50) },
            runCatching { anonymous.profile(42L) },
            runCatching { anonymous.followArtists() },
            runCatching { anonymous.collectVideos(1, 30) },
            runCatching { anonymous.playlistTracksAdd("100", "n", "h", "1", "1") },
            runCatching { anonymous.playlistTracksDel("100", "1") },
            runCatching { anonymous.addPlaylist("999", 0, 1) },
            runCatching { anonymous.deletePlaylist("100") },
            runCatching { anonymous.artistFollow("601") },
        )

        assertTrue(cases.isNotEmpty())
        cases.forEach { result ->
            assertFalse("expected failure for anonymous endpoint", result.isSuccess)
            assertTrue(result.exceptionOrNull() is DirectLoginRequiredException)
        }
    }
}
