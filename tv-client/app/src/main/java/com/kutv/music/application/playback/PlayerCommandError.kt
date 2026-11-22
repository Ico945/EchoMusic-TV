package com.kutv.music.application.playback

data class PlayerCommandError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val errorId: Long,
)

internal data class PlayerErrorDescriptor(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

internal object PlayerCommandErrors {
    fun failed() = PlayerErrorDescriptor(
        code = "PLAYBACK_COMMAND_FAILED",
        message = "暂时无法完成播放操作",
        retryable = true,
    )

    fun invalid() = PlayerErrorDescriptor(
        code = "PLAYBACK_COMMAND_INVALID",
        message = "播放操作参数无效",
        retryable = false,
    )

    fun soundEffectUnavailable() = PlayerErrorDescriptor(
        code = "SOUND_EFFECT_UNAVAILABLE",
        message = "当前设备暂不支持此音效",
        retryable = false,
    )
}
