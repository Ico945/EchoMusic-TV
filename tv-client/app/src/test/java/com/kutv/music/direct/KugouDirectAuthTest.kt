package com.kutv.music.direct

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDirectAuthTest {
    @Test
    fun `create qr uses official endpoint and URL`() = runBlocking {
        val executor = FakeExecutor(
            "/v2/qrcode" to """{"status":1,"data":{"qrcode":"QR-KEY-1"}}""",
        )
        val session = KugouDirectAuth(executor).createQr()

        assertEquals("QR-KEY-1", session.key)
        assertEquals(
            "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=QR-KEY-1",
            session.qrUrl,
        )
        val request = executor.requests.single()
        assertEquals("https://login-user.kugou.com", request.baseUrl)
        assertEquals(KugouSignMode.WEB, request.signMode)
        assertEquals(1001, request.query["appid"])
    }

    @Test
    fun `status four without token remains waiting`() = runBlocking {
        val executor = FakeExecutor(
            "/v2/get_userinfo_qrcode" to """{"status":1,"data":{"status":4,"userid":"42"}}""",
        )

        assertEquals(KugouQrState.Waiting, KugouDirectAuth(executor).pollQr("key"))
    }

    @Test
    fun `confirmed qr returns complete credentials`() = runBlocking {
        val executor = FakeExecutor(
            "/v2/get_userinfo_qrcode" to
                """{"status":1,"data":{"status":4,"userid":"42","token":"T-42","vip_type":1}}""",
        )

        val state = KugouDirectAuth(executor).pollQr("key")

        assertTrue(state is KugouQrState.Confirmed)
        assertEquals("T-42", (state as KugouQrState.Confirmed).credentials.token)
        assertEquals(KugouSigner.APP_ID, executor.requests.single().query["appid"])
    }

    @Test
    fun `account lookup signs explicit credentials without reading global session`() = runBlocking {
        var captured: KugouRawRequest? = null
        val client = KugouHttpClient(
            deviceProvider = { KugouDeviceIdentity("a".repeat(32), "b".repeat(32), "fixture-uuid") },
            credentialProvider = { error("Account lookup must not read global credentials") },
            transport = KugouTransport { request ->
                captured = request
                KugouRawResponse(200, """{"status":1,"data":{"nickname":"Fixture"}}""")
            },
            clockSeconds = { 1_700_000_000L },
        )
        val account = KugouDirectAuth(client, clockSeconds = { 1_700_000_000L })
            .getAccount(KugouCredentials("42", "fixture-token"))
        val query = java.net.URI(checkNotNull(captured).url).rawQuery.split("&").associate {
            val parts = it.split("=", limit = 2)
            java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("42", query["userid"])
        assertEquals("fixture-token", query["token"])
        assertTrue(query["signature"].orEmpty().isNotBlank())
        assertEquals(42L, JSONObject(checkNotNull(captured).body!!).getLong("userid"))
        assertEquals("Fixture", account.nickname)
    }

    @Test
    fun `account lookup uses privacy safe label when nickname is absent`() = runBlocking {
        val executor = FakeExecutor(
            "/v3/get_my_info" to """{"status":1,"data":{"nickname":""}}""",
        )

        val account = KugouDirectAuth(executor, clockSeconds = { 1_700_000_000L })
            .getAccount(KugouCredentials("408615481", "fixture-token"))

        assertEquals(KUGOU_PRIVATE_DISPLAY_NAME, account.nickname)
    }

    private class FakeExecutor(vararg pairs: Pair<String, String>) : KugouRequestExecutor {
        private val responses = pairs.toMap()
        val requests = mutableListOf<KugouRequest>()

        override suspend fun execute(request: KugouRequest): KugouResponse {
            requests += request
            return KugouResponse(200, JSONObject(checkNotNull(responses[request.path])))
        }
    }
}
