package com.kutv.music.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveValueRedactorTest {
    @Test
    fun `removes tokens qr keys and stream urls`() {
        val raw = "token=secret qrcode=abc https://trackercdn.kugou.com/file.mp3"
        val redacted = SensitiveValueRedactor.redact(raw)
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("qrcode=abc"))
        assertFalse(redacted.contains("file.mp3"))
    }

    @Test
    fun `redacts query-shaped credentials and hashes`() {
        val raw = "https://gateway.kugou.com/x?token=TOKEN123&vip_token=VIP9&hash=0123456789abcdef0123456789abcdef&userid=42"
        val redacted = SensitiveValueRedactor.redact(raw)
        assertFalse(redacted.contains("TOKEN123"))
        assertFalse(redacted.contains("VIP9"))
        assertFalse(redacted.contains("0123456789abcdef"))
        assertTrue(redacted.contains("token=[REDACTED]"))
        assertTrue(redacted.contains("hash=[REDACTED]"))
    }

    @Test
    fun `leaves ordinary text untouched`() {
        val raw = "播放队列就绪，共 12 首歌曲"
        assertTrue(SensitiveValueRedactor.redact(raw).contains("播放队列就绪"))
    }

    @Test
    fun `removes media urls on any host`() {
        val raw = "stream https://cdn.example.com/song.flac ready"
        val redacted = SensitiveValueRedactor.redact(raw)
        assertFalse(redacted.contains("song.flac"))
        assertTrue(redacted.contains("[REDACTED]"))
    }
}

class BridgePayloadPolicyTest {
    @Test
    fun `rejects payloads nested beyond the limit`() {
        val deep = "{".repeat(BridgePayloadPolicy.MAX_JSON_DEPTH + 1) +
            "}".repeat(BridgePayloadPolicy.MAX_JSON_DEPTH + 1)
        assertFalse(BridgePayloadPolicy.withinDepthLimit(deep))
    }

    @Test
    fun `accepts payloads within the limit and ignores braces inside strings`() {
        val valid = "{\"payload\":{\"text\":\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"}}}"
        assertTrue(BridgePayloadPolicy.withinDepthLimit(valid))
    }
}

class TvContentSecurityPolicyTest {
    @Test
    fun `policy forbids remote scripts media and connections`() {
        val policy = com.kutv.music.web.TvContentSecurityPolicy.POLICY
        assertTrue(policy.contains("default-src 'self'"))
        assertTrue(policy.contains("script-src 'self'"))
        assertTrue(policy.contains("media-src 'none'"))
        assertTrue(policy.contains("connect-src 'none'"))
        assertTrue(policy.contains("object-src 'none'"))
        assertTrue(policy.contains("img-src 'self' data: https://*.kugou.com https://*.kugou.net"))
    }
}
