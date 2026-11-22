package com.kutv.music.direct

import org.json.JSONObject

data class KugouDeviceIdentity(
    val guid: String,
    val mid: String,
    val uuid: String,
    val dfid: String? = null,
    val mac: String = PRIVACY_SAFE_MAC,
) {
    companion object {
        const val PRIVACY_SAFE_MAC = "02:00:00:00:00:00"
    }
}

data class KugouCredentials(
    val userId: String,
    val token: String,
    val vipToken: String? = null,
    val vipType: Int = 0,
)

data class KugouAccount(
    val userId: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val membership: String = "none",
)

internal const val KUGOU_PRIVATE_DISPLAY_NAME = "酷狗用户"

/**
 * KuGou sometimes omits a nickname and older builds substituted the numeric
 * account id. A television is a shared display, so that implementation detail
 * must never become the user-facing label.
 */
internal fun privacySafeKugouNickname(userId: String, nickname: String?): String {
    val candidate = nickname?.trim().orEmpty()
    return when {
        candidate.isBlank() -> KUGOU_PRIVATE_DISPLAY_NAME
        candidate == userId -> KUGOU_PRIVATE_DISPLAY_NAME
        candidate == "#$userId" -> KUGOU_PRIVATE_DISPLAY_NAME
        candidate == "用户$userId" -> KUGOU_PRIVATE_DISPLAY_NAME
        else -> candidate
    }
}

internal fun KugouAccount.withPrivacySafeNickname(): KugouAccount = copy(
    nickname = privacySafeKugouNickname(userId, nickname),
)

data class KugouQrSession(
    val key: String,
    val qrUrl: String,
    val expiresInSeconds: Int = 180,
)

sealed interface KugouQrState {
    data object Waiting : KugouQrState
    data object Scanned : KugouQrState
    data object Expired : KugouQrState
    data class Confirmed(val credentials: KugouCredentials) : KugouQrState
}

enum class KugouSignMode {
    ANDROID,
    WEB,
    REGISTER,
    NONE,
}

data class KugouRequest(
    val method: String,
    val baseUrl: String,
    val path: String,
    val query: Map<String, Any?> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: Any? = null,
    val signMode: KugouSignMode = KugouSignMode.ANDROID,
    val includeCredentials: Boolean = false,
    val clearDefaultParams: Boolean = false,
) {
    companion object {
        fun get(
            baseUrl: String,
            path: String,
            query: Map<String, Any?> = emptyMap(),
            signMode: KugouSignMode = KugouSignMode.ANDROID,
            includeCredentials: Boolean = false,
            clearDefaultParams: Boolean = false,
        ) = KugouRequest(
            method = "GET",
            baseUrl = baseUrl,
            path = path,
            query = query,
            signMode = signMode,
            includeCredentials = includeCredentials,
            clearDefaultParams = clearDefaultParams,
        )
    }
}

data class KugouResponse(
    val statusCode: Int,
    val body: JSONObject,
    val headers: Map<String, List<String>> = emptyMap(),
)

fun interface KugouRequestExecutor {
    suspend fun execute(request: KugouRequest): KugouResponse
}

data class KugouTrackBinding(
    val trackId: String,
    val hash: String,
    val albumId: String? = null,
    val lyricId: String? = null,
)

/**
 * Maps only the documented KuGou QR states. Unknown and incomplete payloads
 * remain waiting, which prevents a partial upstream response from creating a
 * broken authenticated session.
 */
fun mapQrPayload(payload: JSONObject): KugouQrState {
    val data = payload.optJSONObject("data") ?: payload
    return when (data.optInt("status", -1)) {
        0 -> KugouQrState.Expired
        2 -> KugouQrState.Scanned
        4 -> {
            parseKugouCredentials(data, allowNumericUserId = true)
                ?.let(KugouQrState::Confirmed) ?: KugouQrState.Waiting
        }

        else -> KugouQrState.Waiting
    }
}
