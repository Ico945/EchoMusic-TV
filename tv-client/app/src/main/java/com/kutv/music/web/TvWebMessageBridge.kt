package com.kutv.music.web

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.kutv.music.bridge.BridgeRouter
import com.kutv.music.bridge.BridgeEnvelope
import com.kutv.music.bridge.BridgeParser
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

object TvWebMessageBridge {
    const val OBJECT_NAME = "EchoTvNative"
    const val ALLOWED_ORIGIN = "https://appassets.androidplatform.net"
    private const val MAX_PENDING_EVENTS = 32
    private const val MAX_PENDING_BYTES = 512 * 1024
    private const val MAX_IN_FLIGHT_REQUESTS = 16
    private const val REQUEST_TIMEOUT_MS = 15_000L
    private val replyProxies = WeakHashMap<WebView, JavaScriptReplyProxy>()
    private val pendingEvents = WeakHashMap<WebView, LinkedHashMap<String, String>>()
    private val latestSnapshots = WeakHashMap<WebView, String>()
    private val requestMutexes = WeakHashMap<WebView, Mutex>()
    private val inFlightRequests = WeakHashMap<WebView, AtomicInteger>()
    private val requestJobs = WeakHashMap<WebView, Job>()

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    // Android lint does not propagate WebViewFeature checks across this
    // callback registration. Keep the explicit fail-closed guard above the
    // optional API and suppress only that proven false positive.
    @SuppressLint("RequiresFeature")
    fun install(webView: WebView, scope: LifecycleCoroutineScope, router: BridgeRouter) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            error("The installed Android System WebView does not support secure web-message listeners")
        }
        val requestJob = SupervisorJob(scope.coroutineContext[Job])
        synchronized(this) { requestJobs.put(webView, requestJob)?.cancel() }
        val requestScope = CoroutineScope(scope.coroutineContext + requestJob)
        WebViewCompat.addWebMessageListener(
            webView,
            OBJECT_NAME,
            setOf(ALLOWED_ORIGIN),
        ) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, replyProxy: JavaScriptReplyProxy ->
            if (!isMainFrame || sourceOrigin.toString() != ALLOWED_ORIGIN) return@addWebMessageListener
            val pending = synchronized(this) {
                replyProxies[webView] = replyProxy
                pendingEvents.remove(webView)?.values?.toList().orEmpty()
            }
            pending.forEach { postMessage(replyProxy, it) }
            val raw = message.data ?: ""
            if (BridgeParser.isMessageTooLarge(raw)) {
                postMessage(
                    replyProxy,
                    BridgeEnvelope.error(
                        "unknown",
                        "BRIDGE_PAYLOAD_TOO_LARGE",
                        "请求过大",
                        false,
                        "bridge",
                    ).toString(),
                )
                return@addWebMessageListener
            }
            val inFlight = synchronized(this) {
                inFlightRequests.getOrPut(webView) { AtomicInteger() }.incrementAndGet()
            }
            if (inFlight > MAX_IN_FLIGHT_REQUESTS) {
                synchronized(this) { inFlightRequests[webView]?.decrementAndGet() }
                postMessage(
                    replyProxy,
                    BridgeEnvelope.error(
                        BridgeParser.requestIdOrFallback(raw),
                        "BRIDGE_BUSY",
                        "请求过于频繁，请稍后重试",
                        true,
                        "bridge",
                    ).toString(),
                )
                return@addWebMessageListener
            }
            val mutex = synchronized(this) { requestMutexes.getOrPut(webView) { Mutex() } }
            requestScope.launch {
                try {
                    val response = dispatch(router, raw, mutex)
                    postMessage(replyProxy, response)
                } finally {
                    synchronized(this@TvWebMessageBridge) {
                        inFlightRequests[webView]?.decrementAndGet()
                    }
                }
            }
        }
    }

    /** The exact serialized dispatch path used by production and bridge scheduling tests. */
    internal suspend fun dispatch(router: BridgeRouter, raw: String, mutex: Mutex): String = try {
        withTimeout(REQUEST_TIMEOUT_MS) {
            mutex.withLock { router.handle(raw) }
        }
    } catch (_: TimeoutCancellationException) {
        BridgeEnvelope.error(
            BridgeParser.requestIdOrFallback(raw),
            "BRIDGE_TIMEOUT",
            "操作超时，请重试",
            true,
            "bridge",
        ).toString()
    }

    fun publish(webView: WebView, message: String) {
        val proxy = synchronized(this) {
            updateLatestSnapshot(webView, message)
            replyProxies[webView] ?: run {
                enqueuePending(webView, message)
                null
            }
        }
        if (proxy != null && !postMessage(proxy, message)) {
            synchronized(this) {
                if (replyProxies[webView] === proxy) replyProxies.remove(webView)
                enqueuePending(webView, message)
            }
        }
    }

    fun onPageStarted(webView: WebView) {
        synchronized(this) {
            replyProxies.remove(webView)
            pendingEvents.remove(webView)
            latestSnapshots[webView]?.let { enqueuePending(webView, it) }
        }
    }

    fun dispose(webView: WebView, removeListener: Boolean = true) {
        synchronized(this) {
            requestJobs.remove(webView)?.cancel()
            replyProxies.remove(webView)
            pendingEvents.remove(webView)
            latestSnapshots.remove(webView)
            requestMutexes.remove(webView)
            inFlightRequests.remove(webView)
        }
        if (removeListener && isSupported()) WebViewCompat.removeWebMessageListener(webView, OBJECT_NAME)
    }

    private fun enqueuePending(webView: WebView, message: String) {
        val queue = pendingEvents.getOrPut(webView) { linkedMapOf() }
        val eventName = runCatching { JSONObject(message).optString("event") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "message-${queue.size}"
        if (eventName == "player.snapshot") queue.remove("player.position")
        queue.remove(eventName)
        queue[eventName] = message
        while (
            queue.size > MAX_PENDING_EVENTS ||
            queue.values.sumOf { it.toByteArray(Charsets.UTF_8).size } > MAX_PENDING_BYTES
        ) {
            val removable = queue.keys.firstOrNull { it != "player.snapshot" } ?: break
            queue.remove(removable)
        }
    }

    private fun updateLatestSnapshot(webView: WebView, message: String) {
        val event = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (event.optString("event")) {
            "player.snapshot" -> latestSnapshots[webView] = message
            "player.position", "queue.changed", "lyrics.changed" -> {
                val latest = latestSnapshots[webView]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
                val fullPayload = latest.optJSONObject("payload") ?: return
                val delta = event.optJSONObject("payload") ?: return
                delta.keys().forEachRemaining { key -> fullPayload.put(key, delta.get(key)) }
                latestSnapshots[webView] = latest.toString()
            }
        }
    }

    private fun postMessage(proxy: JavaScriptReplyProxy, message: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        return runCatching { proxy.postMessage(message) }.isSuccess
    }
}
