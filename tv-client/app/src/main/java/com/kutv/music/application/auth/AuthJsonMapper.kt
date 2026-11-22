package com.kutv.music.application.auth

import com.kutv.music.web.ArtworkUrlPolicy
import org.json.JSONObject

object AuthJsonMapper {
    fun projection(projection: AuthProjection): JSONObject = JSONObject()
        .put("state", projection.state)
        .put("qrImageDataUrl", projection.qrImageDataUrl ?: JSONObject.NULL)
        .put("expiresAt", projection.expiresAtEpochMs ?: JSONObject.NULL)
        .put("account", projection.account?.let(::account) ?: JSONObject.NULL)

    fun account(account: PublicAccount): JSONObject = JSONObject()
        .put("userId", account.userId)
        .put("nickname", account.nickname)
        .put("avatar", ArtworkUrlPolicy.normalize(account.avatar) ?: "")
        .put("membership", account.membership)
}
