package com.kutv.music.bridge.handlers

import com.kutv.music.bridge.BridgeHandler
import com.kutv.music.bridge.mappers.BridgeTrackIds
import com.kutv.music.bridge.mappers.ContentJsonMapper
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ShareTarget
import com.kutv.music.data.TvContentRepository
import org.json.JSONObject

class ShareBridgeHandler(private val repository: TvContentRepository) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "share.resolve" to BridgeHandler { payload ->
            val link = ContentBridgeSupport.requireLink(payload)
            val target = repository.resolveShare(link)
                .getOrElse { throw ContentBridgeSupport.contentError(it, "share.resolve") }
            ContentJsonMapper.shareTarget(target)
        },
        "share.create" to BridgeHandler { payload ->
            val kind = ContentBridgeSupport.requireContentKind(payload)
            val id = if (kind == ContentKind.SONG) {
                val rawId = payload.optString("id")
                require(rawId.isNotBlank()) { "id is required" }
                runCatching { BridgeTrackIds.nativeId(rawId) }
                    .getOrElse { ContentBridgeSupport.requireId(payload, "song") }
            } else {
                ContentBridgeSupport.requireId(payload, kind.name.lowercase())
            }
            val projection = repository.shareProjection(ShareTarget(kind, id))
                .getOrElse { throw ContentBridgeSupport.contentError(it, "share.create") }
            JSONObject()
                .put("link", projection.url)
                .put("qrImageDataUrl", projection.qrImageDataUrl)
                .put("clipboardAvailable", projection.clipboardAvailable)
        },
    )

    companion object {
        val METHODS = setOf("share.resolve", "share.create")
    }
}
