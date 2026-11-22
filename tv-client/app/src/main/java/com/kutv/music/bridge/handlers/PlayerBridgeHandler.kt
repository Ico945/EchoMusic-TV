package com.kutv.music.bridge.handlers

import com.kutv.music.application.playback.PlaybackControllerFacade
import com.kutv.music.bridge.BridgeHandler
import com.kutv.music.bridge.mappers.BridgeTrackIds
import org.json.JSONArray
import org.json.JSONObject

class PlayerBridgeHandler(
    private val player: () -> PlaybackControllerFacade?,
) {
    fun handlers(): Map<String, BridgeHandler> = METHODS.associateWith { method ->
        BridgeHandler { payload -> handle(method, payload) }
    }

    suspend fun handle(method: String, payload: JSONObject): JSONObject {
        validate(method, payload)
        if (method == "player.sleepTimer") {
            val timer = com.kutv.music.player.MusicSleepTimerRuntime.timer
            if (payload.has("minutes")) timer?.setMinutes(payload.requireInt("minutes"))
            return JSONObject().put("accepted", timer != null || !payload.has("minutes"))
                .put("endsAt", timer?.endsAt ?: 0L)
        }
        val facade = player() ?: return if (method == "player.clearError") {
            JSONObject().put("accepted", false).put("errorRevision", 0L)
        } else {
            accepted(false)
        }
        if (method == "player.clearError") {
            val result = facade.clearError(payload.requirePositiveLong("errorId"))
            return JSONObject()
                .put("accepted", result.accepted)
                .put("errorRevision", result.errorRevision)
        }
        val result = when (method) {
            "player.editQueue" -> facade.editQueue(payload.requireString("action"),
                if (payload.has("trackId")) BridgeTrackIds.nativeId(payload.requireString("trackId")) else null)
            "player.playQueue" -> {
                payload.requireExactFields("trackIds", "startIndex")
                facade.playQueue(
                    payload.requireTrackIds().map(BridgeTrackIds::nativeId),
                    payload.requireInt("startIndex"),
                )
            }
            "player.playNext" -> {
                payload.requireExactFields("trackId")
                facade.playNext(BridgeTrackIds.nativeId(payload.requireString("trackId")))
            }
            "player.toggle" -> {
                payload.requireExactFields()
                facade.toggle()
            }
            "player.previous" -> {
                payload.requireExactFields()
                facade.previous()
            }
            "player.next" -> {
                payload.requireExactFields()
                facade.next()
            }
            "player.retryCurrent" -> {
                payload.requireExactFields()
                facade.retryCurrent()
            }
            "player.seekTo" -> facade.seekTo(BridgeTrackIds.nativeId(payload.requireString("trackId")), payload.requirePosition())
            "player.seekBy" -> {
                payload.requireExactFields("deltaMs")
                facade.seekBy(payload.requireSeekDelta())
            }
            "player.selectQueueItem" -> {
                payload.requireExactFields("trackId")
                facade.selectQueueItem(BridgeTrackIds.nativeId(payload.requireString("trackId")))
            }
            "player.setRepeatMode" -> {
                payload.requireExactFields("mode")
                val mode = payload.requireString("mode")
                require(mode in setOf("off", "all", "one"))
                facade.setRepeatMode(mode)
            }
            "player.setQuality" -> {
                payload.requireExactFields("quality")
                facade.setQuality(payload.requireString("quality"))
            }
            "player.setSoundEffect" -> {
                payload.requireExactFields("preset")
                facade.setSoundEffect(payload.requireSoundEffectPreset())
            }
            else -> error("Method must be filtered by BridgeRouter")
        }
        return accepted(result)
    }

    private fun accepted(value: Boolean): JSONObject = JSONObject().put("accepted", value)

    private fun validate(method: String, payload: JSONObject) {
        when (method) {
            "player.sleepTimer" -> {
                if (payload.has("minutes")) {
                    payload.requireExactFields("minutes")
                    require(payload.requireInt("minutes") in setOf(0, 15, 30, 60, 90))
                } else payload.requireExactFields()
            }
            "player.editQueue" -> {
                val action = payload.requireString("action")
                require(action in setOf("shuffle", "remove", "clearPending"))
                if (action == "remove") {
                    payload.requireExactFields("action", "trackId")
                    BridgeTrackIds.nativeId(payload.requireString("trackId"))
                } else payload.requireExactFields("action")
            }
            "player.playQueue" -> {
                payload.requireExactFields("trackIds", "startIndex")
                val ids = payload.requireTrackIds()
                require(payload.requireInt("startIndex") in ids.indices)
                ids.forEach(BridgeTrackIds::nativeId)
            }
            "player.playNext", "player.selectQueueItem" -> {
                payload.requireExactFields("trackId")
                BridgeTrackIds.nativeId(payload.requireString("trackId"))
            }
            "player.toggle", "player.previous", "player.next", "player.retryCurrent" -> payload.requireExactFields()
            "player.clearError" -> {
                payload.requireExactFields("errorId")
                payload.requirePositiveLong("errorId")
            }
            "player.seekTo" -> {
                payload.requireExactFields("trackId", "positionMs")
                BridgeTrackIds.nativeId(payload.requireString("trackId"))
                payload.requirePosition()
            }
            "player.seekBy" -> {
                payload.requireExactFields("deltaMs")
                payload.requireSeekDelta()
            }
            "player.setRepeatMode" -> {
                payload.requireExactFields("mode")
                require(payload.requireString("mode") in setOf("off", "all", "one"))
            }
            "player.setQuality" -> {
                payload.requireExactFields("quality")
                payload.requireString("quality")
            }
            "player.setSoundEffect" -> {
                payload.requireExactFields("preset")
                payload.requireSoundEffectPreset()
            }
            else -> error("Method must be filtered by BridgeRouter")
        }
    }

    companion object {
        val METHODS = setOf(
            "player.sleepTimer",
            "player.editQueue",
            "player.playQueue",
            "player.playNext",
            "player.toggle",
            "player.previous",
            "player.next",
            "player.retryCurrent",
            "player.clearError",
            "player.seekBy",
            "player.seekTo",
            "player.selectQueueItem",
            "player.setRepeatMode",
            "player.setQuality",
            "player.setSoundEffect",
        )
    }
}

