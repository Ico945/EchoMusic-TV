package com.kutv.music.video

import androidx.annotation.MainThread
import java.net.URI

/** Native route authority. Only URLs observed from the production WebView may update this gate. */
class MvRouteGate(
    private val closeNativeMv: () -> Unit,
    private val onMvRouteChanged: (Boolean) -> Unit,
) {
    constructor(closeNativeMv: () -> Unit) : this(closeNativeMv, {})

    var allowsMvOpen: Boolean = false
        private set

    @MainThread
    fun observe(url: String?) {
        val next = isObservedMvDetail(url)
        val leftMvRoute = allowsMvOpen && !next
        val changed = allowsMvOpen != next
        allowsMvOpen = next
        if (changed) onMvRouteChanged(next)
        if (leftMvRoute) closeNativeMv()
    }

    private fun isObservedMvDetail(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return false
        if (uri.rawAuthority != "appassets.androidplatform.net" || uri.userInfo != null || uri.port != -1) return false
        if (uri.rawPath != "/assets/tv-ui/tv.html") return false
        val routePath = (uri.rawFragment ?: return false).substringBefore('?')
        val mvId = routePath.removePrefix("/mv/")
        routePath.startsWith("/mv/") && mvId.isNotBlank() && '/' !in mvId
    }.getOrDefault(false)
}
