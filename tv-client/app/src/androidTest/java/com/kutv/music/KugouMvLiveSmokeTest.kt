package com.kutv.music

import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.direct.*
import com.kutv.music.video.KugouMvStreamResolver
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kutv.music.video.TvVideoOverlay
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real public MV diagnostics, opt-in; never logs identifiers, URLs or credentials. */
class KugouMvLiveSmokeTest {
    @Test fun productionMvRendersAdvancesAndReturnsToSourceWithDpad() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvLiveKugouMv") == "true")
        ProductionWebViewHarness.launch().use { web ->
            assertTrue("Public home has no real MV entry", web.waitFor(
                "document.querySelector('[data-page=home] [data-mv-entry]') !== null", 15_000))
            val source = web.text("document.querySelector('[data-page=home] [data-mv-entry]').dataset.focusId")
            val overlay = web.withActivity {
                checkNotNull(it.window.decorView.findViewWithTag<TvVideoOverlay>(TvVideoOverlay.ROOT_TAG))
            }
            val player = web.withActivity { overlay.playerView.player as ExoPlayer }
            val firstFrame = CountDownLatch(1)
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame.countDown() }
            }
            web.withActivity { player.addListener(listener) }
            try {
                assertTrue(web.focus("[data-focus-id=${JSONObject.quote(source)}]"))
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue(web.waitFor("document.querySelector('[data-page=mv-detail]') !== null"))
                val rendered = firstFrame.await(30, TimeUnit.SECONDS)
                val diagnostic = web.withActivity { "state=${player.playbackState};playerError=${player.playerError?.errorCode}" }
                assertTrue("No real MV first frame: $diagnostic", rendered)
                assertTrue("Real MV did not advance with video dimensions", waitNative(web) {
                    player.isPlaying && player.currentPosition >= 1500 && player.videoSize.width > 0
                })
                val backgroundReturn = InstrumentationRegistry.getArguments().getString("kutvBackgroundReturn") == "true"
                if (backgroundReturn) repeat(3) {
                    val originalView = web.webView
                    web.backgroundWithHome()
                    assertTrue("Background MV did not pause", web.withActivity { !player.isPlaying })
                    val position = web.withActivity { player.currentPosition }
                    web.returnFromLauncher()
                    assertSame("Ordinary foreground return replaced the UI", originalView, web.webView)
                    assertTrue("Returned MV controls not visible", web.waitFor(
                        "document.querySelector('[data-page=mv-detail]')?.dataset.mvIdle === 'false'"))
                    assertTrue("MV position was lost on return", web.withActivity {
                        !player.isPlaying && player.currentPosition == position && overlay.isMvVisible
                    })
                    assertTrue(web.focus("[data-focus-id=mv-toggle]"))
                    web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                    assertTrue("Real MV did not continue after Home", waitNative(web) {
                        player.isPlaying && player.currentPosition > position + 1000 && player.videoSize.width > 0
                    })
                }
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                assertNotNull("MV evidence screenshot unavailable", bitmap)
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val screenshotName = if (backgroundReturn) "r52-live-mv-home-return.png" else "kutv-r9-live-mv.png"
                File(context.getExternalFilesDir(null), screenshotName).outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                if (backgroundReturn) {
                    val videoColors = mutableSetOf<Int>()
                    // Sample the center video region, excluding the header and control bar.
                    for (y in bitmap.height / 3 until bitmap.height * 2 / 3 step 8) {
                        for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 8) {
                            videoColors.add(bitmap.getPixel(x, y))
                        }
                    }
                    assertTrue("Returned MV surface was blank despite advancing media", videoColors.size > 30)
                }
                bitmap.recycle()

                assertTrue(web.focus("[data-focus-id=mv-toggle]"))
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue("D-Pad pause failed", waitNative(web) { !player.isPlaying })
                val pausedAt = web.withActivity { player.currentPosition }
                assertTrue(web.focus("[data-focus-id=mv-progress]"))
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                assertTrue("D-Pad seek failed", waitNative(web) { player.currentPosition >= pausedAt + 8000 })
                assertTrue(web.focus("[data-focus-id=mv-toggle]"))
                web.dispatchKey(KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue("D-Pad resume failed", waitNative(web) { player.isPlaying })
                web.dispatchKey(KeyEvent.KEYCODE_BACK)
                assertTrue("Back did not restore the source MV entry", web.waitFor(
                    "document.querySelector('[data-page=home]') !== null && document.activeElement?.dataset.focusId === ${JSONObject.quote(source)}"))
                assertTrue("Native MV retained playback after Back", web.withActivity {
                    !overlay.isMvVisible && !player.isPlaying && player.mediaItemCount == 0
                })
            } finally {
                web.withActivity { player.removeListener(listener) }
            }
        }
    }

    private fun waitNative(web: ProductionWebViewHarness, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (web.withActivity { condition() }) return true
            SystemClock.sleep(50)
        }
        return false
    }

    @Test fun publicHomeMvResolvesThroughProductionProtocol() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvLiveKugouMv") == "true")
        AppGraph.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val trace = mutableListOf<String>()
        val transport = OkHttpKugouTransport()
        val client = KugouHttpClient(AppGraph.deviceStore::getOrCreate, { null },
            transport = KugouTransport { request ->
                val response = transport.execute(request)
                val body = runCatching { JSONObject(response.body) }.getOrNull()
                trace += "${java.net.URI(request.url).path}:http=${response.statusCode};${shape(body, 0)}"
                response
            })
        val feed = KugouDirectApi(client, { null }).home()
        val id = (feed.newSongs + feed.daily).firstNotNullOfOrNull { it.mvId }
        assertNotNull("No live MV available on public home", id)
        val resolver = KugouMvStreamResolver(client, AppGraph.deviceStore::getOrCreate, { null })
        val result = resolver.resolve(id!!, "hd")
        val failure = result.exceptionOrNull()
        val category = (failure as? KugouNetworkException)?.kind?.name ?: failure?.javaClass?.simpleName
        assertTrue("MV resolution failed: category=$category; ${trace.joinToString("; ")}", result.isSuccess)
        val stream = result.getOrThrow()
        assertTrue(KugouDomainPolicy.isAllowedMediaUrl(stream.url))
        assertEquals("video/mp4", stream.mimeType)
        assertTrue("Resolved tier must be one advertised by the video", stream.quality in stream.availableQualities)
        assertTrue(stream.availableQualities.all { it in setOf("fhd", "hd", "qhd", "sd", "ld") })
    }

    private fun shape(value: Any?, depth: Int): String = when {
        depth > 5 -> "..."
        value is JSONObject -> value.keys().asSequence().take(25).joinToString(prefix = "{", postfix = "}") {
            "${if (it.matches(Regex("[a-fA-F0-9]{32}"))) "hash-key" else it}:${shape(value.opt(it), depth + 1)}"
        }
        value is JSONArray -> "[${value.length()};${shape(value.opt(0), depth + 1)}]"
        value is String -> if (value.startsWith("http"))
            "string:${runCatching { java.net.URI(value).scheme }.getOrNull()}:mediaAllowed=${KugouDomainPolicy.isAllowedMediaUrl(value)}" else "string"
        value is Number -> "number"
        value is Boolean -> "boolean"
        else -> "null"
    }
}
