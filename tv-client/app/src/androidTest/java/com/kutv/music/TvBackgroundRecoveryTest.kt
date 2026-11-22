package com.kutv.music

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.webkit.WebView
import android.widget.Button
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.video.MvPlaybackController
import com.kutv.music.video.TvVideoOverlay
import com.kutv.music.web.TvPageRecoveryPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Home key/task return and actual Chromium termination; no fake lifecycle or bridge. */
@RunWith(AndroidJUnit4::class)
class TvBackgroundRecoveryTest {
    private fun <T> field(activity: MainActivity, name: String): T? =
        @Suppress("UNCHECKED_CAST")
        (MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as? T)

    private fun waitNative(web: ProductionWebViewHarness, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (web.withActivity(predicate)) return
            Thread.sleep(100L)
        }
        error("Native recovery assertion timed out")
    }

    private fun captureNonBlack(web: ProductionWebViewHarness, name: String) {
        assertTrue(web.waitFor("window.__echoTvResumePaint > 0"))
        Thread.sleep(400L)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val colors = mutableSetOf<Int>()
            var lit = 0
            for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                val c = bitmap.getPixel(x, y)
                colors.add(c)
                if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) > 80) lit++
            }
            assertTrue("TV screenshot was black/blank: colors=${colors.size}, lit=$lit", colors.size > 20 && lit > 20)
            File(instrumentation.targetContext.getExternalFilesDir(null), "r52-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun terminateRenderer(web: ProductionWebViewHarness) {
        assertTrue("API 29+ multiprocess WebView required for this recovery test", web.withActivity {
            web.webView.webViewRenderProcess?.terminate() == true
        })
    }

    @Test fun tenHomeReturnsKeepTheSamePageFocusAndLiveWebView() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.click("[data-focus-id=nav-more]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=more]') !== null"))
            val original = web.webView
            web.evaluate("window.__r52PageSentinel = 'keep-me'")
            val route = web.text("location.hash")
            val focus = web.activeFocusId()
            repeat(10) {
                web.backgroundWithHome()
                web.returnFromLauncher()
                assertSame(original, web.webView)
                assertTrue(web.waitFor("window.__echoTvResumePaint > 0"))
                assertEquals("keep-me", web.text("window.__r52PageSentinel"))
                assertEquals(route, web.text("location.hash"))
                assertEquals(focus, web.activeFocusId())
            }
            captureNonBlack(web, "ten-home-returns")
            web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(web.bool("document.activeElement?.dataset?.focusId?.length > 0"))
        }
    }

    @Test fun musicSurvivesHomeReturnAndForegroundRendererLossWithoutControllerReplacement() {
        ProductionWebViewHarness.launch().use { web ->
            waitNative(web) { field<MediaController>(it, "musicController") != null }
            val controller = web.withActivity { field<MediaController>(it, "musicController")!! }
            web.withActivity { activity ->
                controller.setMediaItem(MediaItem.Builder()
                    .setMediaId("r52_local_music")
                    .setUri("android.resource://${activity.packageName}/raw/track_1")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("R52 local recovery fixture").setArtist("Debug test").build())
                    .build())
                controller.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                controller.prepare()
                controller.play()
            }
            waitNative(web) { controller.isPlaying }
            web.backgroundWithHome()
            assertTrue(web.withActivity { controller.isPlaying })
            web.returnFromLauncher()
            val oldView = web.webView
            terminateRenderer(web)
            web.refreshWebView(oldView)
            assertTrue(web.waitFor("document.querySelector('[data-page]') !== null"))
            assertTrue(web.waitFor("document.querySelector('[data-mini-player]')?.textContent.includes('R52 local recovery fixture') === true"))
            assertSame(controller, web.withActivity { field<MediaController>(it, "musicController") })
            assertTrue(web.withActivity { controller.isPlaying && controller.currentMediaItem?.mediaId == "r52_local_music" })
            captureNonBlack(web, "music-renderer-recovery")
        }
    }

    @Test fun rendererLossInBackgroundDefersNewUiUntilTaskReturnsAndBackUsesNewView() {
        ProductionWebViewHarness.launch().use { web ->
            assertTrue(web.click("[data-focus-id=nav-more]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=more]') !== null"))
            val oldView = web.webView
            web.backgroundWithHome()
            terminateRenderer(web)
            waitNative(web) { field<WebView>(it, "webView") == null }
            Thread.sleep(400L)
            assertNull(web.withActivity { field<WebView>(it, "webView") })
            web.returnFromLauncher()
            assertNotSame(oldView, web.webView)
            assertTrue(web.waitFor("document.querySelector('[data-page=more]') !== null"))
            captureNonBlack(web, "background-renderer-recovery")
            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(web.waitFor("document.querySelector('[data-page=home]') !== null"))
        }
    }

    @Test fun stalledFrameWatchdogRebuildsPageInsteadOfLeavingBlackScreen() {
        ProductionWebViewHarness.launch().use { web ->
            val oldView = web.webView
            // Controlled compositor-health failure, not an endless JS loop or account mutation.
            web.evaluate("window.requestAnimationFrame = () => 0")
            web.backgroundWithHome()
            web.returnFromLauncher()
            web.refreshWebView(oldView)
            assertTrue(web.waitFor("document.querySelector('[data-page]') !== null"))
            captureNonBlack(web, "frame-watchdog-recovery")
        }
    }

    @Test fun exhaustedAutomaticRecoveryShowsNativeRemoteRetryInsteadOfBlackSurface() {
        ProductionWebViewHarness.launch().use { web ->
            web.withActivity {
                val recovery = field<TvPageRecoveryPolicy>(it, "pageRecovery")!!
                repeat(3) { assertTrue(recovery.beginRecovery()) }
            }
            val oldView = web.webView
            terminateRenderer(web)
            waitNative(web) { field<WebView>(it, "webView") == null }
            waitNative(web) { it.currentFocus is Button }
            web.withActivity { activity ->
                val button = activity.currentFocus as Button
                assertEquals("重新打开页面", button.text.toString())
                val time = SystemClock.uptimeMillis()
                activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
                activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
            }
            web.refreshWebView(oldView)
            assertTrue(web.waitFor("document.querySelector('[data-page]') !== null"))
            captureNonBlack(web, "native-remote-retry")
        }
    }

    @Test fun mvHomeReturnKeepsPositionPausedAndControlsCanResumeWithRemote() {
        ProductionWebViewHarness.launch(MvHomeTestActivity::class.java).use { web ->
            assertTrue(web.waitFor("document.querySelector('[data-page=home] [data-mv-entry]') !== null"))
            assertTrue(web.click("[data-page=home] [data-mv-entry]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=mv-detail]') !== null"))
            waitNative(web) { field<MvPlaybackController>(it, "mvPlaybackController")?.snapshot()?.optBoolean("playing") == true }
            val original = web.webView
            repeat(3) {
                web.backgroundWithHome()
                val paused = web.withActivity { field<MvPlaybackController>(it, "mvPlaybackController")!!.snapshot() }
                assertTrue(paused.getBoolean("open")); assertFalse(paused.getBoolean("playing"))
                web.returnFromLauncher()
                assertSame(original, web.webView)
                val returned = web.withActivity { field<MvPlaybackController>(it, "mvPlaybackController")!!.snapshot() }
                assertFalse(returned.getBoolean("playing"))
                assertEquals(paused.getLong("positionMs"), returned.getLong("positionMs"))
                assertTrue(web.waitFor("document.querySelector('[data-page=mv-detail]')?.dataset.mvIdle === 'false'"))
                assertTrue(web.withActivity { field<TvVideoOverlay>(it, "videoOverlay")!!.isMvVisible })
                assertTrue(web.focus("[data-focus-id=mv-toggle]"))
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                waitNative(web) { field<MvPlaybackController>(it, "mvPlaybackController")?.snapshot()?.optBoolean("playing") == true }
            }
            captureNonBlack(web, "mv-home-return")
        }
    }
}
