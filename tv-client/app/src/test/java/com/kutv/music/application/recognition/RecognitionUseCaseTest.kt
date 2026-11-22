package com.kutv.music.application.recognition

import com.kutv.music.direct.recognition.KugouRecognitionApi
import com.kutv.music.direct.recognition.RecognitionMatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionUseCaseTest {
    @Test
    fun `ten seconds of 8khz mono 16bit pcm is 80000 samples`() = runBlocking {
        val recorder = FakePcmRecorder(bytes = ByteArray(160_000) { it.toByte() })
        val api = FakeApi(result = listOf(RecognitionMatch("h1", "歌", "手", 0.9f)))
        val useCase = RecognitionUseCase(recorder, api, { true }, sleeper = { })

        val result = useCase.start()

        assertTrue(result.isSuccess)
        assertEquals(160_000, api.receivedBytes?.size)
        assertEquals(80_000, api.receivedBytes!!.size / 2)
        assertTrue(recorder.started)
        assertTrue(recorder.released)
    }

    @Test
    fun `denied permission fails without recording`() = runBlocking {
        val recorder = FakePcmRecorder()
        val api = FakeApi()
        val useCase = RecognitionUseCase(recorder, api, { false })

        val result = useCase.start()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        assertEquals("需要麦克风权限", error?.message)
        assertFalse(recorder.started)
        assertEquals(null, api.receivedBytes)
        assertEquals(RecognitionState.Error("需要麦克风权限"), useCase.state.value)
    }

    @Test
    fun `cancel releases the recorder`() = runBlocking {
        val recorder = FakePcmRecorder(bytes = ByteArray(160_000))
        val api = FakeApi()
        val useCase = RecognitionUseCase(recorder, api, { true }, sleeper = { delay(60_000) })

        val job = launch { useCase.start() }
        while (useCase.state.value != RecognitionState.Recording) yield()

        useCase.cancel()

        assertTrue(recorder.released)
        assertEquals(RecognitionState.Idle, useCase.state.value)
        job.cancel()
    }

    private class FakePcmRecorder(private val bytes: ByteArray = ByteArray(0)) : PcmRecorder {
        var started = false
        var released = false

        override fun start() {
            started = true
        }

        override fun stop(): ByteArray {
            released = true
            return bytes
        }
    }

    private class FakeApi(
        private val result: List<RecognitionMatch> = emptyList(),
    ) : KugouRecognitionApi {
        var receivedBytes: ByteArray? = null

        override suspend fun match(pcm: ByteArray): List<RecognitionMatch> {
            receivedBytes = pcm
            return result
        }
    }
}
