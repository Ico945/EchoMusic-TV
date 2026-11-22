package com.kutv.music.bridge.handlers

import com.kutv.music.application.capability.CapabilitySnapshot
import com.kutv.music.application.capability.TvCapabilityDetector
import com.kutv.music.bridge.BridgeHandler
import org.json.JSONObject

class CapabilityBridgeHandler(private val detector: TvCapabilityDetector) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "capability.get" to BridgeHandler { payload ->
            require(payload.length() == 0) { "Payload must be empty" }
            project(detector.snapshot())
        },
    )

    private fun project(snapshot: CapabilitySnapshot): JSONObject = JSONObject()
        .put("microphone", snapshot.microphone)
        .put("voiceInput", snapshot.voiceInput)
        .put("blur", snapshot.blur)

    companion object {
        val METHODS = setOf("capability.get")
    }
}
