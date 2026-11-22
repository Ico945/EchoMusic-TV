package com.kutv.music.player

internal object PlaybackLogMessage {
    fun queueFailure(action: PlaybackQueueAction): String =
        "playback_queue_error action=${action.name} code=PLAYBACK_QUEUE_${action.name}_FAILED"
}
