package com.kutv.music.direct

import java.net.URLEncoder
import org.json.JSONObject

interface KugouAuthGateway {
    suspend fun createQr(): KugouQrSession
    suspend fun pollQr(key: String): KugouQrState
    suspend fun getAccount(credentials: KugouCredentials): KugouAccount
}

class KugouDirectAuth(
    private val executor: KugouRequestExecutor,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : KugouAuthGateway {
    override suspend fun createQr(): KugouQrSession {
        val qrText = "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${KugouSigner.APP_ID}&"
        val response = executor.execute(
            KugouRequest.get(
                baseUrl = LOGIN_BASE,
                path = "/v2/qrcode",
                query = linkedMapOf(
                    "appid" to 1001,
                    "type" to 1,
                    "plat" to 4,
                    "qrcode_txt" to qrText,
                    "srcappid" to KugouSigner.SOURCE_APP_ID,
                ),
                signMode = KugouSignMode.WEB,
            ),
        )
        val data = response.body.optJSONObject("data")
            ?: throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        val key = data.optString("qrcode").ifBlank { data.optString("key") }
        if (key.isBlank()) throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        return KugouQrSession(
            key = key,
            qrUrl = "$QR_PAGE?qrcode=${urlEncode(key)}",
            expiresInSeconds = 180,
        )
    }

    override suspend fun pollQr(key: String): KugouQrState {
        require(key.isNotBlank()) { "QR key cannot be blank" }
        val response = executor.execute(
            KugouRequest.get(
                baseUrl = LOGIN_BASE,
                path = "/v2/get_userinfo_qrcode",
                query = linkedMapOf(
                    "plat" to 4,
                    "appid" to KugouSigner.APP_ID,
                    "srcappid" to KugouSigner.SOURCE_APP_ID,
                    "qrcode" to key,
                ),
                signMode = KugouSignMode.WEB,
            ),
        )
        return mapQrPayload(response.body)
    }

    override suspend fun getAccount(credentials: KugouCredentials): KugouAccount {
        val now = clockSeconds()
        val protected = KugouCrypto.rsaRawEncrypt(
            JSONObject()
                .put("token", credentials.token)
                .put("clienttime", now)
                .toString(),
        ).uppercase()
        val response = executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = "https://usercenter.kugou.com",
                path = "/v3/get_my_info",
                // Bind this lookup to its explicit credentials, not a mutable
                // global store belonging to an earlier/later login.
                query = mapOf("plat" to 1, "userid" to credentials.userId, "token" to credentials.token),
                body = JSONObject()
                    .put("visit_time", now)
                    .put("usertype", 1)
                    .put("p", protected)
                    .put("userid", credentials.userId.toLongOrNull() ?: 0L),
                includeCredentials = false,
            ),
        )
        val data = response.body.optJSONObject("data") ?: response.body
        val nickname = data.optString("nickname").ifBlank { data.optString("user_name") }
        val avatar = data.optString("pic").ifBlank { data.optString("avatar") }
        return KugouAccount(
            userId = credentials.userId,
            nickname = privacySafeKugouNickname(credentials.userId, nickname),
            avatarUrl = avatar.takeIf(String::isNotBlank),
            membership = when {
                credentials.vipType >= 2 -> "svip"
                credentials.vipType == 1 -> "vip"
                else -> "none"
            },
        )
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val LOGIN_BASE = "https://login-user.kugou.com"
        const val QR_PAGE = "https://h5.kugou.com/apps/loginQRCode/html/index.html"
    }
}
