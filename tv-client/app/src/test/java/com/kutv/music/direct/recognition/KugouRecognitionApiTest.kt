package com.kutv.music.direct.recognition

import com.kutv.music.direct.KugouRawRequestExecutor
import com.kutv.music.direct.KugouRawResponse
import com.kutv.music.direct.KugouRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouRecognitionApiTest {
    @Test
    fun `match posts octet stream pcm to the fingerprint endpoint`() = runBlocking {
        val executor = RecordingRawExecutor(KugouRawResponse(200, "{}", bodyBytes = "{}".toByteArray()))
        val api = DirectKugouRecognitionApi(executor, userIdProvider = { 42L }, clockMs = { 1_234L })
        val pcm = byteArrayOf(1, 2, 3, 4)

        api.match(pcm)

        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://gateway.kugou.com", request.baseUrl)
        assertEquals("/fingerprint.service/v1/music_trackid_mulit", request.path)
        assertEquals("1", request.query["area_code"].toString())
        assertEquals("1", request.query["include_unpublish"].toString())
        assertEquals("1", request.query["multi_result"].toString())
        assertEquals("42", request.query["useid"].toString())
        assertEquals("1234", request.query["fpid"].toString())
        assertEquals("application/octet-stream", request.headers["content-type"])
        assertTrue(request.body is ByteArray)
        assertTrue((request.body as ByteArray).contentEquals(pcm))
    }

    @Test
    fun `empty response fails closed to an empty list`() = runBlocking {
        val executor = RecordingRawExecutor(KugouRawResponse(200, "", bodyBytes = ByteArray(0)))
        val api = DirectKugouRecognitionApi(executor)

        assertTrue(api.match(byteArrayOf(1)).isEmpty())
    }

    @Test
    fun `parse failure fails closed to an empty list`() = runBlocking {
        val executor = RecordingRawExecutor(KugouRawResponse(200, "not-json", bodyBytes = "not-json".toByteArray()))
        val api = DirectKugouRecognitionApi(executor)

        assertTrue(api.match(byteArrayOf(1)).isEmpty())
    }

    @Test
    fun `fixture matches parse with normalized confidence`() = runBlocking {
        val fixture = RecognitionFixtures.get("match")
        val executor = RecordingRawExecutor(
            KugouRawResponse(200, fixture.toString(), bodyBytes = fixture.toString().toByteArray()),
        )
        val api = DirectKugouRecognitionApi(executor)

        val matches = api.match(byteArrayOf(1))

        assertEquals(2, matches.size)
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", matches[0].id)
        assertEquals("示例歌曲", matches[0].title)
        assertEquals("示例歌手", matches[0].artist)
        assertEquals(0.92f, matches[0].confidence, 0.001f)
    }

    private class RecordingRawExecutor(private val response: KugouRawResponse) : KugouRawRequestExecutor {
        val requests = mutableListOf<KugouRequest>()

        override suspend fun executeRaw(request: KugouRequest): KugouRawResponse {
            requests.add(request)
            return response
        }
    }
}

object RecognitionFixtures {
    private val root: JSONObject by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("kugou/recognition-fixtures.json")) {
            "missing kugou/recognition-fixtures.json on the unit-test classpath"
        }
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun get(name: String): JSONObject = root.optJSONObject(name) ?: JSONObject()
}
