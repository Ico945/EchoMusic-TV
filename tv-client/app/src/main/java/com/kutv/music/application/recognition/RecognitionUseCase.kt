package com.kutv.music.application.recognition

import com.kutv.music.direct.recognition.KugouRecognitionApi
import com.kutv.music.direct.recognition.RecognitionMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data object Recording : RecognitionState
    data object Processing : RecognitionState
    data class Finished(val matches: List<RecognitionMatch>) : RecognitionState
    data class Error(val message: String) : RecognitionState
}

class RecognitionUseCase(
    private val recorder: PcmRecorder,
    private val api: KugouRecognitionApi,
    private val permissionRequester: suspend () -> Boolean,
    private val recordDurationMs: Long = 10_000L,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    suspend fun start(): Result<List<RecognitionMatch>> {
        if (_state.value is RecognitionState.Recording || _state.value is RecognitionState.Processing) {
            return Result.failure(IllegalStateException("识别已在进行"))
        }
        val granted = try {
            permissionRequester()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
        if (!granted) {
            _state.value = RecognitionState.Error("需要麦克风权限")
            return Result.failure(UnsupportedOperationException("需要麦克风权限"))
        }
        _state.value = RecognitionState.Recording
        return try {
            recorder.start()
            sleeper(recordDurationMs)
            val bytes = recorder.stop()
            _state.value = RecognitionState.Processing
            val matches = api.match(bytes)
            _state.value = RecognitionState.Finished(matches)
            Result.success(matches)
        } catch (error: CancellationException) {
            runCatching { recorder.stop() }
            _state.value = RecognitionState.Idle
            throw error
        } catch (error: Throwable) {
            runCatching { recorder.stop() }
            _state.value = RecognitionState.Error(error.message ?: "识别失败")
            Result.failure(error)
        }
    }

    fun cancel() {
        if (_state.value is RecognitionState.Recording) {
            runCatching { recorder.stop() }
        }
        _state.value = RecognitionState.Idle
    }
}
