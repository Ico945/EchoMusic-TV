package com.kutv.music.direct

import org.json.JSONObject

/** Avoid Android JSONObject's string coercion of JSON null and invalid field types. */
internal fun kugouJsonText(value: Any?): String? = (value as? String)
    ?.takeIf { it.isNotBlank() && !it.trim().equals("null", ignoreCase = true) }

internal fun normalizedDfid(value: Any?): String? = kugouJsonText(value)?.trim()

internal fun parseKugouCredentials(data: JSONObject, allowNumericUserId: Boolean = false): KugouCredentials? {
    val rawId = data.opt("userid")
    val userId = kugouJsonText(rawId)
        ?: if (allowNumericUserId && rawId is Number) rawId.toString() else null
    val token = kugouJsonText(data.opt("token"))
    if (userId == null || token == null) return null
    return KugouCredentials(
        userId = userId,
        token = token,
        vipToken = kugouJsonText(data.opt("vip_token")),
        vipType = data.optInt("vip_type", 0),
    )
}
