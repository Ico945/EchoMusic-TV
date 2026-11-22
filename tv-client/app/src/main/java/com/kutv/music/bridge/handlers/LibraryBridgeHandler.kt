package com.kutv.music.bridge.handlers

import com.kutv.music.bridge.BridgeHandler
import com.kutv.music.bridge.mappers.BridgeTrackIds
import com.kutv.music.bridge.mappers.ContentJsonMapper
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.MutationTarget
import com.kutv.music.core.model.PlaylistMutation
import com.kutv.music.data.TvContentRepository
import org.json.JSONArray
import org.json.JSONObject

class LibraryBridgeHandler(private val repository: TvContentRepository) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "library.get" to BridgeHandler { payload ->
            require(payload.length() == 0) { "Payload must be empty" }
            val summary = repository.library().getOrElse { throw ContentBridgeSupport.contentError(it, "library") }
            ContentJsonMapper.librarySummary(summary)
        },
        // Read-only inventory, independent of created/collected classification.
        "library.playlists" to BridgeHandler { payload ->
            require(payload.keys().asSequence().all { it == "cursor" }) { "Unexpected payload field" }
            val page = repository.myPlaylists(ContentBridgeSupport.requireCursor(payload))
                .getOrElse { throw ContentBridgeSupport.contentError(it, "library:playlists") }
            JSONObject().put("items", cards(page.items))
                .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                .put("total", page.total ?: JSONObject.NULL)
                .also { putSyncInfo(it, page.librarySync) }
        },
        "library.section" to BridgeHandler { payload ->
            val section = ContentBridgeSupport.requireSection(payload)
            val cursor = ContentBridgeSupport.requireCursor(payload)
            val page = repository.librarySection(section, cursor)
                .getOrElse { throw ContentBridgeSupport.contentError(it, "library:${ContentBridgeSupport.sectionEnumToJson(section)}") }
            JSONObject()
                .put("section", ContentBridgeSupport.sectionEnumToJson(section))
                .put("items", cards(page.items))
                .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                .put("total", page.total ?: JSONObject.NULL)
                .also { putSyncInfo(it, page.librarySync) }
        },
        "library.favorite" to BridgeHandler { payload ->
            val kind = ContentBridgeSupport.requireContentKind(payload)
            val rawId = payload.optString("id")
            require(rawId.isNotBlank()) { "id is required" }
            val favorite = ContentBridgeSupport.requireBoolean(payload, "favorite")
            val requestId = ContentBridgeSupport.requireRequestId(payload)
            val nativeId = if (kind == ContentKind.SONG) resolveSongId(rawId) else ContentBridgeSupport.requireId(payload, kind.name.lowercase())
            val target = MutationTarget(kind, nativeId)
            repository.favoriteWithRequestId(requestId, target, favorite)
                .getOrElse { throw ContentBridgeSupport.contentError(it, "library:favorite") }
            // The UI owns an opaque TV id. Resolve only for the native call;
            // returning the upstream id makes a successful write fail UI validation.
            ContentJsonMapper.favoriteState(if (kind == ContentKind.SONG) MutationTarget(kind, rawId) else target, favorite)
        },
        "library.addToPlaylist" to BridgeHandler { payload ->
            val playlistId = ContentBridgeSupport.requireId(payload, "playlist")
            val trackId = ContentBridgeSupport.requireTrackId(payload)
            ContentBridgeSupport.requireRequestId(payload)
            val mutation = PlaylistMutation(playlistId, resolveSongId(trackId))
            repository.addToPlaylist(mutation)
                .getOrElse { throw ContentBridgeSupport.contentError(it, "library:playlists") }
            JSONObject()
                .put("playlistId", "playlist:$playlistId")
                .put("trackId", trackId)
        },
        "library.deleteCloudItem" to BridgeHandler { payload ->
            val cloudTrackId = ContentBridgeSupport.requireCloudTrackId(payload)
            ContentBridgeSupport.requireRequestId(payload)
            repository.deleteCloudItem(cloudTrackId)
                .getOrElse { throw ContentBridgeSupport.contentError(it, "library:cloud") }
            JSONObject()
        },
    )

    companion object {
        val METHODS = setOf(
            "library.get",
            "library.playlists",
            "library.section",
            "library.favorite",
            "library.addToPlaylist",
            "library.deleteCloudItem",
        )
    }

    private fun resolveSongId(rawId: String): String = runCatching { BridgeTrackIds.nativeId(rawId) }
        .getOrElse {
            require(!rawId.startsWith("song:")) { "song id is invalid" }
            rawId
        }

    private fun cards(items: List<com.kutv.music.core.model.ContentCard>): JSONArray =
        JSONArray().apply { items.forEach { put(ContentJsonMapper.card(it)) } }

    private fun putSyncInfo(target: JSONObject, info: com.kutv.music.core.model.LibrarySyncInfo?) {
        if (info == null) return
        target.put("librarySync", JSONObject().put("page", info.page).put("received", info.received)
            .put("playlists", info.playlists).put("created", info.created).put("collected", info.collected)
            .put("albums", info.albums).put("missingCreator", info.missingCreator))
    }
}
