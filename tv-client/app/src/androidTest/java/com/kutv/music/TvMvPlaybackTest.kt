package com.kutv.music

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.View
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.video.TvVideoOverlay
import com.kutv.music.video.MvPlaybackController
import com.kutv.music.video.MvStreamResolver
import com.kutv.music.video.PlaybackOwner
import com.kutv.music.video.PlaybackOwnership
import com.kutv.music.video.ResolvedMvStream
import com.kutv.music.video.MvRouteGate
import com.kutv.music.bridge.BridgeRouter
import com.kutv.music.bridge.handlers.VideoBridgeHandler
import com.kutv.music.web.TvWebMessageBridge
import com.kutv.music.web.TvWebViewHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.lifecycleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMvPlaybackTest {
    @Test
    fun productionHomeMvCardEntersControlsAndBackRestoresTheRealSourceCard() {
        ProductionWebViewHarness.launch(MvHomeTestActivity::class.java).use { web ->
            val mvCardReady = web.waitFor("document.querySelector('[data-page=home] [data-mv-entry]') !== null")
            assertTrue(
                "page=${web.text("document.querySelector('[data-page]')?.dataset?.page || ''")}," +
                    " cards=${web.evaluate("document.querySelectorAll('[data-page=home] .music-card').length")}," +
                    " mvCards=${web.evaluate("document.querySelectorAll('[data-page=home] [data-mv-entry]').length")}",
                mvCardReady,
            )
            val sourceFocusId = web.text(
                "document.querySelector('[data-page=home] [data-mv-entry]')?.dataset?.focusId || ''",
            )
            assertTrue(sourceFocusId.isNotBlank())
            assertTrue(web.focus("[data-focus-id=${org.json.JSONObject.quote(sourceFocusId)}]"))
            assertTrue(web.click("[data-focus-id=${org.json.JSONObject.quote(sourceFocusId)}]"))
            assertTrue(web.waitFor("document.querySelector('[data-page=mv-detail]') !== null"))
            assertTrue(web.waitFor("document.activeElement?.dataset?.focusId === 'mv-progress'"))

            web.dispatchKey(KeyEvent.KEYCODE_DPAD_DOWN)
            val dpadMoved = web.waitFor("document.activeElement?.dataset?.focusId !== 'mv-progress'", 2_000L)
            assertTrue("activeFocus=${web.activeFocusId()}", dpadMoved)
            assertTrue(web.activeFocusId().startsWith("mv-"))

            web.dispatchKey(KeyEvent.KEYCODE_BACK)
            assertTrue(
                web.waitFor(
                    "document.querySelector('[data-page=home]') !== null && " +
                        "document.activeElement?.dataset?.focusId === ${org.json.JSONObject.quote(sourceFocusId)}",
                ),
            )
        }
    }

    @Test
    fun processResolverSlotStaysBusyAcrossProductionActivityRecreateAndRecoversAfterRelease() {
        BlockingMvResolverProbe.reset()
        try {
            ProductionWebViewHarness.launch(BlockingResolverMainActivity::class.java).use { web ->
                installVideoOpenProbe(web)
                openFirstHomeMv(web)
                assertTrue(
                    "openResult=${web.text("window.__playbackGateOpenResult || ''")}",
                    BlockingMvResolverProbe.awaitStarts(1, 5_000L),
                )
                assertTrue(AppGraph.mvResolverAdmission.isBusy)

                web.recreate()
                assertTrue(web.waitFor("document.querySelector('[data-page=home] [data-mv-entry]') !== null"))
                installVideoOpenProbe(web)
                openFirstHomeMv(web)
                assertTrue(web.waitFor("window.__playbackGateOpenResult !== null", 2_000L))
                assertEquals("MV_RESOLVER_BUSY", web.text("window.__playbackGateOpenResult"))
                assertFalse(BlockingMvResolverProbe.awaitStarts(2, 750L))
                assertEquals(1, BlockingMvResolverProbe.starts)
                assertTrue(AppGraph.mvResolverAdmission.isBusy)

                BlockingMvResolverProbe.release()
                assertTrue(waitForCondition(5_000L) { !AppGraph.mvResolverAdmission.isBusy })
                web.dispatchKey(KeyEvent.KEYCODE_BACK)
                assertTrue(web.waitFor("document.querySelector('[data-page=home] [data-mv-entry]') !== null"))

                openFirstHomeMv(web)
                assertTrue(BlockingMvResolverProbe.awaitStarts(2, 5_000L))
                assertTrue(waitForCondition(5_000L) { !AppGraph.mvResolverAdmission.isBusy })
                assertTrue(web.waitFor("document.querySelector('[data-focus-id=mv-quality-hd]:not([disabled])') !== null"))
            }
        } finally {
            BlockingMvResolverProbe.release()
            waitForCondition(5_000L) { !AppGraph.mvResolverAdmission.isBusy }
        }
    }

    @Test
    fun playerViewStaysBelowTheTransparentWebControlLayerOnlyWhileMvIsVisible() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val player = ExoPlayer.Builder(activity).build()
                val playerView = PlayerView(activity).apply { this.player = player }
                val webView = WebView(activity)
                val overlay = TvVideoOverlay(activity, playerView, webView)
                activity.setContentView(overlay)

                try {
                    assertEquals(playerView, overlay.getChildAt(0))
                    assertEquals(webView, overlay.getChildAt(1))
                    assertEquals(View.INVISIBLE, playerView.visibility)
                    assertEquals(TvVideoOverlay.WEB_BACKGROUND, overlay.webBackgroundColor)

                    overlay.showMv()
                    assertEquals(View.VISIBLE, playerView.visibility)
                    assertEquals(Color.TRANSPARENT, overlay.webBackgroundColor)

                    overlay.hideMv()
                    assertEquals(View.INVISIBLE, playerView.visibility)
                    assertEquals(TvVideoOverlay.WEB_BACKGROUND, overlay.webBackgroundColor)
                } finally {
                    overlay.removeAllViews()
                    webView.destroy()
                    player.release()
                }
            }
        }
    }

    @Test
    fun hardwareDpadNavigatesTheWebControlLayer() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val ready = CountDownLatch(1)
            val windowFocused = CountDownLatch(1)
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus) windowFocused.countDown()
                }
                if (activity.hasWindowFocus()) windowFocused.countDown()
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundColor(Color.BLACK)
                    loadDataWithBaseURL(
                        "https://appassets.androidplatform.net",
                        """<div style='display:flex;gap:80px'>
                          <button id='left'>left</button><button id='right' autofocus>right</button>
                        </div><script>
                          window.addEventListener('load', () => { document.getElementById('right').focus(); AndroidReady.ready(); });
                        </script>""",
                        "text/html",
                        "UTF-8",
                        null,
                    )
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface fun ready() = ready.countDown()
                    }, "AndroidReady")
                }
                activity.setContentView(FrameLayout(activity).apply { addView(webView) })
                webView.requestFocus()
            }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertTrue(windowFocused.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertTrue(webView.requestFocus())
                    assertTrue(webView.hasFocus())
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                scenario.onActivity { activity ->
                    val downTime = SystemClock.uptimeMillis()
                    val down = KeyEvent(
                        downTime,
                        downTime,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        0,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        KeyEvent.FLAG_FROM_SYSTEM,
                        InputDevice.SOURCE_DPAD,
                    )
                    val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
                    activity.dispatchKeyEvent(down)
                    activity.dispatchKeyEvent(up)
                }
                val observed = CountDownLatch(1)
                var key = ""
                scenario.onActivity {
                    webView.evaluateJavascript("document.activeElement && document.activeElement.id") { value ->
                        key = value
                        observed.countDown()
                    }
                }
                assertTrue(observed.await(5, TimeUnit.SECONDS))
                assertEquals("\"left\"", key)
            } finally {
                scenario.onActivity {
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
            }
        }
    }

    @Test
    fun hardwareBackClosesMainActivityMvBeforeReturningControlToVue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ownership = PlaybackOwnership()
        lateinit var overlay: TvVideoOverlay
        lateinit var controller: MvPlaybackController

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val overlayField = MainActivity::class.java.getDeclaredField("videoOverlay").apply { isAccessible = true }
                overlay = overlayField.get(activity) as TvVideoOverlay
                val controllerField = MainActivity::class.java.getDeclaredField("mvPlaybackController").apply { isAccessible = true }
                (controllerField.get(activity) as MvPlaybackController).destroy()
                val player = ExoPlayer.Builder(activity).build()
                controller = MvPlaybackController(
                    resolver = MvStreamResolver { _, quality ->
                        Result.success(
                            ResolvedMvStream(
                                "android.resource://${activity.packageName}/raw/track_1",
                                "video/mp4",
                                quality,
                            ),
                        )
                    },
                    player = player,
                    ownership = ownership,
                    onVisibilityChanged = { visible -> if (visible) overlay.showMv() else overlay.hideMv() },
                )
                controllerField.set(activity, controller)
                assertTrue(runBlocking { controller.open("mv:42", "hd") }.getBoolean("accepted"))
                assertTrue(overlay.isMvVisible)
                assertEquals(PlaybackOwner.MV, ownership.value)
            }

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()

            scenario.onActivity {
                assertFalse(overlay.isMvVisible)
                assertFalse(controller.isOpen)
                assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
            }
        }
    }

    @Test
    fun hardwareBackInvalidatesPendingOpenBeforeLateResolverCanShowSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolution = CompletableDeferred<Result<ResolvedMvStream>>()
        val resolverStarted = CountDownLatch(1)
        val openingFinished = CountDownLatch(1)
        val ownership = PlaybackOwnership()
        lateinit var overlay: TvVideoOverlay
        lateinit var controller: MvPlaybackController
        var resultCode = ""

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                overlay = MainActivity::class.java.getDeclaredField("videoOverlay").apply { isAccessible = true }.get(activity) as TvVideoOverlay
                val controllerField = MainActivity::class.java.getDeclaredField("mvPlaybackController").apply { isAccessible = true }
                (controllerField.get(activity) as MvPlaybackController).destroy()
                controller = MvPlaybackController(
                    resolver = MvStreamResolver { _, _ -> resolverStarted.countDown(); resolution.await() },
                    player = ExoPlayer.Builder(activity).build(),
                    ownership = ownership,
                    onVisibilityChanged = { visible -> if (visible) overlay.showMv() else overlay.hideMv() },
                )
                controllerField.set(activity, controller)
                activity.lifecycleScope.launch {
                    resultCode = controller.open("mv:late", "hd").getString("errorCode")
                    openingFinished.countDown()
                }
            }
            assertTrue(resolverStarted.await(5, TimeUnit.SECONDS))

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            resolution.complete(
                Result.success(
                    ResolvedMvStream(
                        "android.resource://${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/raw/track_1",
                        "video/mp4",
                        "hd",
                    ),
                ),
            )
            assertTrue(openingFinished.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            scenario.onActivity {
                assertEquals("MV_OPERATION_SUPERSEDED", resultCode)
                assertFalse(overlay.isMvVisible)
                assertFalse(controller.isOpen)
                assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
            }
        }
    }

    @Test
    fun asynchronousMedia3ErrorHidesSurfaceAndReturnsMusicOwnership() {
        val ownership = PlaybackOwnership()
        lateinit var overlay: TvVideoOverlay
        lateinit var controller: MvPlaybackController
        lateinit var testWebView: WebView

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val player = ExoPlayer.Builder(activity).build()
                testWebView = WebView(activity)
                overlay = TvVideoOverlay(activity, PlayerView(activity).apply { this.player = player }, testWebView)
                activity.setContentView(overlay)
                controller = MvPlaybackController(
                    resolver = MvStreamResolver { _, quality ->
                        Result.success(
                            ResolvedMvStream(
                                "android.resource://${activity.packageName}/raw/missing_mv_fixture",
                                "video/mp4",
                                quality,
                            ),
                        )
                    },
                    player = player,
                    ownership = ownership,
                    onVisibilityChanged = { visible -> if (visible) overlay.showMv() else overlay.hideMv() },
                )
                assertTrue(runBlocking { controller.open("mv:error", "hd") }.getBoolean("accepted"))
            }

            try {
                assertTrue(waitUntil(scenario) {
                    !controller.isOpen &&
                        !overlay.isMvVisible &&
                        ownership.value == PlaybackOwner.MUSIC_SERVICE &&
                        controller.snapshot().optString("errorCode") == "MV_PLAYBACK_FAILED"
                })
            } finally {
                scenario.onActivity {
                    controller.destroy()
                    overlay.removeAllViews()
                    testWebView.destroy()
                }
            }
        }
    }

    @Test
    fun realWebMessageBridgeReturnsCloseWhileOpenResolverIsStillPending() {
        val resolverStarted = CountDownLatch(1)
        val releaseBlockingPrefix = CountDownLatch(1)
        val resolution = CompletableDeferred<Result<ResolvedMvStream>>()
        val probeReady = CountDownLatch(1)
        val responses = ConcurrentHashMap<String, MutableList<String>>()
        val ownership = PlaybackOwnership()
        lateinit var controller: MvPlaybackController
        lateinit var webView: WebView

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                controller = MvPlaybackController(
                    resolver = MvStreamResolver { _, quality ->
                        resolverStarted.countDown()
                        check(releaseBlockingPrefix.await(5, TimeUnit.SECONDS))
                        resolution.await()
                    },
                    player = ExoPlayer.Builder(activity).build(),
                    ownership = ownership,
                )
                val gate = MvRouteGate(controller::close).apply {
                    observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3Apending")
                }
                val router = BridgeRouter(
                    VideoBridgeHandler({ controller }, gate, activity.lifecycleScope).handlers(),
                )
                webView = TvWebViewHost.createForBridge(
                    context = activity,
                    installListener = { TvWebMessageBridge.install(it, activity.lifecycleScope, router) },
                    loadPage = { target ->
                        target.addJavascriptInterface(object {
                            @JavascriptInterface
                            fun response(raw: String) {
                                val id = runCatching { org.json.JSONObject(raw).getString("id") }.getOrDefault("invalid")
                                responses.computeIfAbsent(id) { mutableListOf() }.add(raw)
                            }
                        }, "AndroidProbe")
                        TvWebViewHost.load(target)
                    },
                )
                activity.setContentView(webView)
            }
            assertTrue(waitUntil(scenario, timeoutMs = 10_000L) { webView.progress == 100 })
            scenario.onActivity {
                webView.evaluateJavascript(
                    "EchoTvNative.onmessage = (event) => AndroidProbe.response(event.data); true",
                ) { probeReady.countDown() }
            }
            assertTrue(probeReady.await(2, TimeUnit.SECONDS))

            scenario.onActivity {
                webView.evaluateJavascript("EchoTvNative.postMessage(JSON.stringify({version:1,type:'request',id:'open',method:'video.open',payload:{mvId:'mv:pending',quality:'hd'}}))", null)
            }
            assertTrue(resolverStarted.await(5, TimeUnit.SECONDS))
            assertTrue(waitForResponse(responses, "open", 1_000L))

            val closeStarted = SystemClock.uptimeMillis()
            scenario.onActivity {
                webView.evaluateJavascript("EchoTvNative.postMessage(JSON.stringify({version:1,type:'request',id:'close',method:'video.close',payload:{}}))", null)
            }
            assertTrue(waitForResponse(responses, "close", 2_000L))
            assertTrue(SystemClock.uptimeMillis() - closeStarted < 2_000L)
            releaseBlockingPrefix.countDown()
            resolution.complete(
                Result.success(ResolvedMvStream("https://media.example.invalid/late.mp4", "video/mp4", "hd")),
            )
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity {
                assertEquals(1, responses.getValue("open").size)
                assertEquals(1, responses.getValue("close").size)
                assertTrue(org.json.JSONObject(responses.getValue("open").single()).getJSONObject("payload").getBoolean("pending"))
                assertFalse(controller.isOpen)
                assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
                controller.destroy()
                TvWebViewHost.dispose(webView)
            }
        }
    }

    @Test
    fun productionWebViewHashRouteLeaveAndMvActivityRecreateUseFreshNativeHost() {
        lateinit var oldWebView: WebView
        lateinit var oldController: MvPlaybackController
        lateinit var oldOverlay: TvVideoOverlay
        val oldOwnership = PlaybackOwnership()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(waitUntil(scenario, timeoutMs = 10_000L) { activity ->
                val candidate = MainActivity::class.java.getDeclaredField("webView")
                    .apply { isAccessible = true }
                    .get(activity) as? WebView
                if (candidate != null && candidate.progress == 100) oldWebView = candidate
                candidate != null && candidate.progress == 100
            })
            scenario.onActivity {
                oldWebView.evaluateJavascript("window.location.hash = '#/mv/mv%3A42'", null)
            }
            assertTrue(waitUntil(scenario) { activity ->
                val gate = MainActivity::class.java.getDeclaredField("mvRouteGate")
                    .apply { isAccessible = true }
                    .get(activity) as MvRouteGate
                gate.allowsMvOpen
            })
            scenario.onActivity { activity ->
                val controllerField = MainActivity::class.java.getDeclaredField("mvPlaybackController").apply { isAccessible = true }
                (controllerField.get(activity) as MvPlaybackController).destroy()
                oldOverlay = MainActivity::class.java.getDeclaredField("videoOverlay")
                    .apply { isAccessible = true }
                    .get(activity) as TvVideoOverlay
                val player = ExoPlayer.Builder(activity).build()
                (oldOverlay.getChildAt(0) as PlayerView).player = player
                oldController = MvPlaybackController(
                    resolver = MvStreamResolver { _, quality ->
                        Result.success(ResolvedMvStream(
                            "android.resource://${activity.packageName}/raw/track_1",
                            "video/mp4",
                            quality,
                        ))
                    },
                    player = player,
                    ownership = oldOwnership,
                    onVisibilityChanged = { visible -> if (visible) oldOverlay.showMv() else oldOverlay.hideMv() },
                )
                controllerField.set(activity, oldController)
                assertTrue(runBlocking { oldController.open("mv:42", "hd") }.getBoolean("accepted"))
                assertTrue(oldOverlay.isMvVisible)
            }
            scenario.onActivity {
                oldWebView.evaluateJavascript("window.location.hash = '#/home'", null)
            }
            assertTrue(waitUntil(scenario) { !oldController.isOpen && !oldOverlay.isMvVisible })
            assertEquals(PlaybackOwner.MUSIC_SERVICE, oldOwnership.value)
            scenario.onActivity {
                oldWebView.evaluateJavascript("window.location.hash = '#/mv/mv%3A42'", null)
            }
            assertTrue(waitUntil(scenario) { activity ->
                val gate = MainActivity::class.java.getDeclaredField("mvRouteGate")
                    .apply { isAccessible = true }
                    .get(activity) as MvRouteGate
                gate.allowsMvOpen
            })
            scenario.onActivity {
                assertTrue(runBlocking { oldController.open("mv:42", "hd") }.getBoolean("accepted"))
                assertTrue(oldOverlay.isMvVisible)
            }

            scenario.recreate()

            assertTrue(waitUntil(scenario, timeoutMs = 10_000L) { activity ->
                val nextWebView = MainActivity::class.java.getDeclaredField("webView")
                    .apply { isAccessible = true }
                    .get(activity) as? WebView
                val gate = MainActivity::class.java.getDeclaredField("mvRouteGate")
                    .apply { isAccessible = true }
                    .get(activity) as? MvRouteGate
                val overlay = MainActivity::class.java.getDeclaredField("videoOverlay")
                    .apply { isAccessible = true }
                    .get(activity) as? TvVideoOverlay
                nextWebView != null && nextWebView !== oldWebView && nextWebView.progress == 100 &&
                    gate?.allowsMvOpen == false && overlay?.isMvVisible == false
            })
            assertFalse(oldController.isOpen)
            assertEquals(PlaybackOwner.MUSIC_SERVICE, oldOwnership.value)
        }
    }

    @Test
    fun controllerOnlyViewHostRecreateReleasesOldMediaControllerAndConnectsANewOne() {
        var oldController: MediaController? = null
        ActivityScenario.launch(ControllerLifecycleActivity::class.java).use { scenario ->
            assertTrue(waitUntil(scenario, timeoutMs = 10_000L) { activity ->
                oldController = MainActivity::class.java.getDeclaredField("musicController")
                    .apply { isAccessible = true }
                    .get(activity) as? MediaController
                oldController?.isConnected == true
            })

            scenario.recreate()

            var newController: MediaController? = null
            assertTrue(waitUntil(scenario, timeoutMs = 10_000L) { activity ->
                newController = MainActivity::class.java.getDeclaredField("musicController")
                    .apply { isAccessible = true }
                    .get(activity) as? MediaController
                newController?.isConnected == true && newController !== oldController
            })
            assertFalse(oldController!!.isConnected)
        }
    }

    private fun openFirstHomeMv(web: ProductionWebViewHarness) {
        assertTrue(web.waitFor("document.querySelector('[data-page=home] [data-mv-entry]') !== null"))
        assertTrue(web.click("[data-page=home] [data-mv-entry]"))
        assertTrue(web.waitFor("document.querySelector('[data-page=mv-detail]') !== null"))
    }

    private fun installVideoOpenProbe(web: ProductionWebViewHarness) {
        assertTrue(
            web.bool(
                "(() => { const bridge = window.EchoTvNative; if (!bridge) return false; " +
                    "const previous = bridge.onmessage; window.__playbackGateOpenResult = null; " +
                    "window.__playbackGateRouteRetries = 0; " +
                    "bridge.onmessage = (event) => { let value; try { value = JSON.parse(event.data); } catch {} " +
                    "if (typeof value?.payload?.accepted === 'boolean') { " +
                    "if (!value.payload.accepted && value.payload.errorCode === 'MV_ROUTE_REQUIRED') { " +
                    "window.__playbackGateRouteRetries += 1; " +
                    "} else { window.__playbackGateOpenResult = value.payload.accepted ? 'accepted' : value.payload.errorCode; " +
                    "bridge.onmessage = previous; } } if (previous) previous(event); }; return true; })()",
            ),
        )
    }

    private fun waitForCondition(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20L)
        }
        return false
    }

    private fun <A : androidx.activity.ComponentActivity> waitUntil(
        scenario: ActivityScenario<A>,
        timeoutMs: Long = 5_000L,
        predicate: (A) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { activity -> matched = predicate(activity) }
            if (matched) return true
            Thread.sleep(50L)
        }
        return false
    }

    private fun waitForResponse(
        responses: ConcurrentHashMap<String, MutableList<String>>,
        id: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (responses[id]?.isNotEmpty() == true) return true
            Thread.sleep(20L)
        }
        return false
    }
}
