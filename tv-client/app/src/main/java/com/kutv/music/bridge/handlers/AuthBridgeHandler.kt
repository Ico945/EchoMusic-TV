package com.kutv.music.bridge.handlers

import com.kutv.music.application.auth.AuthJsonMapper
import com.kutv.music.application.auth.AuthUseCases
import com.kutv.music.bridge.BridgeHandler
import org.json.JSONObject

class AuthBridgeHandler(
    private val useCases: AuthUseCases,
    private val onQrFlowStarted: suspend () -> Unit = {},
    private val onQrFlowStopped: () -> Unit = {},
    private val onLoggedOut: suspend () -> Unit = {},
) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "auth.beginQr" to BridgeHandler { payload ->
            payload.requireEmpty()
            val projection = useCases.beginQr()
            onQrFlowStarted()
            AuthJsonMapper.projection(projection)
        },
        "auth.cancelQr" to BridgeHandler { payload ->
            payload.requireEmpty()
            onQrFlowStopped()
            AuthJsonMapper.projection(useCases.cancel())
        },
        "auth.restore" to BridgeHandler { payload ->
            payload.requireEmpty()
            AuthJsonMapper.projection(useCases.restore())
        },
        "auth.logout" to BridgeHandler { payload ->
            payload.requireEmpty()
            onQrFlowStopped()
            val projection = useCases.logout()
            onLoggedOut()
            AuthJsonMapper.projection(projection)
        },
    )

    companion object {
        val METHODS = setOf("auth.beginQr", "auth.cancelQr", "auth.restore", "auth.logout")
    }
}

private fun JSONObject.requireEmpty() {
    require(length() == 0) { "Payload must be empty" }
}
