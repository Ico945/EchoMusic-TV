package com.kutv.music.direct

import java.net.URI

object KugouDomainPolicy {
    fun requireApiBase(baseUrl: String): URI {
        val uri = runCatching { URI(baseUrl) }.getOrElse {
            throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
        }
        val host = uri.host?.lowercase()
        val validHost = host == "kugou.com" || host?.endsWith(".kugou.com") == true
        if (
            uri.scheme?.lowercase() != "https" ||
            !validHost ||
            uri.userInfo != null ||
            uri.port != -1 ||
            !uri.path.orEmpty().let { it.isEmpty() || it == "/" } ||
            uri.query != null ||
            uri.fragment != null
        ) {
            throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
        }
        return uri
    }

    fun isAllowedMediaUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.userInfo != null || uri.fragment != null || uri.port != -1) return false
        val scheme = uri.scheme?.lowercase()
        return isAllowedMediaHost(host) &&
            (scheme == "https" || (scheme == "http" && isCleartextMediaHost(host)))
    }

    fun normalizeMediaUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (
            scheme !in setOf("http", "https") ||
            !isAllowedMediaHost(host) ||
            uri.userInfo != null ||
            uri.fragment != null ||
            uri.port != -1
        ) return null
        if (scheme == "http" && !isCleartextMediaHost(host)) return null
        return uri.toASCIIString()
    }

    private fun isAllowedMediaHost(host: String): Boolean =
        host == "kugou.com" || host.endsWith(".kugou.com") ||
            host == "kugou.net" || host.endsWith(".kugou.net") ||
            host == "kgimg.com" || host.endsWith(".kgimg.com") ||
            // Exact MV CDN returned by trackermv.kugou.com; not all Tencent domains.
            host == "kgv.stream.tencentmusic.com"

    private fun isCleartextMediaHost(host: String): Boolean =
        host == "kugou.com" || host.endsWith(".kugou.com") ||
            host == "kugou.net" || host.endsWith(".kugou.net") ||
            host == "kgv.stream.tencentmusic.com"
}
