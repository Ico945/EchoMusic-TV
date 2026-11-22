package com.kutv.music.application.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEqualizerControllerTest {
    @Test
    fun `reapplies selected preset to a replacement session and clears state when reattach fails`() {
        val first = FakeAudioEqualizer()
        val second = FakeAudioEqualizer()
        val controller = AndroidEqualizerController { sessionId ->
            when (sessionId) {
                11 -> Result.success(first)
                22 -> Result.success(second)
                else -> Result.failure(IllegalStateException("no session"))
            }
        }

        controller.attach(11)
        assertTrue(controller.select("bass").isSuccess)
        controller.attach(22)

        assertEquals("bass", controller.activePreset.value)
        assertTrue(second.enabled)
        assertTrue(second.levels.values.any { it != 0.toShort() })
        assertTrue(first.released)

        controller.attach(33)
        assertEquals("off", controller.activePreset.value)
        assertFalse(controller.select("bass").isSuccess)
        controller.release()
        assertTrue(second.released)
    }

    private class FakeAudioEqualizer : AudioEqualizerPort {
        override val numberOfBands: Short = 5
        override val bandLevelRange: ShortArray = shortArrayOf(-1_000, 1_000)
        override var enabled = false
        val levels = mutableMapOf<Short, Short>()
        var released = false
        override fun setBandLevel(band: Short, level: Short) { levels[band] = level }
        override fun release() { released = true }
    }
}
