package com.kutv.music.web

import java.net.URI

object ArtworkUrlPolicy {
    // singerimg is the exact CDN returned by the real /ocean/v6/singer/list feed.
    // Keep the HTTPS/no-query/authority checks; do not allow a Kugou wildcard.
    private val allowedHosts = setOf("imge.kugou.com", "singerimg.kugou.com")

    fun normalize(value: String): String? {
        if (value.isBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return uri.toASCIIString().takeIf {
            uri.scheme?.lowercase() == "https" && uri.host?.lowercase() in allowedHosts &&
                uri.rawAuthority?.lowercase() in allowedHosts && uri.userInfo == null &&
                uri.port == -1 && uri.query == null && uri.fragment == null
        }
    }
}
