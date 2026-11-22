package com.kutv.music.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.webkit.WebViewAssetLoader
import com.kutv.music.BuildConfig
import com.kutv.music.bridge.BridgeRouter

object TvWebViewHost {
    const val LOCAL_URL = "https://appassets.androidplatform.net/assets/tv-ui/tv.html"

    fun createView(
        context: Context,
        scope: LifecycleCoroutineScope,
        router: BridgeRouter,
        routeObserved: (String?) -> Unit = {},
        rendererGone: ((WebView) -> Unit)? = null,
        initialUrl: String = LOCAL_URL,
    ): View {
        if (!TvWebMessageBridge.isSupported()) return unsupportedWebViewError(context)
        return createForBridge(context, installListener = { webView ->
            TvWebMessageBridge.install(webView, scope, router)
        }, routeObserved = routeObserved, rendererGone = rendererGone,
            loadPage = { it.loadUrl(initialUrl.takeIf(TvPageRecoveryPolicy::isRestorable) ?: LOCAL_URL) })
    }

    fun create(context: Context): WebView = configure(TvRemoteWebView(context), context).also(::load)

    internal fun createForBridge(
        context: Context,
        installListener: (WebView) -> Unit,
        loadPage: (WebView) -> Unit = { load(it) },
        routeObserved: (String?) -> Unit = {},
        rendererGone: ((WebView) -> Unit)? = null,
    ): WebView = configure(TvRemoteWebView(context), context, routeObserved, rendererGone).also { webView ->
        installListener(webView)
        loadPage(webView)
    }

    // JavaScript is required for the packaged Vue UI. Navigation is pinned to
    // HTTPS appassets, file/content access is disabled, and the bridge checks
    // the exact main-frame origin before accepting a message.
    @SuppressLint("SetJavaScriptEnabled")
    internal fun configure(
        webView: WebView,
        context: Context,
        routeObserved: (String?) -> Unit = {},
        rendererGone: ((WebView) -> Unit)? = null,
    ): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        return webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            webViewClient = createClient(context, routeObserved, rendererGone)
        }
    }

    internal fun load(webView: WebView) {
        webView.loadUrl(LOCAL_URL)
    }

    fun publish(webView: WebView, message: String) {
        TvWebMessageBridge.publish(webView, message)
    }

    internal fun createClient(
        context: Context,
        routeObserved: (String?) -> Unit = {},
        rendererGone: ((WebView) -> Unit)? = null,
    ): WebViewClient {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        return TvAssetClient(assetLoader, TvWebMessageBridge::onPageStarted, routeObserved, rendererGone)
    }

    internal fun dispose(webView: WebView, rendererGone: Boolean = false) {
        TvWebMessageBridge.dispose(webView, removeListener = !rendererGone)
        if (!rendererGone) {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun unsupportedWebViewError(context: Context): TextView =
        TvFallbackView.create(context, "此设备的 Android System WebView 版本过低，请更新后重试。")
}
