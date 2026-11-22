package com.kutv.music.ui

import com.kutv.music.core.model.TimedLyricLine

enum class TopDestination(val label: String) {
    HOME("推荐"),
    NEW("新内容"),
    RADIO("广播"),
    LIBRARY("资料库"),
    NOW_PLAYING("正在播放"),
    SEARCH("搜索"),
}

enum class PlayerViewMode(val label: String) {
    COVER("封面"),
    LYRICS("歌词"),
    QUEUE("队列"),
}

fun activeLyricIndex(lines: List<TimedLyricLine>, positionMs: Long): Int =
    lines.indexOfLast { it.startMs <= positionMs }

enum class ConnectionAction {
    RETRY,
    EDIT_URL,
    USE_EXPERIENCE,
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Editing(val url: String, val validationError: String? = null) : ConnectionState
    data object Checking : ConnectionState
    data class QrReady(
        val transactionId: String,
        val qrUrl: String,
        val expiresIn: Int,
        val scanned: Boolean = false,
    ) : ConnectionState
    data class Error(
        val message: String,
        val actions: List<ConnectionAction> = listOf(
            ConnectionAction.RETRY,
            ConnectionAction.EDIT_URL,
            ConnectionAction.USE_EXPERIENCE,
        ),
    ) : ConnectionState
}
