package com.kutv.music

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.kutv.music.application.auth.AndroidQrImageRenderer
import com.kutv.music.application.content.SharePresentationUseCase
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ShareTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareQrInstrumentedTest {
    @Test
    fun publicSongShareRendersAReadableQrCode() {
        val projection = SharePresentationUseCase(AndroidQrImageRenderer)
            .create(ShareTarget(ContentKind.SONG, "song:album-audio-123"))
            .getOrThrow()

        assertTrue(projection.qrImageDataUrl.startsWith(PNG_PREFIX))
        val bytes = Base64.decode(projection.qrImageDataUrl.removePrefix(PNG_PREFIX), Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
        )

        assertEquals(projection.url, decoded.text)
        bitmap.recycle()
    }

    private companion object {
        const val PNG_PREFIX = "data:image/png;base64,"
    }
}
