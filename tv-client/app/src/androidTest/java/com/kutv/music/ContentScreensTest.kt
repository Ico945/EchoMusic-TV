package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kutv.music.player.PlaybackQueueRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentScreensTest {
    @Test
    fun productionHeroSubmitsTheCompleteNativeHomeQueue() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.waitFor("document.querySelector('[data-focus-id=hero-play]') !== null"))
            assertTrue(web.click("[data-focus-id=hero-play]"))
            val deadline = android.os.SystemClock.uptimeMillis() + 8_000L
            while (PlaybackQueueRuntime.currentSnapshot().queue.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50L)
            val snapshot = PlaybackQueueRuntime.currentSnapshot()
            assertTrue(snapshot.queue.isNotEmpty())
            assertEquals(0, snapshot.currentIndex)
        }
    }

    @Test
    fun currentContentDestinationsRenderTheirProductionWebViewSurfaces() {
        ProductionWebViewHarness.launch().use { web ->
            mapOf("explore" to "发现", "fm" to "私人 FM", "search" to "搜索").forEach { (page, title) ->
                assertTrue(web.click("[data-focus-id=nav-$page]"))
                assertTrue(web.waitFor("document.querySelector('[data-page=$page]') !== null"))
                assertTrue(web.bool("document.querySelector('[data-page=$page]')?.textContent.includes(${org.json.JSONObject.quote(title)})"))
            }
        }
    }
}
