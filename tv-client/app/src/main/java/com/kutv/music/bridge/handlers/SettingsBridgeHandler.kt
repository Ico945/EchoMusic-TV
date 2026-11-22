package com.kutv.music.bridge.handlers

import com.kutv.music.application.settings.TvSettings
import com.kutv.music.application.settings.TvSettingsRepository
import com.kutv.music.bridge.BridgeHandler
import org.json.JSONObject

class SettingsBridgeHandler(private val repository: TvSettingsRepository) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "settings.get" to BridgeHandler { payload ->
            payload.requireEmpty()
            project(repository.get())
        },
        "settings.update" to BridgeHandler { payload ->
            val key = payload.requireStringField("key")
            require(payload.has("value")) { "value is required" }
            val value = payload.get("value")
            require(value !== JSONObject.NULL) { "value is required" }
            project(repository.update(key, value).getOrThrow())
        },
        "app.clearCache" to BridgeHandler { payload ->
            payload.requireEmpty()
            JSONObject().put("cacheBytes", 0L)
        },
    )

    private fun project(settings: TvSettings): JSONObject = JSONObject()
        .put(
            "settings",
            JSONObject()
                .put("reducedMotion", settings.reducedMotion)
                .put("defaultQuality", settings.defaultQuality)
                .put("lyricFontScale", settings.lyricFontScale)
                .put("lyricBackground", settings.lyricBackground)
                .put("cacheLimitMb", settings.cacheLimitMb)
                .put("compatiblePluginsEnabled", settings.compatiblePluginsEnabled),
        )
        .put("cacheBytes", 0L)

    companion object {
        val METHODS = setOf("settings.get", "settings.update", "app.clearCache")
    }
}

private fun JSONObject.requireEmpty() {
    require(length() == 0) { "Payload must be empty" }
}

private fun JSONObject.requireStringField(name: String): String {
    val value = opt(name)
    require(value is String && value.isNotBlank()) { "$name 必须是字符串" }
    return value
}
