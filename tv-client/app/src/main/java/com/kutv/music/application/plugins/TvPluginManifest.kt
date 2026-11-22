package com.kutv.music.application.plugins

import org.json.JSONObject

data class TvPluginManifest(
    val id: String,
    val version: String,
    val entry: String,
    val capabilities: List<String>,
    val bridgeScopes: List<String>,
) {
    companion object {
        val ALLOWED_BRIDGE_SCOPES = setOf("content.read", "player.control", "library.read", "navigation.open")
        const val REQUIRED_CAPABILITY = "android-tv-v1"

        fun parse(raw: String): Result<TvPluginManifest> = runCatching {
            val json = JSONObject(raw)
            val id = json.optString("id")
            require(id.matches(ID_REGEX)) { "id 无效" }
            val version = json.optString("version")
            require(version.matches(VERSION_REGEX)) { "version 无效" }
            val entry = json.optString("entry")
            require(isLocalRelativePath(entry)) { "entry 必须是本地相对路径" }
            val capabilities = stringArray(json, "capabilities")
            require(REQUIRED_CAPABILITY in capabilities) { "capabilities 必须包含 $REQUIRED_CAPABILITY" }
            val bridgeScopes = stringArray(json, "bridgeScopes")
            require(bridgeScopes.all { it in ALLOWED_BRIDGE_SCOPES }) { "bridgeScopes 包含未知作用域" }
            TvPluginManifest(id = id, version = version, entry = entry, capabilities = capabilities, bridgeScopes = bridgeScopes)
        }

        private val ID_REGEX = Regex("^[a-z0-9-]{3,32}$")
        private val VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")

        private fun stringArray(json: JSONObject, key: String): List<String> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { index ->
                array.optString(index).takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("$key 包含空项")
            }
        }

        private fun isLocalRelativePath(path: String): Boolean {
            if (path.isBlank()) return false
            if (path.startsWith("/") || path.startsWith("\\")) return false
            if (path.contains(":") || path.contains("\\")) return false
            val segments = path.split("/")
            return segments.none { it.isBlank() || it == "." || it == ".." }
        }
    }
}
