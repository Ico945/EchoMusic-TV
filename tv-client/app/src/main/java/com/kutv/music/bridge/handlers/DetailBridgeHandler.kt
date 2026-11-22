package com.kutv.music.bridge.handlers

import com.kutv.music.bridge.BridgeHandler
import com.kutv.music.bridge.mappers.BridgeTrackIds
import com.kutv.music.bridge.mappers.ContentJsonMapper
import com.kutv.music.core.model.CommentTarget
import com.kutv.music.core.model.CommentTargetKind
import com.kutv.music.core.model.ContentKind
import com.kutv.music.data.TvContentRepository
import org.json.JSONArray
import org.json.JSONObject

class DetailBridgeHandler(private val repository: TvContentRepository) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "content.detail" to BridgeHandler { payload ->
            val kind = ContentBridgeSupport.requireContentKind(payload)
            val rawId = payload.optString("id")
            require(rawId.isNotBlank()) { "id is required" }
            val nativeId = if (kind == ContentKind.SONG) resolveSongId(rawId) else ContentBridgeSupport.requireId(payload, kind.name.lowercase())
            val detail = repository.detail(kind, nativeId)
                .getOrElse { throw ContentBridgeSupport.contentError(it, "detail:${kind.name.lowercase()}:$nativeId") }
            ContentJsonMapper.detail(detail)
        },
        "content.detailChildren" to BridgeHandler { payload ->
            val kind = ContentBridgeSupport.requireContentKind(payload)
            val id = ContentBridgeSupport.requireId(payload, kind.name.lowercase())
            val tab = ContentBridgeSupport.requireTab(payload)
            val cursor = ContentBridgeSupport.requireCursor(payload)
            val scope = "detail:${kind.name.lowercase()}:$id:$tab"
            when {
                kind == ContentKind.PLAYLIST && tab == "songs" -> {
                    val page = repository.playlistTracks(id, cursor).getOrElse { throw ContentBridgeSupport.contentError(it, scope) }
                    JSONObject()
                        .put("items", tracks(page.items))
                        .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                        .put("total", page.total ?: JSONObject.NULL)
                }
                kind == ContentKind.ALBUM && tab == "songs" -> {
                    val page = repository.albumTracks(id, cursor).getOrElse { throw ContentBridgeSupport.contentError(it, scope) }
                    JSONObject()
                        .put("items", tracks(page.items))
                        .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                        .put("total", page.total ?: JSONObject.NULL)
                }
                kind == ContentKind.ARTIST && tab == "songs" -> {
                    val page = repository.artistTracks(id, cursor).getOrElse { throw ContentBridgeSupport.contentError(it, scope) }
                    // Keep the v1 card projection; add playable tracks instead
                    // of silently changing the existing items field's shape.
                    val items = page.items.map { track -> com.kutv.music.core.model.ContentCard(
                        id = track.id, title = track.title, kind = ContentKind.SONG,
                        subtitle = track.artists.joinToString(" / "), artworkUrl = track.artworkUri,
                    ) }
                    JSONObject().put("items", cards(items)).put("tracks", tracks(page.items))
                        .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                        .put("total", page.total ?: JSONObject.NULL)
                }
                kind == ContentKind.ARTIST && tab in setOf("albums", "mv") -> {
                    val page = repository.artistChildren(id, tab, cursor).getOrElse { throw ContentBridgeSupport.contentError(it, scope) }
                    JSONObject()
                        .put("items", cards(page.items))
                        .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                        .put("total", page.total ?: JSONObject.NULL)
                }
                else -> throw IllegalArgumentException("detailChildren is unsupported for this kind/tab")
            }
        },
        "content.comments" to BridgeHandler { payload ->
            val targetKind = ContentBridgeSupport.requireCommentKind(payload)
            val id = ContentBridgeSupport.requireId(payload, targetKind.name.lowercase())
            val cursor = ContentBridgeSupport.requireCursor(payload)
            val page = repository.comments(
                CommentTarget(
                    when (targetKind) {
                        CommentTargetKind.SONG -> CommentTargetKind.SONG
                        CommentTargetKind.PLAYLIST -> CommentTargetKind.PLAYLIST
                        CommentTargetKind.ALBUM -> CommentTargetKind.ALBUM
                    },
                    id,
                ),
                cursor,
            ).getOrElse { throw ContentBridgeSupport.contentError(it, "comments:${targetKind.name.lowercase()}:$id") }
            ContentJsonMapper.commentPage(page)
        },
    )

    companion object {
        val METHODS = setOf("content.detail", "content.detailChildren", "content.comments")
    }

    private fun resolveSongId(rawId: String): String = runCatching { BridgeTrackIds.nativeId(rawId) }
        .getOrElse {
            require(!rawId.startsWith("song:")) { "song id is invalid" }
            rawId
        }

    private fun cards(items: List<com.kutv.music.core.model.ContentCard>): JSONArray =
        JSONArray().apply { items.forEach { put(ContentJsonMapper.card(it)) } }

    private fun tracks(items: List<com.kutv.music.core.model.Track>): JSONArray =
        JSONArray().apply { items.forEach { put(ContentJsonMapper.track(it)) } }
}
