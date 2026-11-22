package com.kutv.music.bridge

import kotlinx.coroutines.runBlocking
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kutv.music.application.playback.PlayerCommandError
import com.kutv.music.bridge.mappers.PlayerJsonMapper

class BridgeFixtureParityTest {
    private val fixtures: JSONObject by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/tv-bridge/v1-fixtures.json")) {
            "Shared Bridge v1 fixture is not on the Kotlin test classpath"
        }
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun `loads the current Echo worktree fixture into the test classpath`() {
        val sourcePath = checkNotNull(System.getProperty("echo.bridge.fixture")) {
            "Gradle did not provide the shared Bridge fixture path"
        }
        val classpathFixture = checkNotNull(javaClass.getResourceAsStream("/tv-bridge/v1-fixtures.json"))
            .bufferedReader()
            .use { it.readText().trim() }

        assertEquals(File(sourcePath).readText().trim(), classpathFixture)
    }

    @Test
    fun `parses the shared valid request fixture`() {
        val request = BridgeParser.parseRequest(fixtures.getJSONObject("validRequest").toString())

        assertEquals(1, request.version)
        assertEquals("request", request.type)
        assertEquals("request-1", request.id)
        assertEquals("app.getSnapshot", request.method)
        assertEquals(0, request.payload.length())
    }

    @Test
    fun `emits the shared valid response fixture`() = runBlocking {
        val router = BridgeRouter(
            mapOf("app.getSnapshot" to BridgeHandler { JSONObject().put("route", "home") }),
        )

        val actual = JSONObject(router.handle(fixtures.getJSONObject("validRequest").toString()))

        assertTrue(actual.similar(fixtures.getJSONObject("validResponse")))
    }

    @Test
    fun `matches TypeScript rejection of unsafe structural and sensitive payloads`() {
        val malformed = fixtures.getJSONObject("malformedPayloadResponse").getJSONObject("payload")
        val sensitive = fixtures.getJSONObject("sensitiveFieldResponse").getJSONObject("payload")

        val malformedError = assertThrows(BridgeProtocolException::class.java) {
            BridgeEnvelope.success("request-3", malformed)
        }
        val sensitiveError = assertThrows(BridgeProtocolException::class.java) {
            BridgeEnvelope.success("request-3", sensitive)
        }

        assertEquals("BRIDGE_PROTOCOL_INVALID", malformedError.code)
        assertEquals("BRIDGE_SENSITIVE_FIELD", sensitiveError.code)
    }

    @Test
    fun `matches Vue fail closed rejection for every protected playback field`() {
        listOf("url", "streamUrl", "hash", "secret", "source").forEach { field ->
            val error = assertThrows(BridgeProtocolException::class.java) {
                BridgeEnvelope.success("protected-$field", JSONObject().put(field, "redacted"))
            }
            assertEquals(field, "BRIDGE_SENSITIVE_FIELD", error.code)
        }
    }

    @Test
    fun `Android app error producer matches the exact shared Vue event fixture`() {
        val actual = BridgeEnvelope.event(
            "app.error",
            PlayerJsonMapper().error(
                PlayerCommandError(
                    code = "PLAYBACK_NEXT_FAILED",
                    message = "下一首加载失败，按确认键重试",
                    retryable = true,
                    errorId = 7001L,
                ),
            ),
        )

        assertTrue(actual.similar(fixtures.getJSONObject("validAppErrorEvent")))
        assertEquals(
            setOf("code", "message", "retryable", "errorId"),
            actual.getJSONObject("payload").keys().asSequence().toSet(),
        )
    }
}
