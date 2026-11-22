package com.kutv.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackLogMessageTest {
    @Test
    fun `queue failure log contains only a fixed code and action`() {
        val message = PlaybackLogMessage.queueFailure(PlaybackQueueAction.START)

        assertEquals(
            "playback_queue_error action=START code=PLAYBACK_QUEUE_START_FAILED",
            message,
        )
        listOf("track", "hash", "url", "token", "signature", "exception").forEach { forbidden ->
            assertFalse(message.contains(forbidden, ignoreCase = true))
        }
    }
}
