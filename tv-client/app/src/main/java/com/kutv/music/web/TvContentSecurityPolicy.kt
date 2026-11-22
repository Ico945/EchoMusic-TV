package com.kutv.music.web

/**
 * Single source of truth for the TV WebView content security policy. The
 * packaged `tv.html` ships the same policy as a meta tag; the release gate
 * byte-compares the packaged HTML and re-checks this string for drift.
 */
object TvContentSecurityPolicy {
    const val POLICY = "default-src 'self'; " +
        "script-src 'self'; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: https://*.kugou.com https://*.kugou.net; " +
        "media-src 'none'; " +
        "connect-src 'none'; " +
        "frame-src 'none'; " +
        "object-src 'none'; " +
        "base-uri 'none'; " +
        "form-action 'none'"

    fun matchesPackagedPolicy(raw: String): Boolean = raw.contains(POLICY)
}
