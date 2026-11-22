package com.kutv.music.bridge

import com.kutv.music.security.BridgePayloadPolicy
import org.json.JSONObject

object BridgeParser {
    const val MAX_MESSAGE_BYTES = BridgePayloadPolicy.MAX_MESSAGE_BYTES
    private val requestFields = setOf("version", "type", "id", "method", "payload")

    fun parseRequest(raw: String): BridgeRequest {
        if (isMessageTooLarge(raw)) {
            throw BridgeProtocolException("BRIDGE_PAYLOAD_TOO_LARGE", "请求过大")
        }
        if (!BridgePayloadPolicy.withinDepthLimit(raw)) {
            throw BridgeProtocolException("BRIDGE_PAYLOAD_TOO_DEEP", "请求嵌套过深")
        }
        val message = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw BridgeProtocolException("BRIDGE_PROTOCOL_INVALID", "请求格式无效")
        }
        message.keys().forEachRemaining { key ->
            if (key !in requestFields) {
                throw BridgeProtocolException("BRIDGE_PROTOCOL_INVALID", "请求包含未知字段")
            }
        }
        if (requestFields.any { !message.has(it) }) {
            throw BridgeProtocolException("BRIDGE_PROTOCOL_INVALID", "请求缺少必要字段")
        }
        val version = message.get("version")
        if (version !is Int || version != BridgeEnvelope.VERSION) {
            throw BridgeProtocolException("BRIDGE_VERSION_UNSUPPORTED", "不支持的协议版本")
        }
        if (message.optString("type", "") != "request") {
            throw BridgeProtocolException("BRIDGE_PROTOCOL_INVALID", "请求类型无效")
        }
        val id = message.requireNonBlankString("id")
        val method = message.requireNonBlankString("method")
        val payload = message.opt("payload") as? JSONObject
            ?: throw BridgeProtocolException("BRIDGE_PAYLOAD_INVALID", "请求参数无效")
        BridgePayloadValidator.validate(payload)
        return BridgeRequest(BridgeEnvelope.VERSION, "request", id, method, payload)
    }

    fun requestIdOrFallback(raw: String): String = try {
        JSONObject(raw).opt("id")?.let { it as? String }?.takeIf { it.isNotBlank() } ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    fun isMessageTooLarge(raw: String): Boolean =
        raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES

    private fun JSONObject.requireNonBlankString(name: String): String =
        (opt(name) as? String)?.takeIf { it.isNotBlank() }
            ?: throw BridgeProtocolException("BRIDGE_PROTOCOL_INVALID", "请求字段无效")
}
