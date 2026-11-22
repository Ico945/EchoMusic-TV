package com.kutv.music.bridge.handlers

import com.kutv.music.application.recognition.PcmRecorder
import com.kutv.music.application.recognition.RecognitionUseCase
import com.kutv.music.direct.recognition.KugouRecognitionApi
import com.kutv.music.direct.recognition.RecognitionMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionBridgeHandlerTest {
    @Test
    fun `start returns recording and projects the finished state through the callback`() = runBlocking {
        val states = mutableListOf<JSONObject>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val handler = RecognitionBridgeHandler(useCase(success = true), scope) { states.add(it) }

        val result = handler.handlers().getValue("recognition.start").handle(JSONObject())

        assertEquals("recording", result.getString("state"))
        val terminal = states.last()
        assertEquals("finished", terminal.getString("state"))
        assertTrue(terminal.has("matches"))
    }

    @Test
    fun `denied permission projects the error state through the callback`() = runBlocking {
        val states = mutableListOf<JSONObject>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val handler = RecognitionBridgeHandler(useCase(success = true, permissionGranted = false), scope) { states.add(it) }

        handler.handlers().getValue("recognition.start").handle(JSONObject())

        val terminal = states.last()
        assertEquals("error", terminal.getString("state"))
        assertEquals("需要麦克风权限", terminal.getString("message"))
    }

    @Test
    fun `start rejects a non empty payload`() {
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val handler = RecognitionBridgeHandler(useCase(success = false), scope) {}
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { handler.handlers().getValue("recognition.start").handle(JSONObject().put("x", 1)) }
            }
        }
    }

    @Test
    fun `cancel projects idle`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val handler = RecognitionBridgeHandler(useCase(success = true), scope) {}
        val result = handler.handlers().getValue("recognition.cancel").handle(JSONObject())

        assertEquals("idle", result.getString("state"))
    }

    @Test
    fun `recognition methods are registered`() {
        assertEquals(
            setOf("recognition.start", "recognition.cancel"),
            RecognitionBridgeHandler.METHODS,
        )
    }

    private fun useCase(success: Boolean, permissionGranted: Boolean = true): RecognitionUseCase {
        val api = object : KugouRecognitionApi {
            override suspend fun match(pcm: ByteArray): List<RecognitionMatch> =
                if (success) listOf(RecognitionMatch("h", "歌", "手", 0.8f)) else emptyList()
        }
        return RecognitionUseCase(FakeRecorder(), api, { permissionGranted }, sleeper = { })
    }

    private class FakeRecorder : PcmRecorder {
        override fun start() = Unit
        override fun stop(): ByteArray = ByteArray(160_000)
    }
}
