package com.kutv.music.bridge.handlers

import com.kutv.music.application.plugins.TvPluginInfo
import com.kutv.music.application.plugins.TvPluginRegistry
import com.kutv.music.bridge.BridgeHandler
import org.json.JSONArray
import org.json.JSONObject

class PluginBridgeHandler(private val registry: TvPluginRegistry) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "plugins.list" to BridgeHandler { payload ->
            payload.requireEmpty()
            JSONObject()
                .put(
                    "plugins",
                    JSONArray().apply {
                        registry.list().forEach { put(plugin(it)) }
                    },
                )
                .put("disabledReason", registry.disabledReason ?: JSONObject.NULL)
        },
        "plugins.invoke" to BridgeHandler { payload ->
            val id = payload.requireStringField("id")
            val action = payload.requireStringField("action")
            registry.invoke(id, action).getOrThrow()
            JSONObject().put("accepted", true)
        },
    )

    private fun plugin(info: TvPluginInfo): JSONObject = JSONObject()
        .put("id", info.id)
        .put("version", info.version)
        .put("capabilities", JSONArray(info.capabilities))
        .put("bridgeScopes", JSONArray(info.bridgeScopes))

    companion object {
        val METHODS = setOf("plugins.list", "plugins.invoke")
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
