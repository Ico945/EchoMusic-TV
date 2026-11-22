package com.kutv.music.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRouterTest {
    @Test
    fun `routes an allowlisted method and correlates the request id`() = runBlocking {
        val router = BridgeRouter(
            mapOf("content.home" to BridgeHandler { JSONObject("""{"hero":{"id":"1"}}""") }),
        )

        val response = JSONObject(
            router.handle("""{"version":1,"type":"request","id":"r1","method":"content.home","payload":{}}"""),
        )

        assertEquals(1, response.getInt("version"))
        assertEquals("response", response.getString("type"))
        assertEquals("r1", response.getString("id"))
        assertTrue(response.getBoolean("ok"))
        assertEquals("1", response.getJSONObject("payload").getJSONObject("hero").getString("id"))
    }

    @Test
    fun `rejects a method outside the exact allowlist`() = runBlocking {
        val response = responseFor(
            BridgeRouter(emptyMap()),
            """{"version":1,"type":"request","id":"r2","method":"root.exec","payload":{}}""",
        )

        assertFalse(response.getBoolean("ok"))
        assertEquals("BRIDGE_METHOD_NOT_FOUND", response.errorCode())
        assertEquals("r2", response.getJSONObject("error").getString("requestId"))
    }

    @Test
    fun `accepts only version one request envelopes`() = runBlocking {
        val response = responseFor(
            BridgeRouter(emptyMap()),
            """{"version":2,"type":"request","id":"r3","method":"content.home","payload":{}}""",
        )

        assertEquals("BRIDGE_VERSION_UNSUPPORTED", response.errorCode())
    }

    @Test
    fun `accepts only request envelope type and object payload`() = runBlocking {
        val router = BridgeRouter(emptyMap())

        val wrongType = responseFor(
            router,
            """{"version":1,"type":"event","id":"r4","method":"content.home","payload":{}}""",
        )
        val wrongPayload = responseFor(
            router,
            """{"version":1,"type":"request","id":"r5","method":"content.home","payload":[]}""",
        )

        assertEquals("BRIDGE_PROTOCOL_INVALID", wrongType.errorCode())
        assertEquals("BRIDGE_PAYLOAD_INVALID", wrongPayload.errorCode())
    }

    @Test
    fun `measures the 256 KiB limit using UTF-8 bytes`() = runBlocking {
        val multibytePayload = "你".repeat(BridgeParser.MAX_MESSAGE_BYTES / 3)
        val raw = JSONObject()
            .put("version", 1)
            .put("type", "request")
            .put("id", "r6")
            .put("method", "content.home")
            .put("payload", JSONObject().put("value", multibytePayload))
            .toString()

        val response = responseFor(BridgeRouter(emptyMap()), raw)

        assertTrue(raw.length < BridgeParser.MAX_MESSAGE_BYTES)
        assertTrue(raw.toByteArray(Charsets.UTF_8).size > BridgeParser.MAX_MESSAGE_BYTES)
        assertEquals("BRIDGE_PAYLOAD_TOO_LARGE", response.errorCode())
    }

    @Test
    fun `rejects oversized malformed input before attempting request id extraction`() = runBlocking {
        var requestIdWasInspected = false
        val router = BridgeRouter(
            handlers = emptyMap(),
            requestIdExtractor = {
                requestIdWasInspected = true
                error("oversized input must not be parsed for an id")
            },
        )

        val response = responseFor(router, "{" + "x".repeat(BridgeParser.MAX_MESSAGE_BYTES))

        assertFalse(requestIdWasInspected)
        assertEquals("unknown", response.getString("id"))
        assertEquals("BRIDGE_PAYLOAD_TOO_LARGE", response.errorCode())
    }

    @Test
    fun `rejects sensitive input fields before dispatch`() = runBlocking {
        var invoked = false
        val router = BridgeRouter(
            mapOf("content.home" to BridgeHandler {
                invoked = true
                JSONObject()
            }),
        )

        val response = responseFor(
            router,
            """{"version":1,"type":"request","id":"r7","method":"content.home","payload":{"accessToken":"secret"}}""",
        )

        assertFalse(invoked)
        assertEquals("BRIDGE_SENSITIVE_FIELD", response.errorCode())
    }

    @Test
    fun `rejects sensitive output fields instead of leaking them to Vue`() = runBlocking {
        val router = BridgeRouter(
            mapOf("content.home" to BridgeHandler {
                JSONObject().put("source", JSONObject().put("playbackUrl", "https://example.invalid/audio"))
            }),
        )

        val response = responseFor(
            router,
            """{"version":1,"type":"request","id":"r8","method":"content.home","payload":{}}""",
        )

        assertFalse(response.toString().contains("example.invalid"))
        assertEquals("BRIDGE_SENSITIVE_FIELD", response.errorCode())
    }

    private suspend fun responseFor(router: BridgeRouter, raw: String): JSONObject =
        JSONObject(router.handle(raw))

    private fun JSONObject.errorCode(): String = getJSONObject("error").getString("code")
}
