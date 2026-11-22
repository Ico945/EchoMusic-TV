package com.kutv.music.security

import java.util.regex.Pattern

/**
 * Redacts credential-, QR-key-, and stream-URL-shaped values from log-like
 * text. The redactor is deliberately conservative: it removes the matched
 * segment entirely rather than partially masking, so redacted output never
 * carries a usable prefix or suffix.
 */
object SensitiveValueRedactor {
    private val secretKeys = Pattern.compile(
        "(?i)(?<![a-z0-9])(" +
            "token|vip_token|vipToken|kugou_token|" +
            "qrcode|qr_key|qrkey|qr_secret|qrsecret|" +
            "hash|secret|signature|accesskey" +
            ")=[^&\\s\"']*",
    )

    private val mediaUrl = Pattern.compile(
        "https?://[^\\s\"']+\\.(?:mp3|flac|m4a|aac|mp4|mkv|flv)[^\\s\"']*",
        Pattern.CASE_INSENSITIVE,
    )

    fun redact(value: String): String {
        val withKeysRedacted = secretKeys.matcher(value).replaceAll("$1=[REDACTED]")
        return mediaUrl.matcher(withKeysRedacted).replaceAll("[REDACTED]")
    }
}
