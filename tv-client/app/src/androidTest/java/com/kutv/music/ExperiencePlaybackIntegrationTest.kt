package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kutv.music.player.PlaybackQueueRuntime
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperiencePlaybackIntegrationTest {
    @Test
    fun productionWebViewPlayEntryReachesTheServiceQueueBridge() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=hero-play]') !== null"))
            assertTrue(web.click("[data-focus-id=hero-play]"))
            val deadline = android.os.SystemClock.uptimeMillis() + 8_000L
            while (PlaybackQueueRuntime.currentSnapshot().currentIndex != 0 && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50L)
            assertTrue(PlaybackQueueRuntime.currentSnapshot().queue.isNotEmpty())
            assertTrue(web.bool("document.querySelector('[data-page=home]') !== null"))
        }
    }
}
