package com.kutv.music.direct

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDirectApiLyricsTest {
    @Test
    fun `lyrics uses signed concept search and top level candidates`() = runBlocking {
        val requests = mutableListOf<KugouRequest>()
        val lrc = "[00:01.00]First line\n[00:03.00]Second line"
        val api = KugouDirectApi(
            executor = KugouRequestExecutor { request ->
                requests += request
                val body = when (request.path) {
                    "/v3/search/song" -> JSONObject(
                        """{"data":{"songs":[{"album_audio_id":"audio-1","hash":"ABCDEF","songname":"Song"}]}}""",
                    )
                    "/v1/search" -> JSONObject(
                        """{"status":1,"candidates":[{"id":"lyric-1","accesskey":"access-1"}]}""",
                    )
                    "/download" -> JSONObject()
                        .put("status", 1)
                        .put("contenttype", 1)
                        .put("content", Base64.getEncoder().encodeToString(lrc.toByteArray()))
                    else -> error("Unexpected path ${request.path}")
                }
                KugouResponse(200, body)
            },
            credentialProvider = { KugouCredentials(userId = "42", token = "token") },
        )

        val track = api.search("Song").single()
        val lines = api.lyrics(track.id)

        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        val searchRequest = requests.single { it.path == "/v1/search" }
        assertEquals(KugouSigner.APP_ID, searchRequest.query["appid"])
        assertEquals(KugouSigner.CLIENT_VERSION, searchRequest.query["clientver"])
        assertEquals(KugouSignMode.ANDROID, searchRequest.signMode)
        assertTrue(searchRequest.clearDefaultParams)
    }
}
