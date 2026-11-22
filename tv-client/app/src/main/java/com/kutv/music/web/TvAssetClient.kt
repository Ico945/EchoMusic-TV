package com.kutv.music.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.ByteArrayInputStream
import java.net.URI

object TvAssetPathPolicy {
    private const val SCHEME = "https"
    private const val HOST = "appassets.androidplatform.net"
    private const val PATH_PREFIX = "/assets/tv-ui/"

    fun isAllowed(url: String?): Boolean = runCatching {
        val uri = URI(url)
        val rawPath = uri.rawPath ?: return false
        uri.scheme == SCHEME &&
            uri.host == HOST &&
            uri.rawAuthority == HOST &&
            uri.userInfo == null &&
            uri.port == -1 &&
            rawPath.startsWith(PATH_PREFIX) &&
            '\\' !in rawPath &&
            '%' !in rawPath &&
            rawPath.split('/').none { segment -> segment == "." || segment == ".." }
    }.getOrDefault(false)
}

class TvAssetClient(
    private val loader: WebViewAssetLoader,
    private val pageStarted: (WebView) -> Unit = {},
    private val routeObserved: (String?) -> Unit = {},
    private val rendererGone: ((WebView) -> Unit)? = null,
) : WebViewClientCompat() {
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val recover = rendererGone ?: return false
        recover(view)
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        pageStarted(view)
        // A reload invalidates native MV immediately, even when the pending URL keeps the same hash.
        routeObserved(null)
        super.onPageStarted(view, url, favicon)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        routeObserved(url)
        super.doUpdateVisitedHistory(view, url, isReload)
    }
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = when {
        TvAssetPathPolicy.isAllowed(request.url.toString()) -> loader.shouldInterceptRequest(request.url)
        ArtworkUrlPolicy.normalize(request.url.toString()) != null -> null
        else -> blockedResponse()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !TvAssetPathPolicy.isAllowed(request.url.toString())

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Forbidden",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )
}
