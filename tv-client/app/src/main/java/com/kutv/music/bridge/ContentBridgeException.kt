package com.kutv.music.bridge

class ContentBridgeException(
    val errorCode: String,
    message: String,
    val errorRetryable: Boolean,
    val errorScope: String,
) : RuntimeException(message)
