package com.kutv.music.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDomainPolicyTest {
    @Test fun `official Tencent MV CDN is exact host only and never an API base`() {
        val host = "kgv.stream.tencentmusic.com"
        for (scheme in listOf("http", "https")) {
            val url = "$scheme://$host/video.mp4"
            assertEquals(url, KugouDomainPolicy.normalizeMediaUrl(url))
            assertTrue(KugouDomainPolicy.isAllowedMediaUrl(url))
        }
        for (url in listOf("http://$host.evil.example/a", "http://sub.$host/a",
            "http://stream.tencentmusic.com/a", "http://other.tencentmusic.com/a",
            "http://user@$host/a", "http://$host:8080/a", "http://$host/a#fragment")) {
            assertNull(KugouDomainPolicy.normalizeMediaUrl(url))
        }
        assertTrue(runCatching { KugouDomainPolicy.requireApiBase("https://$host") }.isFailure)
    }

    @Test
    fun `known Kugou media http url is preserved for scoped cleartext playback`() {
        val normalized = KugouDomainPolicy.normalizeMediaUrl(
            "http://fs.youthandroid2.kugou.com/path/audio.mp3?key=value",
        )

        assertEquals(
            "http://fs.youthandroid2.kugou.com/path/audio.mp3?key=value",
            normalized,
        )
        assertTrue(KugouDomainPolicy.isAllowedMediaUrl(checkNotNull(normalized)))
    }

    @Test
    fun `unknown host and credential bearing url are rejected`() {
        assertNull(KugouDomainPolicy.normalizeMediaUrl("http://evil.example/audio.mp3"))
        assertNull(KugouDomainPolicy.normalizeMediaUrl("https://user@fs.kugou.com/audio.mp3"))
    }
}
