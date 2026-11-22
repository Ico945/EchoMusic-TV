package com.kutv.music.application.content

import com.kutv.music.application.auth.QrImageRenderer
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ShareTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareResolveUseCaseTest {
    private val resolver = ShareResolveUseCase()

    @Test
    fun `javascript scheme is rejected`() {
        val result = resolver.resolve("javascript:alert(1)")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `kugou https share resolves hash to song`() {
        val result = resolver.resolve("https://t1.kugou.com/song.html?hash=deadbeef")
        assertEquals(ShareTarget(ContentKind.SONG, "deadbeef"), result.getOrNull())
    }

    @Test
    fun `non kugou domain is rejected`() {
        assertTrue(resolver.resolve("https://example.com/song?hash=abc").isFailure)
    }

    @Test
    fun `kugou url without fragment is rejected`() {
        assertTrue(resolver.resolve("https://www.kugou.com/").isFailure)
    }

    @Test
    fun `album id fragment resolves to album`() {
        val result = resolver.resolve("https://www.kugou.com/album?album_id=5050")
        assertEquals(ShareTarget(ContentKind.ALBUM, "5050"), result.getOrNull())
    }

    @Test
    fun `special id fragment resolves to playlist`() {
        val result = resolver.resolve("https://www.kugou.com/yy/special/single?special_id=6060")
        assertEquals(ShareTarget(ContentKind.PLAYLIST, "6060"), result.getOrNull())
    }

    @Test
    fun `share projection string never contains a token`() {
        var renderedUrl = ""
        val projection = SharePresentationUseCase(QrImageRenderer { url ->
            renderedUrl = url
            "data:image/png;base64,REAL_QR"
        })
            .create(ShareTarget(ContentKind.SONG, "song:album-audio-123"))
            .getOrThrow()

        assertFalse(projection.toString().contains("token"))
        assertEquals("https://www.kugou.com/mixsong/album-audio-123", projection.url)
        assertEquals(projection.url, renderedUrl)
        assertEquals("data:image/png;base64,REAL_QR", projection.qrImageDataUrl)
    }

    @Test
    fun `qr rendering failure does not return a fake projection`() {
        val result = SharePresentationUseCase(QrImageRenderer { error("render failed") })
            .create(ShareTarget(ContentKind.ALBUM, "5050"))

        assertTrue(result.isFailure)
    }
}
