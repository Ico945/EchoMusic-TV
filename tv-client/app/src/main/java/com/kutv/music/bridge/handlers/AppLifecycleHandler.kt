package com.kutv.music.bridge.handlers

import com.kutv.music.bridge.BridgeHandler
import org.json.JSONObject

class AppLifecycleHandler(
    private val exit: () -> Unit,
    private val showIme: () -> Unit = {},
    private val hideIme: () -> Unit = {},
    private val readLicense: suspend (String) -> String = { error("License reader unavailable") },
) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "app.exit" to command(exit),
        "app.showIme" to command(showIme),
        "app.hideIme" to command(hideIme),
        "app.readLicense" to BridgeHandler { payload ->
            require(payload.keys().asSequence().toSet() == setOf("kind"))
            val filename = when (payload.opt("kind")) {
                "gpl" -> "EchoMusic-GPL-3.0.txt"
                "notices" -> "THIRD_PARTY_NOTICES.md"
                else -> throw IllegalArgumentException("Unknown license")
            }
            JSONObject().put("text", readLicense(filename))
        },
    )

    private fun command(action: () -> Unit): BridgeHandler = BridgeHandler { payload ->
        require(payload.length() == 0) { "Payload must be empty" }
        action()
        JSONObject().put("accepted", true)
    }

    companion object {
        val METHODS = setOf("app.exit", "app.showIme", "app.hideIme", "app.readLicense")
    }
}
