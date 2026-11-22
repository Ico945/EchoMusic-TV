package com.kutv.music.bridge.handlers

import com.kutv.music.application.recognition.RecognitionState
import com.kutv.music.application.recognition.RecognitionUseCase
import com.kutv.music.bridge.BridgeHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class RecognitionBridgeHandler(
    private val useCase: RecognitionUseCase,
    private val scope: CoroutineScope,
    private val onState: (JSONObject) -> Unit = {},
) {
    private var activeJob: Job? = null

    init {
        scope.launch {
            useCase.state.collect { state -> onState(project(state)) }
        }
    }

    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "recognition.start" to BridgeHandler { payload ->
            payload.requireEmpty()
            if (activeJob?.isActive != true) {
                activeJob = scope.launch { useCase.start() }
            }
            JSONObject().put("state", "recording")
        },
        "recognition.cancel" to BridgeHandler { payload ->
            payload.requireEmpty()
            activeJob?.cancel()
            activeJob = null
            useCase.cancel()
            project(useCase.state.value)
        },
    )

    private fun project(state: RecognitionState): JSONObject = when (state) {
        RecognitionState.Idle -> JSONObject().put("state", "idle")
        RecognitionState.Recording -> JSONObject().put("state", "recording")
        RecognitionState.Processing -> JSONObject().put("state", "processing")
        is RecognitionState.Finished -> JSONObject()
            .put("state", "finished")
            .put(
                "matches",
                JSONArray().apply {
                    state.matches.forEach { match ->
                        put(
                            JSONObject()
                                .put("id", match.id)
                                .put("title", match.title)
                                .put("artist", match.artist)
                                .put("confidence", match.confidence.toDouble()),
                        )
                    }
                },
            )
        is RecognitionState.Error -> JSONObject()
            .put("state", "error")
            .put("message", state.message)
    }

    companion object {
        val METHODS = setOf("recognition.start", "recognition.cancel")
    }
}

private fun JSONObject.requireEmpty() {
    require(length() == 0) { "Payload must be empty" }
}
