package com.kutv.music.bridge

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

fun interface BridgeHandler {
    suspend fun handle(payload: JSONObject): JSONObject
}

class BridgeRouter(
    handlers: Map<String, BridgeHandler>,
    private val requestIdExtractor: (String) -> String = BridgeParser::requestIdOrFallback,
) {
    private val handlers = handlers.filterKeys { it in ALLOWED_METHODS }

    suspend fun handle(raw: String): String {
        if (BridgeParser.isMessageTooLarge(raw)) {
            return BridgeErrors.fromException(
                "unknown",
                BridgeProtocolException("BRIDGE_PAYLOAD_TOO_LARGE", "请求过大"),
            ).toString()
        }
        val requestId = requestIdExtractor(raw)
        val request = try {
            BridgeParser.parseRequest(raw)
        } catch (error: Throwable) {
            return BridgeErrors.fromException(requestId, error).toString()
        }
        val handler = handlers[request.method]
            ?: return BridgeErrors.fromException(request.id, BridgeMethodNotFound(request.method)).toString()
        return try {
            BridgeEnvelope.success(request.id, handler.handle(request.payload)).toString()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BridgeErrors.fromException(request.id, error).toString()
        }
    }

    private companion object {
        val ALLOWED_METHODS = setOf("app.getSnapshot", "content.home") +
            com.kutv.music.bridge.handlers.AppLifecycleHandler.METHODS +
            com.kutv.music.bridge.handlers.PlayerBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.VideoBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.AuthBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.BrowseBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.DetailBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.LibraryBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.ShareBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.SettingsBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.CapabilityBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.RecognitionBridgeHandler.METHODS +
            com.kutv.music.bridge.handlers.PluginBridgeHandler.METHODS
    }
}
