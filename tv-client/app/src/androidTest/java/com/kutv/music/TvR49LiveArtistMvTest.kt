package com.kutv.music

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.video.TvVideoOverlay
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public Eason artist -> real MV -> ExoPlayer. No mocks or account writes. */
class TvR49LiveArtistMvTest {
    @Test fun threeEasonMvsRenderAdvanceAndReturnToTheirSource() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvLiveKugouMv") == "true")
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".debug"))
        ProductionWebViewHarness.launch().use { web ->
            web.evaluate("location.hash='#/artist/artist:420'")
            assertTrue("Artist did not load", web.waitFor("document.querySelector('[data-focus-id=artist-tab-mv]') !== null", 20_000))
            assertTrue(web.focus("[data-focus-id=artist-tab-mv]"))
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue("Public MV list did not load", web.waitFor("document.querySelectorAll('[data-focus-group=artist-mv]').length>=3", 20_000))
            val overlay = web.withActivity {
                checkNotNull(it.window.decorView.findViewWithTag<TvVideoOverlay>(TvVideoOverlay.ROOT_TAG))
            }
            val player = web.withActivity { overlay.playerView.player as ExoPlayer }
            repeat(3) { index ->
                val source = "artist-mv-$index"
                val firstFrame = CountDownLatch(1)
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.countDown() }
                }
                web.withActivity { player.addListener(listener) }
                try {
                    assertTrue(web.focus("[data-focus-id=$source]"))
                    web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                    assertTrue(web.waitFor("document.querySelector('[data-page=mv-detail]')!==null"))
                    val rendered = firstFrame.await(35, TimeUnit.SECONDS)
                    val diagnostic = web.withActivity { "state=${player.playbackState};error=${player.playerError?.errorCode}" } +
                        web.text("document.querySelector('[role=alert]')?.textContent || ''")
                    assertTrue("MV $index no real first frame: $diagnostic", rendered)
                    assertTrue("MV $index did not advance with video and audio tracks", waitNative(web) {
                        player.isPlaying && player.currentPosition >= 1500 &&
                            player.videoSize.width > 0 && player.audioFormat != null
                    })
                    if (index == 0) {
                        assertTrue("Real MV controls must auto hide", web.waitFor(
                            "document.querySelector('[data-page=mv-detail]')?.dataset.mvIdle==='true'", 8000))
                        assertTrue(web.bool("document.activeElement?.dataset.page==='mv-detail'"))
                        assertTrue("MV chrome did not finish fading", web.waitFor(
                            "getComputedStyle(document.querySelector('[data-page=mv-detail] header')).opacity==='0' && getComputedStyle(document.querySelector('.mv-control-layer')).visibility==='hidden'"))
                        // Screenshot after the composited fade, not the old
                        // frame immediately preceding the DOM idle attribute.
                        SystemClock.sleep(300)
                        val idleInstrumentation = InstrumentationRegistry.getInstrumentation()
                        val idleBitmap = checkNotNull(idleInstrumentation.uiAutomation.takeScreenshot())
                        File(idleInstrumentation.targetContext.getExternalFilesDir(null), "r50-mv-idle.png").outputStream().use {
                            assertTrue(idleBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        idleBitmap.recycle()
                        val beforeWake = web.withActivity { player.currentPosition }
                        web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                        assertTrue(web.waitFor("document.querySelector('[data-page=mv-detail]')?.dataset.mvIdle==='false'"))
                        assertTrue("Wake direction must not seek", web.withActivity { player.isPlaying && player.currentPosition < beforeWake + 8000 })
                    }
                    if (web.bool("document.querySelector('[data-page=mv-detail]')?.dataset.mvIdle==='true'")) {
                        web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
                    }
                    assertTrue("No actual quality control", web.waitFor("document.querySelector('.mv-actions button.active:not([disabled])')!==null"))
                    val quality = web.text("document.querySelector('.mv-actions button.active').textContent")
                    val metrics = web.withActivity { "width=${player.videoSize.width};height=${player.videoSize.height};position=${player.currentPosition};audio=${player.audioFormat?.sampleMimeType}" }
                    android.util.Log.i("R49LiveMv", "sample=$index;quality=$quality;$metrics;firstFrame=true")
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    File(instrumentation.targetContext.getExternalFilesDir(null), "r49-eason-mv-$index.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    bitmap.recycle()
                    if (index == 1) {
                        // The second public sample advertises FHD but no HD.
                        // Verify an actual source switch, not just menu rendering.
                        val switchedFrame = CountDownLatch(1)
                        val switchListener = object : Player.Listener {
                            override fun onRenderedFirstFrame() { switchedFrame.countDown() }
                        }
                        web.withActivity { player.addListener(switchListener) }
                        try {
                            assertTrue(web.focus("[data-focus-id=mv-quality-fhd]"))
                            web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                            assertTrue("FHD did not render", switchedFrame.await(35, TimeUnit.SECONDS))
                            assertTrue("Actual quality was not reflected", web.waitFor(
                                "document.querySelector('[data-focus-id=mv-quality-fhd]')?.classList.contains('active') && !document.querySelector('[data-focus-id=mv-quality-fhd]')?.disabled"))
                            assertTrue("Quality switch lost playback", waitNative(web) { player.isPlaying && player.currentPosition >= 1500 })
                            android.util.Log.i("R49LiveMv", "sample=$index;switchTo=FHD;firstFrame=true")
                        } finally {
                            web.withActivity { player.removeListener(switchListener) }
                        }
                    }
                    if (web.bool("document.querySelector('[data-page=mv-detail]')?.dataset.mvIdle==='true'")) {
                        web.dispatchKey(KeyEvent.KEYCODE_DPAD_UP)
                    }
                    assertTrue(web.focus("[data-focus-id=mv-toggle]"))
                    web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                    assertTrue("Pause failed", waitNative(web) { !player.isPlaying })
                    val pausedAt = web.withActivity { player.currentPosition }
                    assertTrue(web.focus("[data-focus-id=mv-progress]"))
                    web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                    assertTrue("Seek failed", waitNative(web) { player.currentPosition >= pausedAt + 8000 })
                    assertTrue(web.focus("[data-focus-id=mv-toggle]"))
                    web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                    assertTrue("Resume failed", waitNative(web) { player.isPlaying })
                    web.dispatchKey(KeyEvent.KEYCODE_BACK)
                    assertTrue("Return focus failed for MV $index", web.waitFor(
                        "document.querySelector('[data-page=artist-detail]')!==null && document.activeElement?.dataset.focusId===${JSONObject.quote(source)}", 15_000))
                    assertTrue(web.withActivity { !overlay.isMvVisible && !player.isPlaying && player.mediaItemCount == 0 })
                } finally {
                    web.withActivity { player.removeListener(listener) }
                }
            }
        }
    }

    private fun waitNative(web: ProductionWebViewHarness, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (web.withActivity { condition() }) return true
            SystemClock.sleep(50)
        }
        return false
    }
}
