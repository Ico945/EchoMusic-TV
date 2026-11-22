package com.kutv.music.application.content

import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ShareTarget
import java.net.URI
import java.net.URLDecoder

/**
 * Local share-link parser. KuGou has no upstream share-resolve endpoint, so this never touches
 * the network: it accepts https links on kugou.com and extracts a hash/album_id/special_id/song_id
 * fragment into a [ShareTarget]. Anything else is rejected.
 */
class ShareResolveUseCase {
    fun resolve(input: String): Result<ShareTarget> {
        val uri = runCatching { URI(input.trim()) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("无效的分享链接"))
        if (uri.scheme?.lowercase() != "https") {
            return Result.failure(IllegalArgumentException("仅支持 https 分享链接"))
        }
        val host = uri.host?.lowercase()
        if (host == null || (host != "kugou.com" && !host.endsWith(".kugou.com"))) {
            return Result.failure(IllegalArgumentException("非酷狗分享链接"))
        }

        val query = parseQuery(uri.rawQuery)
        query["special_id"]?.takeIf(String::isNotBlank)?.let {
            return Result.success(ShareTarget(ContentKind.PLAYLIST, it))
        }
        query["album_id"]?.takeIf(String::isNotBlank)?.let {
            return Result.success(ShareTarget(ContentKind.ALBUM, it))
        }
        query["hash"]?.takeIf(String::isNotBlank)?.let {
            return Result.success(ShareTarget(ContentKind.SONG, it))
        }
        query["song_id"]?.takeIf(String::isNotBlank)?.let {
            return Result.success(ShareTarget(ContentKind.SONG, it))
        }

        resolvePath(uri.path)?.let { return Result.success(it) }

        return Result.failure(IllegalArgumentException("未找到可解析的分享片段"))
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    private fun resolvePath(path: String?): ShareTarget? {
        if (path.isNullOrBlank()) return null
        val segments = path.split('/').filter(String::isNotBlank)
        val last = segments.lastOrNull()?.substringBefore('.')?.takeIf(String::isNotBlank)
            ?: return null
        return when {
            "special" in segments || "single" in segments -> ShareTarget(ContentKind.PLAYLIST, last)
            "album" in segments -> ShareTarget(ContentKind.ALBUM, last)
            "mixsong" in segments || "song" in segments -> ShareTarget(ContentKind.SONG, last)
            else -> null
        }
    }
}
