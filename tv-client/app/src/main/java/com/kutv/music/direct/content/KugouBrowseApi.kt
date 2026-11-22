package com.kutv.music.direct.content

import com.kutv.music.core.model.SearchCategory
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct browse endpoints for discover / daily recommendation / private FM /
 * rankings / six-category search. Every method returns the raw upstream
 * [JSONObject]; mapping into DTOs is handled by [BrowseMapper] so transport
 * stays a thin, verifiable layer.
 */
class KugouBrowseApi(
    private val executor: KugouRequestExecutor,
    private val midProvider: () -> String,
) {
    suspend fun search(query: String, category: SearchCategory, page: Int): JSONObject {
        require(query.isNotBlank()) { "search query must not be blank" }
        require(page > 0) { "page must be positive" }
        return executor.execute(
            KugouRequest(
                method = "GET",
                baseUrl = GATEWAY,
                path = searchPath(category),
                query = linkedMapOf(
                    "albumhide" to 0,
                    "iscorrection" to 1,
                    "keyword" to query,
                    "nocollect" to 0,
                    "page" to page,
                    "pagesize" to 30,
                    "platform" to "AndroidFilter",
                ),
                headers = mapOf("x-router" to "complexsearch.kugou.com"),
                // Echo carries its cookie credentials for all six search types.
                // Without them the live provider rejects search with code 152.
                includeCredentials = true,
            ),
        ).body
    }

    suspend fun hotSearch(): JSONObject = executor.execute(
        KugouRequest(
            method = "GET",
            baseUrl = GATEWAY,
            path = "/api/v3/search/hot_tab",
            query = linkedMapOf("navid" to 1, "plat" to 2),
            headers = mapOf("x-router" to "msearch.kugou.com"),
            includeCredentials = true,
        ),
    ).body

    suspend fun dailyRecommendations(): JSONObject = executor.execute(
        KugouRequest(
            method = "POST",
            baseUrl = GATEWAY,
            path = "/everyday_song_recommend",
            query = mapOf("platform" to "ios"),
            // Echo sends zero body bytes here. JSON "{}" is rejected with 200103.
            body = null,
            headers = mapOf("x-router" to "everydayrec.service.kugou.com"),
            includeCredentials = true,
        ),
    ).body

    suspend fun privateFm(action: String, hash: String? = null, songId: String? = null): JSONObject {
        require(action.isNotBlank()) { "action must not be blank" }
        val clienttime = System.currentTimeMillis()
        val body = JSONObject()
            .put("appid", KugouSigner.APP_ID)
            .put("clienttime", clienttime)
            .put("mid", midProvider())
            .put("action", action)
            .put("recommend_source_locked", 0)
            .put("song_pool_id", 0)
            .put("callerid", 0)
            .put("m_type", 1)
            .put("platform", "ios")
            .put("area_code", 1)
            .put("remain_songcnt", 0)
            .put("clientver", KugouSigner.CLIENT_VERSION)
            .put("is_overplay", 0)
            .put("mode", "normal")
            .put("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a")
            .put("key", KugouSigner.signParamsKey(clienttime))
        hash?.let { body.put("hash", it) }
        songId?.let { body.put("songid", it) }
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v2/personal_recommend",
                body = body,
                headers = mapOf("x-router" to "persnfm.service.kugou.com"),
            ),
        ).body
    }

    suspend fun rankings(): JSONObject = executor.execute(
        KugouRequest(
            method = "GET",
            baseUrl = GATEWAY,
            path = "/ocean/v6/rank/list",
            query = linkedMapOf("plat" to 2, "withsong" to 1, "parentid" to 0),
        ),
    ).body

    suspend fun rankingTracks(rankId: String, page: Int): JSONObject {
        require(rankId.isNotBlank()) { "rankId must not be blank" }
        require(page > 0) { "page must be positive" }
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/openapi/kmr/v2/rank/audio",
                body = JSONObject()
                    .put("show_portrait_mv", 1)
                    .put("show_type_total", 1)
                    .put("filter_original_remarks", 1)
                    .put("area_code", 1)
                    .put("pagesize", 30)
                    .put("rank_cid", 0)
                    .put("type", 1)
                    .put("page", page)
                    .put("rank_id", rankId),
                headers = mapOf("kg-tid" to "369"),
            ),
        ).body
    }

    suspend fun newSongs(page: Int): JSONObject {
        require(page > 0) { "page must be positive" }
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/musicadservice/container/v1/newsong_publish",
                body = JSONObject()
                    .put("rank_id", 21608)
                    .put("userid", 0)
                    .put("page", page)
                    .put("pagesize", 30)
                    .put("tags", JSONArray()),
            ),
        ).body
    }

    /** Echo server/module/artist_lists.js, including the grouped singer response. */
    suspend fun artists(): JSONObject = executor.execute(
        KugouRequest(
            method = "GET", baseUrl = GATEWAY, path = "/ocean/v6/singer/list",
            query = mapOf("musician" to 0, "sextype" to 0, "showtype" to 2, "type" to 0, "hotsize" to 30),
        ),
    ).body

    suspend fun newAlbums(page: Int): JSONObject {
        require(page > 0) { "page must be positive" }
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/musicadservice/v1/mobile_newalbum_sp",
                body = JSONObject()
                    .put("apiver", 20)
                    .put("page", page)
                    .put("pagesize", 30)
                    .put("withpriv", 1),
            ),
        ).body
    }

    suspend fun newPlaylists(page: Int): JSONObject {
        require(page > 0) { "page must be positive" }
        val clienttime = System.currentTimeMillis() / 1000L
        val body = JSONObject()
            .put("appid", KugouSigner.APP_ID)
            .put("mid", midProvider())
            .put("clientver", KugouSigner.CLIENT_VERSION)
            .put("platform", "android")
            .put("clienttime", clienttime)
            .put("userid", 0)
            .put("module_id", 1)
            .put("page", page)
            .put("pagesize", 30)
            .put("key", KugouSigner.signParamsKey(clienttime))
            .put(
                "special_recommend",
                JSONObject()
                    .put("withtag", 1)
                    .put("withsong", 1)
                    .put("sort", 1)
                    .put("ugc", 1)
                    .put("is_selected", 0)
                    .put("withrecommend", 1)
                    .put("area_code", 1)
                    .put("categoryid", 0),
            )
            .put("req_multi", 1)
            .put("retrun_min", 5)
            .put("return_special_falg", 1)
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v2/special_recommend",
                body = body,
                headers = mapOf("x-router" to "specialrec.service.kugou.com"),
            ),
        ).body
    }

    private fun searchPath(category: SearchCategory): String = when (category) {
        SearchCategory.SONG -> "/v3/search/song"
        SearchCategory.PLAYLIST -> "/v1/search/special"
        SearchCategory.ALBUM -> "/v1/search/album"
        SearchCategory.ARTIST -> "/v1/search/author"
        SearchCategory.LYRIC -> "/v1/search/lyric"
        SearchCategory.MV -> "/v1/search/mv"
    }

    private companion object {
        const val GATEWAY = "https://gateway.kugou.com"
    }
}
