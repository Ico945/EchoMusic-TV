package com.kutv.music.bridge

import org.json.JSONArray
import org.json.JSONObject

data class BridgeRequest(
    val version: Int,
    val type: String,
    val id: String,
    val method: String,
    val payload: JSONObject,
)

object BridgeEnvelope {
    const val VERSION = 1

    fun success(requestId: String, payload: JSONObject): JSONObject {
        BridgePayloadValidator.validate(payload)
        return JSONObject()
            .put("version", VERSION)
            .put("type", "response")
            .put("id", requestId)
            .put("ok", true)
            .put("payload", payload)
    }

    fun error(
        requestId: String,
        code: String,
        message: String,
        retryable: Boolean,
        scope: String,
    ): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("type", "response")
        .put("id", requestId)
        .put("ok", false)
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message)
                .put("retryable", retryable)
                .put("scope", scope)
                .put("requestId", requestId),
        )

    fun event(event: String, payload: JSONObject): JSONObject {
        require(event.isNotBlank())
        BridgePayloadValidator.validate(payload)
        return JSONObject()
            .put("version", VERSION)
            .put("type", "event")
            .put("event", event)
            .put("payload", payload)
    }
}

internal object BridgePayloadValidator {
    private val structuralFields = setOf("__proto__", "constructor", "prototype")
    private val sensitiveFields = setOf(
        "token", "accesstoken", "refreshtoken", "device", "deviceid", "deviceidentity",
        "signature", "qrkey", "qrsecret", "playbackurl", "url", "streamurl", "hash", "secret", "source",
    )

    fun validate(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEachRemaining { key ->
                if (key in structuralFields) {
                    throw BridgeProtocolException("BRIDGE_PROTOCOL_INVALID", "请求包含不安全字段")
                }
                if (key.normalizeBridgeField() in sensitiveFields) {
                    throw BridgeProtocolException("BRIDGE_SENSITIVE_FIELD", "请求包含受保护信息")
                }
                validate(value.get(key))
            }
            is JSONArray -> (0 until value.length()).forEach { validate(value.get(it)) }
        }
    }

    private fun String.normalizeBridgeField(): String =
        filter { it.isLetterOrDigit() }.lowercase()
}
