package com.kutv.music.application.content

data class ShareProjection(
    val url: String,
    val qrImageDataUrl: String,
    val clipboardAvailable: Boolean,
)