private fun JSONObject.requireExactFields(vararg expected: String) {
    val actual = keys().asSequence().toSet()
    require(actual == expected.toSet())
}

private fun JSONObject.requireString(name: String): String {
    val value = opt(name) as? String
    require(!value.isNullOrBlank() && value.length <= 256)
    return value
}

private fun JSONObject.requireInt(name: String): Int =
    (opt(name) as? Int) ?: throw IllegalArgumentException("Expected integer")

private fun JSONObject.requirePositiveLong(name: String): Long {
    val value = when (val raw = opt(name)) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> throw IllegalArgumentException("Expected positive integer")
    }
    require(value > 0L)
    return value
}

private fun JSONObject.requirePosition(): Long {
    val value = when (val raw = opt("positionMs")) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> throw IllegalArgumentException("Expected position integer")
    }
    require(value in 0L..9_007_199_254_740_991L)
    return value
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

private fun JSONObject.requireSoundEffectPreset(): String = requireString("preset").also { preset ->
    require(preset in setOf("off", "bass", "vocal", "live"))
}

private fun JSONObject.requireTrackIds(): List<String> {
    val array = opt("trackIds") as? JSONArray ?: throw IllegalArgumentException("Expected track ids")
    require(array.length() in 1..500)
    val ids = (0 until array.length()).map { index ->
        (array.opt(index) as? String)?.takeIf { it.isNotBlank() && it.length <= 256 }
            ?: throw IllegalArgumentException("Invalid track id")
    }
    require(ids.distinct().size == ids.size)
    return ids
}
