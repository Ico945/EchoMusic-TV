package com.kutv.music.application.auth

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.util.Base64

fun interface QrImageRenderer {
    fun render(url: String): String
}

object AndroidQrImageRenderer : QrImageRenderer {
    private const val SIZE = 480
    private const val MARGIN = 2

    override fun render(url: String): String {
        require(url.isNotBlank()) { "QR content must not be blank" }
        val matrix = QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            SIZE,
            SIZE,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to MARGIN,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val pixels = IntArray(SIZE * SIZE) { index ->
            if (matrix[index / SIZE, index % SIZE]) Color.BLACK else Color.WHITE
        }
        val bitmap = createBitmap(SIZE, SIZE)
        bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }
}
