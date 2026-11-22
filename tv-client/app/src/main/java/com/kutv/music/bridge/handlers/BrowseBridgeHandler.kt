package com.kutv.music.bridge.handlers

import com.kutv.music.bridge.BridgeHandler
import com.kutv.music.bridge.mappers.ContentJsonMapper
import com.kutv.music.data.TvContentRepository
import org.json.JSONArray
import org.json.JSONObject

class BrowseBridgeHandler(private val repository: TvContentRepository) {
    fun handlers(): Map<String, BridgeHandler> = mapOf(
        "content.explore" to BridgeHandler { payload ->
            require(payload.length() == 0) { "Payload must be empty" }
            val explore = repository.explore().getOrElse { throw ContentBridgeSupport.contentError(it, "explore") }
            JSONObject()
                .put("newSongs", cards(explore.newSongs))
                .put("newAlbums", cards(explore.newAlbums))
                .put("newPlaylists", cards(explore.newPlaylists))
                .put("artists", cards(explore.artists))
                .put("rankings", JSONArray().apply { explore.rankings.forEach { put(ContentJsonMapper.ranking(it)) } })
                .put("failedSections", JSONArray(explore.failedSections.map { it.wireName }))
        },
        "content.dailyRecommendations" to BridgeHandler { payload ->
            require(payload.length() == 0) { "Payload must be empty" }
            val tracks = repository.dailyRecommendations().getOrElse { throw ContentBridgeSupport.contentError(it, "dailyRecommendations") }
            JSONObject().put("items", tracks(tracks))
        },
        "content.privateFm" to BridgeHandler { payload ->
            require(payload.length() == 0) { "Payload must be empty" }
            val tracks = repository.privateFm().getOrElse { throw ContentBridgeSupport.contentError(it, "fm") }
            JSONObject().put("items", tracks(tracks))
        },
        "content.privateFmDiscard" to BridgeHandler { payload ->
            val trackId = ContentBridgeSupport.requireTrackId(payload)
            repository.discardPrivateFm(trackId).getOrElse { throw ContentBridgeSupport.contentError(it, "fm") }
            JSONObject()
        },
        "content.hotSearch" to BridgeHandler { payload ->
            require(payload.length() == 0) { "Payload must be empty" }
            val terms = repository.hotSearch().getOrElse { throw ContentBridgeSupport.contentError(it, "search:hot") }
            JSONObject().put("terms", JSONArray(terms))
        },
        "content.ranking" to BridgeHandler { payload ->
            val id = ContentBridgeSupport.requireId(payload)
            val cursor = ContentBridgeSupport.requireCursor(payload)
            val page = repository.ranking(id, cursor).getOrElse { throw ContentBridgeSupport.contentError(it, "ranking") }
            JSONObject()
                .put("id", id)
                .put("items", tracks(page.items))
                .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                .put("total", page.total ?: JSONObject.NULL)
        },
        "content.search" to BridgeHandler { payload ->
            val query = ContentBridgeSupport.requireQuery(payload)
            val category = ContentBridgeSupport.requireSearchCategory(payload)
            val cursor = ContentBridgeSupport.requireCursor(payload)
            val page = repository.search(query, category, cursor)
                .getOrElse { throw ContentBridgeSupport.contentError(it, "search:${category.name.lowercase()}") }
            JSONObject()
                .put("category", category.name.lowercase())
                .put("items", cards(page.items))
                .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                .put("total", page.total ?: JSONObject.NULL)
        },
    )

    companion object {
        val METHODS = setOf(
            "content.explore",
            "content.dailyRecommendations",
            "content.privateFm",
            "content.privateFmDiscard",
            "content.hotSearch",
            "content.ranking",
            "content.search",
        )
    }

    private fun cards(items: List<com.kutv.music.core.model.ContentCard>): JSONArray =
        JSONArray().apply { items.forEach { put(ContentJsonMapper.card(it)) } }

    private fun tracks(items: List<com.kutv.music.core.model.Track>): JSONArray =
        JSONArray().apply { items.forEach { put(ContentJsonMapper.track(it)) } }
}
