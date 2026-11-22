package com.kutv.music.direct

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object KugouSigner {
    const val APP_ID = 3116
    const val CLIENT_VERSION = 11440
    const val STANDARD_APP_ID = 1005
    const val STANDARD_CLIENT_VERSION = 20489
    const val SOURCE_APP_ID = 2919

    const val ANDROID_LITE = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    const val WEB = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    const val REGISTER = "1014"
    // Public compatibility salt from KuGouMusicApi util/helper.js (a5a98013).
    const val SIGN_KEY_LITE = "185672dd44712f60bb1736df5a377e82" // gitleaks:allow

    fun md5(input: String): String = MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun stringifyQueryValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> value
        is Number, is Boolean -> value.toString()
        is JSONObject, is JSONArray -> value.toString()
        is Map<*, *> -> mapToJson(value).toString()
        is Iterable<*> -> iterableToJson(value).toString()
        is Array<*> -> iterableToJson(value.asIterable()).toString()
        else -> value.toString()
    }

    fun signatureAndroid(
        params: Map<String, Any?>,
        body: String = "",
        salt: String = ANDROID_LITE,
    ): String {
        val serialized = params.keys.sorted().joinToString("") { key ->
            "$key=${stringifyQueryValue(params[key])}"
        }
        return md5("$salt$serialized$body$salt")
    }

    fun signatureWeb(
        params: Map<String, Any?>,
        salt: String = WEB,
    ): String {
        val serialized = params.entries
            .map { (key, value) -> "$key=${stringifyQueryValue(value)}" }
            .sorted()
            .joinToString("")
        return md5("$salt$serialized$salt")
    }

    fun signatureRegister(
        params: Map<String, Any?>,
        salt: String = REGISTER,
    ): String {
        val serialized = params.values.map(::stringifyQueryValue).sorted().joinToString("")
        return md5("$salt$serialized$salt")
    }

    fun signKey(
        hash: String,
        mid: String,
        userId: String,
        appId: Int = APP_ID,
        salt: String = SIGN_KEY_LITE,
    ): String = md5("$hash$salt$appId$mid${userId.ifBlank { "0" }}")

    fun signParamsKey(
        data: Any,
        appId: Int = APP_ID,
        clientVersion: Int = CLIENT_VERSION,
        salt: String = ANDROID_LITE,
    ): String = md5("$appId$salt$clientVersion$data")

    fun sign(request: KugouRequest): KugouRequest {
        if (request.signMode == KugouSignMode.NONE) return request
        val body = when (val value = request.body) {
            null -> ""
            is String -> value
            is JSONObject, is JSONArray -> value.toString()
            is Map<*, *> -> mapToJson(value).toString()
            // Binary bodies (e.g. PCM recognition uploads) contribute an empty
            // body segment to the signature, matching the upstream client.
            is ByteArray -> ""
            else -> value.toString()
        }
        val signature = when (request.signMode) {
            KugouSignMode.ANDROID -> signatureAndroid(request.query, body)
            KugouSignMode.WEB -> signatureWeb(request.query)
            KugouSignMode.REGISTER -> signatureRegister(request.query)
            KugouSignMode.NONE -> return request
        }
        return request.copy(query = LinkedHashMap(request.query).apply { put("signature", signature) })
    }

    private fun mapToJson(map: Map<*, *>): JSONObject = JSONObject().apply {
        map.forEach { (key, value) -> put(key.toString(), jsonValue(value)) }
    }

    private fun iterableToJson(values: Iterable<*>): JSONArray = JSONArray().apply {
        values.forEach { put(jsonValue(it)) }
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> mapToJson(value)
        is Iterable<*> -> iterableToJson(value)
        is Array<*> -> iterableToJson(value.asIterable())
        else -> value
    }
}
