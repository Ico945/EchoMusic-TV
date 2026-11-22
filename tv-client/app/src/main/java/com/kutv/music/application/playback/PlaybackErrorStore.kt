package com.kutv.music.application.playback

import com.kutv.music.player.PlaybackQueueAction
import com.kutv.music.player.PlaybackQueueError
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackErrorState(
    val errorRevision: Long = 0L,
    val error: PlayerCommandError? = null,
)

data class PlaybackErrorClearResult(
    val accepted: Boolean,
    val errorRevision: Long,
)

class MonotonicErrorIdentity(initialValue: Long = 0L) {
    private val value = AtomicLong(initialValue)

    fun next(): Long = value.incrementAndGet().also { check(it > 0L) }
}

class PlaybackErrorStore(
    private val identity: MonotonicErrorIdentity,
) {
    private val mutableState = MutableStateFlow(PlaybackErrorState())
    val state: StateFlow<PlaybackErrorState> = mutableState.asStateFlow()

    @Synchronized
    internal fun report(descriptor: PlayerErrorDescriptor): PlayerCommandError {
        val error = PlayerCommandError(
            code = descriptor.code,
            message = descriptor.message,
            retryable = descriptor.retryable,
            errorId = identity.next(),
        )
        mutableState.value = PlaybackErrorState(error.errorId, error)
        return error
    }

    @Synchronized
    fun reportQueue(error: PlaybackQueueError): PlayerCommandError? {
        val descriptor = when (error.action) {
            PlaybackQueueAction.START -> PlayerErrorDescriptor(
                code = "PLAYBACK_RESOLVE_FAILED",
                message = "当前歌曲暂时无法播放",
                retryable = true,
            )
            PlaybackQueueAction.RESTORE -> PlayerErrorDescriptor(
                code = "PLAYBACK_RESTORE_FAILED",
                message = "上次播放暂时无法恢复",
                retryable = true,
            )
            PlaybackQueueAction.NEXT -> PlayerErrorDescriptor(
                code = "PLAYBACK_NEXT_FAILED",
                message = "下一首暂时无法播放",
                retryable = true,
            )
            PlaybackQueueAction.PREVIOUS -> PlayerErrorDescriptor(
                code = "PLAYBACK_PREVIOUS_FAILED",
                message = "上一首暂时无法播放",
                retryable = true,
            )
            PlaybackQueueAction.QUALITY -> PlayerErrorDescriptor(
                code = "PLAYBACK_QUALITY_FAILED",
                message = "音质切换失败，已保留原音源",
                retryable = true,
            )
            PlaybackQueueAction.PREFETCH -> return null
        }
        val title = error.trackTitle?.filterNot(Char::isISOControl)?.take(80)?.takeIf(String::isNotBlank)
        return report(if (title == null) descriptor else descriptor.copy(message = "$title：${descriptor.message}"))
    }

    @Synchronized
    fun clear(expectedErrorId: Long): PlaybackErrorClearResult {
        val current = mutableState.value
        val currentError = current.error
        if (currentError == null) return PlaybackErrorClearResult(true, current.errorRevision)
        if (currentError.errorId != expectedErrorId) {
            return PlaybackErrorClearResult(false, current.errorRevision)
        }
        val revision = identity.next()
        mutableState.value = PlaybackErrorState(errorRevision = revision, error = null)
        return PlaybackErrorClearResult(true, revision)
    }
}

object PlaybackErrorRuntime {
    private val identity = MonotonicErrorIdentity()
    val store = PlaybackErrorStore(identity)

    internal fun nextErrorId(): Long = identity.next()

    fun nextAppError(
        code: String,
        message: String,
        retryable: Boolean,
    ): PlayerCommandError = PlayerCommandError(code, message, retryable, nextErrorId())
}
