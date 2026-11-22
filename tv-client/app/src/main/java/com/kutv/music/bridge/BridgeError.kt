package com.kutv.music.bridge

class BridgeProtocolException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

class BridgeMethodNotFound(method: String) : RuntimeException(method)

object BridgeErrors {
    fun fromException(requestId: String, error: Throwable): org.json.JSONObject = when (error) {
        is BridgeProtocolException -> BridgeEnvelope.error(requestId, error.code, error.message ?: "请求格式无效", false, "bridge")
        is BridgeMethodNotFound -> BridgeEnvelope.error(requestId, "BRIDGE_METHOD_NOT_FOUND", "不支持的操作", false, "bridge")
        is ContentBridgeException -> BridgeEnvelope.error(requestId, error.errorCode, error.message ?: "暂时无法完成操作", error.errorRetryable, error.errorScope)
        is IllegalArgumentException -> BridgeEnvelope.error(requestId, "BRIDGE_PAYLOAD_INVALID", "请求参数无效", false, "bridge")
        else -> BridgeEnvelope.error(requestId, "INTERNAL_ERROR", "暂时无法完成操作", true, "app")
    }
}
