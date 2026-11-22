package com.kutv.music.direct

import java.net.URI
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouHttpClientTest {
    @Test fun `daily recommendation request keeps Echo empty body and native credentials through signing`() = runBlocking {
        val transport = RecordingTransport(KugouRawResponse(200, """{"status":1,"error_code":0,"data":{"song_list":[]}}"""))
        val http = client(transport, credentials = KugouCredentials("42", "FIXTURE-TOKEN"))
        com.kutv.music.direct.content.KugouBrowseApi(http) { "fixture-mid" }.dailyRecommendations()
        val raw = transport.calls.single()
        assertEquals("POST", raw.method)
        assertEquals(null, raw.body)
        assertEquals(null, raw.bodyBytes)
        assertFalse(raw.headers.keys.any { it.equals("content-type", ignoreCase = true) })
        val query = URI(raw.url).rawQuery
        assertTrue(query.contains("userid=42"))
        assertTrue(query.contains("token=FIXTURE-TOKEN"))
        assertTrue(query.contains("signature="))
    }

    @Test fun `parent timeout remains cancellation while own deadline is typed timeout`() = runBlocking {
        fun slowClient(deadline: Long) = KugouHttpClient(
            { KugouDeviceIdentity("a".repeat(32), "b".repeat(32), "uuid", null) },
            { null }, KugouTransport { kotlinx.coroutines.awaitCancellation() }, timeoutMs = deadline,
        )
        val request = KugouRequest.get("https://gateway.kugou.com", "/slow")
        val parentError = runCatching {
            kotlinx.coroutines.withTimeout(50) { slowClient(5000).execute(request) }
        }.exceptionOrNull()
        assertTrue(parentError is kotlinx.coroutines.TimeoutCancellationException)
        val ownError = runCatching { slowClient(50).execute(request) }.exceptionOrNull()
        assertEquals(KugouNetworkErrorKind.TIMEOUT, (ownError as KugouNetworkException).kind)
    }

    @Test fun `socket timeout maps to timeout without exposing URL`() = runBlocking {
        val error = runCatching {
            client(KugouTransport { throw java.net.SocketTimeoutException("SECRET-URL") })
                .execute(KugouRequest.get("https://gateway.kugou.com", "/slow"))
        }.exceptionOrNull() as KugouNetworkException
        assertEquals(KugouNetworkErrorKind.TIMEOUT, error.kind)
        assertFalse(error.toString().contains("SECRET-URL"))
    }

    @Test
    fun `rejects non Kugou hosts before transport`() = runBlocking {
        val transport = RecordingTransport()
        val client = client(transport)

        val error = runCatching {
            client.execute(KugouRequest.get("https://evil.example", "/token"))
        }.exceptionOrNull()

        assertTrue(error is KugouNetworkException)
        assertEquals(KugouNetworkErrorKind.NETWORK, (error as KugouNetworkException).kind)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `adds device defaults signs sorted query and does not leak credentials into error`() = runBlocking {
        val transport = RecordingTransport(
            response = KugouRawResponse(200, """{"status":1,"data":{"ok":true}}"""),
        )
        val client = client(transport, credentials = KugouCredentials("42", "SECRET-TOKEN"))

        val response = client.execute(
            KugouRequest.get(
                "https://gateway.kugou.com",
                "/endpoint",
                linkedMapOf("z" to "last", "a" to "first"),
                includeCredentials = true,
            ),
        )

        assertTrue(response.body.getJSONObject("data").getBoolean("ok"))
        val uri = URI(transport.calls.single().url)
        assertTrue(uri.rawQuery.contains("appid=3116"))
        assertTrue(uri.rawQuery.contains("userid=42"))
        assertTrue(uri.rawQuery.contains("token=SECRET-TOKEN"))
        assertTrue(uri.rawQuery.contains("signature="))
        assertFalse(transport.calls.single().headers.values.any { it.contains("evil") })
    }

    @Test
    fun `maps HTTP and session expiry to typed redacted errors`() = runBlocking {
        val expired = client(
            RecordingTransport(KugouRawResponse(502, """{"error_code":20018,"token":"LEAK"}""")),
        )
        val sessionError = runCatching {
            expired.execute(KugouRequest.get("https://gateway.kugou.com", "/expired"))
        }.exceptionOrNull() as KugouNetworkException
        assertEquals(KugouNetworkErrorKind.SESSION_EXPIRED, sessionError.kind)
        assertFalse(sessionError.message.orEmpty().contains("LEAK"))

        val http = client(RecordingTransport(KugouRawResponse(503, "unavailable")))
        val httpError = runCatching {
            http.execute(KugouRequest.get("https://gateway.kugou.com", "/failed"))
        }.exceptionOrNull() as KugouNetworkException
        assertEquals(KugouNetworkErrorKind.HTTP, httpError.kind)
        assertEquals(503, httpError.statusCode)
    }

    @Test
    fun `maps nonzero upstream business code to data error`() = runBlocking {
        val client = client(
            RecordingTransport(KugouRawResponse(200, """{"status":1,"error_code":152}""")),
        )

        val error = runCatching {
            client.execute(KugouRequest.get("https://gateway.kugou.com", "/invalid-parameters"))
        }.exceptionOrNull() as KugouNetworkException

        assertEquals(KugouNetworkErrorKind.DATA, error.kind)
        assertEquals(200, error.statusCode)
    }

    @Test
    fun `search 152 requires login without leaking upstream error details`() = runBlocking {
        val client = client(RecordingTransport(KugouRawResponse(200, """{"status":1,"error_code":152,"error_msg":"LEAK"}""")))
        val error = runCatching {
            client.execute(KugouRequest(method = "GET", baseUrl = "https://gateway.kugou.com", path = "/v3/search/song", headers = mapOf("x-router" to "complexsearch.kugou.com")))
        }.exceptionOrNull() as KugouNetworkException
        assertEquals(KugouNetworkErrorKind.SESSION_EXPIRED, error.kind)
        assertFalse(error.message.orEmpty().contains("LEAK"))
    }

    @Test
    fun `redirect to unapproved host is rejected`() = runBlocking {
        val transport = RecordingTransport(
            KugouRawResponse(302, "", mapOf("Location" to listOf("https://evil.example/steal"))),
        )
        val error = runCatching {
            client(transport).execute(KugouRequest.get("https://gateway.kugou.com", "/redirect"))
        }.exceptionOrNull()

        assertTrue(error is KugouNetworkException)
        assertEquals(KugouNetworkErrorKind.NETWORK, (error as KugouNetworkException).kind)
    }

    @Test
    fun `query values are percent encoded exactly once`() = runBlocking {
        val transport = RecordingTransport()
        client(transport).execute(
            KugouRequest.get(
                "https://login-user.kugou.com",
                "/v2/qrcode",
                mapOf("qrcode_txt" to "https://h5.kugou.com/path?a=1&"),
                signMode = KugouSignMode.WEB,
            ),
        )

        val rawQuery = URI(transport.calls.single().url).rawQuery
        assertTrue(rawQuery.contains("qrcode_txt=https%3A%2F%2Fh5.kugou.com%2Fpath%3Fa%3D1%26"))
        assertFalse(rawQuery.contains("%253A"))
    }

    @Test
    fun `binary request bodies pass through as raw bytes with octet stream content type`() = runBlocking {
        val transport = RecordingTransport()
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x00, 0x7F, 0x00.toByte(), 0x55.toByte())
        client(transport).executeRaw(
            KugouRequest(
                method = "POST",
                baseUrl = "https://gateway.kugou.com",
                path = "/fingerprint.service/v1/music_trackid_mulit",
                body = pcm,
                signMode = KugouSignMode.ANDROID,
            ),
        )

        val sent = transport.calls.single()
        assertEquals(pcm.toList(), sent.bodyBytes?.toList())
        assertEquals(null, sent.body)
        assertEquals("application/octet-stream", sent.headers["content-type"])
        assertTrue(URI(sent.url).rawQuery.contains("signature="))
    }

    private fun client(
        transport: KugouTransport,
        credentials: KugouCredentials? = null,
    ) = KugouHttpClient(
        deviceProvider = {
            KugouDeviceIdentity("a".repeat(32), "b".repeat(32), "uuid", "DFID")
        },
        credentialProvider = { credentials },
        transport = transport,
        clockSeconds = { 12345L },
    )

    private class RecordingTransport(
        private val response: KugouRawResponse = KugouRawResponse(200, JSONObject().put("status", 1).toString()),
    ) : KugouTransport {
        val calls = mutableListOf<KugouRawRequest>()

        override suspend fun execute(request: KugouRawRequest): KugouRawResponse {
            calls += request
            return response
        }
    }
}
