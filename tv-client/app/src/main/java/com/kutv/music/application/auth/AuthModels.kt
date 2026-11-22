package com.kutv.music.application.auth

data class PublicAccount(
    val userId: String,
    val nickname: String,
    val avatar: String = "",
    val membership: String = "none",
)

data class AuthProjection(
    val state: String,
    val qrImageDataUrl: String? = null,
    val expiresAtEpochMs: Long? = null,
    val account: PublicAccount? = null,
)
