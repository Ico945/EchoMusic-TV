package com.kutv.music.application.content

import com.kutv.music.application.auth.QrImageRenderer
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ShareTarget

/**
 * Builds a public KuGou link and renders it as an offline QR image. The projection contains no
 * account credential, signed media URL, or playback token.
 */
class SharePresentationUseCase(
    private val qrImageRenderer: QrImageRenderer,
    private val clipboardAvailable: Boolean = false,
) {
    fun create(target: ShareTarget): Result<ShareProjection> {
        val publicId = target.id.removePrefix("${target.kind.name.lowercase()}:")
        if (publicId.isBlank()) return Result.failure(IllegalArgumentException("分享内容 id 不能为空"))
        val url = when (target.kind) {
            ContentKind.SONG -> "https://www.kugou.com/mixsong/$publicId"
            ContentKind.ALBUM -> "https://www.kugou.com/album/$publicId"
            ContentKind.PLAYLIST -> "https://www.kugou.com/yy/special/single/$publicId"
            else -> return Result.failure(UnsupportedOperationException("该类型暂不支持分享"))
        }
        return runCatching {
            ShareProjection(
                url = url,
                qrImageDataUrl = qrImageRenderer.render(url),
                clipboardAvailable = clipboardAvailable,
            )
        }
    }
}
