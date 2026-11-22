package com.kutv.music

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener

internal class ProductionWebViewHarness private constructor(
    private val scenario: ActivityScenario<MainActivity>,
    private var activity: MainActivity,
    webView: WebView,
) : AutoCloseable {
    var webView: WebView = webView
        private set

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun launch(activityClass: Class<out MainActivity> = MainActivity::class.java): ProductionWebViewHarness {
            com.kutv.music.application.playback.PlaybackErrorRuntime.store.state.value.error
                ?.let { error -> com.kutv.music.application.playback.PlaybackErrorRuntime.store.clear(error.errorId) }
            val scenario = ActivityScenario.launch(activityClass) as ActivityScenario<MainActivity>
            var launchedActivity: MainActivity? = null
            var webView: WebView? = null
            scenario.onActivity { activity ->
                launchedActivity = activity
                webView = MainActivity::class.java.getDeclaredField("webView")
                    .apply { isAccessible = true }
                    .get(activity) as? WebView
                webView?.requestFocus()
            }
            return ProductionWebViewHarness(
                scenario,
                checkNotNull(launchedActivity) { "Production Activity did not launch" },
                checkNotNull(webView) { "Production WebView did not load" },
            )
                .also { check(it.waitFor("document.querySelector('[data-page]') !== null")) }
        }
    }

    fun evaluate(script: String): Any? {
        val complete = CountDownLatch(1)
        var raw = "null"
        val failure = AtomicReference<Throwable?>(null)
        check(webView.post {
            try {
                webView.evaluateJavascript(script) { value ->
                    raw = value ?: "null"
                    complete.countDown()
                }
            } catch (error: Throwable) {
                failure.set(error)
                complete.countDown()
            }
        }) { "Could not post JavaScript evaluation" }
        check(complete.await(5, TimeUnit.SECONDS)) { "JavaScript evaluation timed out" }
        failure.get()?.let { throw it }
        return runCatching { JSONTokener(raw).nextValue() }.getOrNull()
    }

    fun bool(script: String): Boolean = evaluate(script) == true
    fun text(script: String): String = evaluate(script)?.toString().orEmpty()

    fun click(selector: String): Boolean = bool(
        "(() => { const node = document.querySelector(${JSONObject.quote(selector)}); " +
            "if (!node) return false; node.click(); return true; })()",
    )

    fun focus(selector: String): Boolean = bool(
        "(() => { const node = document.querySelector(${JSONObject.quote(selector)}); " +
            "if (!node) return false; node.focus(); return document.activeElement === node; })()",
    )

    fun activeFocusId(): String = text("document.activeElement?.dataset?.focusId || ''")

    fun waitFor(script: String, timeoutMs: Long = 8_000L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (bool(script)) return true
            Thread.sleep(50L)
        }
        return false
    }

    fun dispatchKey(keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            runOnActivity { it.onBackPressedDispatcher.onBackPressed() }
            return
        }
        val eventTime = SystemClock.uptimeMillis()
        val down = KeyEvent(
            eventTime,
            eventTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_DPAD,
        )
        runOnWebView {
            if (!webView.hasFocus()) webView.requestFocus()
            check(webView.dispatchKeyEvent(down)) { "Production WebView did not consume D-pad key down" }
            webView.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        }
    }

    fun backgroundWithHome() {
        check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome())
        waitForActivityState { !it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    }

    fun returnFromLauncher() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context, activity.javaClass).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ))
        waitForActivityState { it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        refreshWebView()
        check(waitFor("document.querySelector('[data-page]') !== null", 12_000L))
    }

    fun refreshWebView(previous: WebView? = null) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val candidate = withActivity {
                MainActivity::class.java.getDeclaredField("webView").apply { isAccessible = true }.get(it) as? WebView
            }
            if (candidate != null && candidate !== previous) {
                webView = candidate
                return
            }
            Thread.sleep(100L)
        }
        error("Production WebView recovery timed out")
    }

    private fun waitForActivityState(predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (withActivity(predicate)) return
            Thread.sleep(100L)
        }
        error("Production Activity background/foreground transition timed out")
    }

    fun recreate() {
        val previousActivity = activity
        val application = previousActivity.application
        val resumed = CountDownLatch(1)
        val nextActivity = AtomicReference<MainActivity?>(null)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(candidate: Activity) {
                if (candidate is MainActivity && candidate !== previousActivity) {
                    nextActivity.set(candidate)
                    resumed.countDown()
                }
            }

            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            runOnActivity {
                webView.visibility = View.GONE
                webView.onPause()
                it.recreate()
            }
            check(resumed.await(10, TimeUnit.SECONDS)) { "Production Activity recreation timed out" }
            activity = checkNotNull(nextActivity.get()) { "Recreated Production Activity was not observed" }
            webView = checkNotNull(
                MainActivity::class.java.getDeclaredField("webView")
                    .apply { isAccessible = true }
                    .get(activity) as? WebView,
            ) { "Recreated Production WebView was not created" }
            check(waitFor("document.querySelector('[data-page]') !== null", 10_000L)) {
                "Recreated Production WebView did not load"
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    fun dispatchKeyAction(keyCode: Int, action: Int, repeatCount: Int = 0, flags: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, keyCode, repeatCount, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags or KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD)
        runOnWebView { check(webView.dispatchKeyEvent(event)) { "Remote event was not consumed" } }
    }

    fun <T> withActivity(action: (MainActivity) -> T): T {
        val complete = CountDownLatch(1)
        val result = AtomicReference<Result<T>?>(null)
        val target = activity
        check(target.window.decorView.post {
            result.set(runCatching { action(target) })
            complete.countDown()
        }) { "Could not post Production Activity action" }
        check(complete.await(5, TimeUnit.SECONDS)) { "Production Activity action timed out" }
        return checkNotNull(result.get()) { "Production Activity action produced no result" }.getOrThrow()
    }

    fun emit(event: String, payload: JSONObject) {
        val envelope = JSONObject()
            .put("version", 1)
            .put("type", "event")
            .put("event", event)
            .put("payload", payload)
        evaluate(
            "(() => { const handler = EchoTvNative.onmessage; if (!handler) return false; " +
                "window.__kutvFixtureEmitting=true; try { handler({data:${JSONObject.quote(envelope.toString())}}); } " +
                "finally { window.__kutvFixtureEmitting=false; } return true; })()",
        )
    }

    fun emitPlayerSnapshot(
        playing: Boolean = true,
        queueSize: Int = 2,
        positionMs: Long = 2_000L,
    ) {
        if (bool("window.__kutvPlayerUiFixture === true")) {
            // A real persisted public track can fail restore between launch and
            // fixture setup. Clear its exact native revision before projecting
            // a successful synthetic song; equal revisions cannot erase errors.
            com.kutv.music.application.playback.PlaybackErrorRuntime.store.state.value.error
                ?.let { com.kutv.music.application.playback.PlaybackErrorRuntime.store.clear(it.errorId) }
        }
        fun track(index: Int) = JSONObject()
            .put("id", "tv_instrumented_$index")
            .put("title", if (index == 0) "Instrumented Song" else "Instrumented Next")
            .put("artists", org.json.JSONArray().put("Echo Test"))
            .put("album", "Bridge Album")
            .put("durationMs", 180_000)
            .put("qualities", org.json.JSONArray().put("320"))
        val queue = org.json.JSONArray().apply { repeat(queueSize) { put(track(it)) } }
        val lyrics = org.json.JSONArray()
            .put(JSONObject().put("startMs", 0).put("endMs", 1_499).put("text", "First line"))
            .put(JSONObject().put("startMs", 1_500).put("endMs", 2_999).put("text", "Active line"))
        emit(
            "player.snapshot",
            JSONObject()
                .put("currentTrack", track(0))
                .put("isPlaying", playing)
                .put("positionMs", positionMs)
                .put("durationMs", 180_000)
                .put("queue", queue)
                .put("queueIndex", 0)
                .put("repeatMode", "off")
                .put("quality", "320")
                .put("lyrics", lyrics)
                .put("error", JSONObject.NULL)
                .put("errorRevision", com.kutv.music.application.playback.PlaybackErrorRuntime.store.state.value.errorRevision),
        )
    }

    /** Only for UI projection tests: real Media3 tests must not call this. */
    fun isolatePlayerUiFixture() {
        evaluate("""
            (()=>{
              window.__kutvPlayerUiFixture=true;
              const bridge=window.EchoTvNative, receive=bridge.onmessage;
              bridge.onmessage=message=>{
                const data=JSON.parse(message.data);
                if(data.type==='event' && !window.__kutvFixtureEmitting &&
                   (data.event?.startsWith('player.') || ['queue.changed','lyrics.changed','app.error'].includes(data.event))) return;
                return receive?.(message);
              };
            })()
        """.trimIndent())
    }

    override fun close() {
        val closingActivity = activity
        val destroyed = CountDownLatch(1)
        val application = closingActivity.application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(candidate: Activity) {
                if (candidate === closingActivity) destroyed.countDown()
            }

            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            val cleanupFailure = AtomicReference<Throwable?>(null)
            val finishPosted = closingActivity.window.decorView.post {
                try {
                    val musicController = MainActivity::class.java.getDeclaredField("musicController")
                        .apply { isAccessible = true }
                        .get(closingActivity) as? androidx.media3.session.MediaController
                    musicController?.stop()
                    musicController?.clearMediaItems()
                    closingActivity.stopService(Intent(closingActivity, com.kutv.music.player.MusicService::class.java))
                    closingActivity.getSystemService(InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(webView.windowToken, 0)
                    webView.clearFocus()
                    webView.visibility = View.GONE
                    webView.onPause()
                } catch (error: Throwable) {
                    cleanupFailure.set(error)
                } finally {
                    closingActivity.finishAndRemoveTask()
                }
            }
            check(finishPosted) { "Could not post Production Activity cleanup" }
            check(destroyed.await(10, TimeUnit.SECONDS)) { "Production Activity cleanup timed out" }
            cleanupFailure.get()?.let { throw it }
            val scenarioClosed = CountDownLatch(1)
            val closeFailure = AtomicReference<Throwable?>(null)
            Thread(
                {
                    try {
                        scenario.close()
                    } catch (error: Throwable) {
                        closeFailure.set(error)
                    } finally {
                        scenarioClosed.countDown()
                    }
                },
                "production-webview-scenario-close",
            ).apply {
                isDaemon = true
                start()
            }
            check(scenarioClosed.await(5, TimeUnit.SECONDS)) { "ActivityScenario cleanup timed out" }
            closeFailure.get()?.let { throw it }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun runOnWebView(action: () -> Unit) {
        val complete = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        check(webView.post {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                complete.countDown()
            }
        }) { "Could not post WebView action" }
        check(complete.await(5, TimeUnit.SECONDS)) { "WebView action timed out" }
        failure.get()?.let { throw it }
    }

    private fun runOnActivity(action: (MainActivity) -> Unit) {
        withActivity {
            action(it)
            Unit
        }
    }
}
