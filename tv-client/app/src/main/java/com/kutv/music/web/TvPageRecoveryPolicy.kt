package com.kutv.music.web

import java.net.URI

/** Small, bounded state: never save WebView history, credentials, or a remote URL. */
class TvPageRecoveryPolicy(private val maxAutomaticAttempts: Int = 3) {
    var lastUrl: String = TvWebViewHost.LOCAL_URL
        private set
    private var attempts = 0

    fun remember(url: String?) {
        // A lost UI also loses the native video surface. Return to its source
        // page after a renderer/Activity rebuild; never silently restart an MV.
        if (isRestorable(url) && !URI(url).rawFragment.orEmpty().startsWith("/mv/")) lastUrl = url!!
    }

    fun beginRecovery(): Boolean {
        if (attempts >= maxAutomaticAttempts) return false
        attempts += 1
        return true
    }

    fun markHealthy() { attempts = 0 }

    companion object {
        fun isRestorable(url: String?): Boolean = runCatching {
            if (url == null || url.length > 4096 || url.any { it.isISOControl() }) return false
            val uri = URI(url)
            val document = URI(TvWebViewHost.LOCAL_URL)
            uri.scheme == document.scheme && uri.rawAuthority == document.rawAuthority &&
                uri.rawPath == document.rawPath && uri.rawQuery == null &&
                (uri.rawFragment == null || (uri.rawFragment.startsWith('/') && !uri.rawFragment.startsWith("//")))
        }.getOrDefault(false)
    }
}
