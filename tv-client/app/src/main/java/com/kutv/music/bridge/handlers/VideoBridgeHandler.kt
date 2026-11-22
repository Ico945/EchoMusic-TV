package com.kutv.music.bridge.handlers

import com.kutv.music.bridge.BridgeHandler
import com.kutv.music.video.MvPlaybackController
import com.kutv.music.video.MvRouteGate
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

class VideoBridgeHandler(
    private val controller: () -> MvPlaybackController?,
    private val routeGate: MvRouteGate,
    private val operationScope: CoroutineScope,
) {
    fun handlers(): Map<String, BridgeHandler> = METHODS.associateWith { method ->
        BridgeHandler { payload -> handle(method, payload) }
    }

    suspend fun handle(method: String, payload: JSONObject): JSONObject {
        validate(method, payload)
        val playback = controller() ?: return unavailable(method)
        return when (method) {
            "video.open" -> if (routeGate.allowsMvOpen) {
                playback.requestOpen(operationScope, payload.requireString("mvId"), payload.requireQuality("quality"))
            } else {
                JSONObject().put("accepted", false).put("errorCode", "MV_ROUTE_REQUIRED")
            }
            "video.toggle" -> playback.toggle()
            "video.seekBy" -> playback.seekBy(payload.requireSeekDelta())
            "video.setQuality" -> playback.requestSetQuality(operationScope, payload.requireQuality("quality"))
            "video.close" -> playback.close()
            "video.snapshot" -> playback.snapshot()
            else -> error("Method must be filtered by BridgeRouter")
        }
    }

    private fun validate(method: String, payload: JSONObject) {
        when (method) {
            "video.open" -> {
                payload.requireExactFields("mvId", "quality")
                require(payload.requireString("mvId").matches(MV_ID_PATTERN))
                payload.requireQuality("quality")
            }
            "video.toggle", "video.close", "video.snapshot" -> payload.requireExactFields()
            "video.seekBy" -> {
                payload.requireExactFields("deltaMs")
                payload.requireSeekDelta()
            }
            "video.setQuality" -> {
                payload.requireExactFields("quality")
                payload.requireQuality("quality")
            }
            else -> error("Method must be filtered by BridgeRouter")
        }
    }

    private fun unavailable(method: String): JSONObject = if (method == "video.snapshot") {
        JSONObject()
            .put("durationMs", 0L)
            .put("positionMs", 0L)
            .put("playing", false)
            .put("quality", "")
            .put("errorCode", "MV_UNAVAILABLE")
    } else {
        JSONObject().put("accepted", false).put("errorCode", "MV_UNAVAILABLE")
    }

    companion object {
        val METHODS = setOf("video.open", "video.toggle", "video.seekBy", "video.setQuality", "video.close", "video.snapshot")
        val QUALITIES = setOf("fhd", "hd", "qhd", "sd", "ld")
        val MV_ID_PATTERN = Regex("^[A-Za-z0-9:_-]{1,128}$")
    }
}

private fun JSONObject.requireExactFields(vararg expected: String) {
    require(keys().asSequence().toSet() == expected.toSet())
}

private fun JSONObject.requireString(name: String): String = (opt(name) as? String)
    ?.takeIf { it.isNotBlank() && it.length <= 128 }
    ?: throw IllegalArgumentException("Expected string")

private fun JSONObject.requireQuality(name: String): String = requireString(name).also { quality ->
    require(quality in VideoBridgeHandler.QUALITIES)
}

private fun JSONObject.requireSeekDelta(): Long {
    val value = when (val raw = opt("deltaMs")) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> throw IllegalArgumentException("Expected seek delta")
    }
    require(value == -10_000L || value == 10_000L)
    return value
}
