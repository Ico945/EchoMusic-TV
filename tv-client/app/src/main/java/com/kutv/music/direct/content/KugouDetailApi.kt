package com.kutv.music.direct.content

import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouSigner
import com.kutv.music.direct.KugouCredentials
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct KuGou detail-family endpoints (playlist / album / artist / song / MV).
 *
 * Every method returns the raw response body; parsing is left to [DetailMapper]
 * so malformed payloads fail closed without throwing sensitive data.
 */
class KugouDetailApi(
    private val executor: KugouRequestExecutor,
    private val credentialsProvider: () -> KugouCredentials? = { null },
    private val midProvider: () -> String,
) {
    private fun clockSeconds(): Long = System.currentTimeMillis() / 1_000L

    suspend fun playlistMeta(id: String): JSONObject {
        val credentials = credentialsProvider()
        return executor.execute(
            KugouRequest(
            method = "POST",
            baseUrl = GATEWAY,
            path = "/v3/get_list_info",
            headers = mapOf("x-router" to "pubsongs.kugou.com"),
            body = JSONObject()
                .put("data", JSONArray().put(JSONObject().put("global_collection_id", id)))
                .put("userid", credentials?.userId?.toLongOrNull() ?: 0L)
                .put("token", credentials?.token.orEmpty()),
            includeCredentials = credentials != null,
            ),
        ).body
    }

    suspend fun playlistTracks(id: String, page: Int): JSONObject = executor.execute(
        KugouRequest(
            method = "GET",
            baseUrl = GATEWAY,
            path = "/pubsongs/v2/get_other_list_file_nofilt",
            query = linkedMapOf(
                "area_code" to 1,
                "begin_idx" to ((page - 1) * PAGE_SIZE),
                "plat" to 1,
                "type" to 1,
                "mode" to 1,
                "personal_switch" to 1,
                "extend_fields" to "abtags,hot_cmt,popularization",
                "pagesize" to PAGE_SIZE,
                "global_collection_id" to id,
            ),
            includeCredentials = credentialsProvider() != null,
        ),
    ).body

    suspend fun albumMeta(id: String): JSONObject = executor.execute(
        KugouRequest(
            method = "POST",
            baseUrl = GATEWAY,
            path = "/kmr/v2/albums",
            headers = mapOf("x-router" to "openapi.kugou.com", "kg-tid" to "255"),
            body = JSONObject()
                .put("data", JSONArray().put(JSONObject().put("album_id", id)))
                .put("is_buy", 0)
                .put("fields", ALBUM_FIELDS),
        ),
    ).body

    suspend fun albumTracks(id: String, page: Int): JSONObject = executor.execute(
        KugouRequest(
            method = "POST",
            baseUrl = GATEWAY,
            path = "/v1/album_audio/lite",
            headers = mapOf("x-router" to "openapi.kugou.com", "kg-tid" to "255"),
            body = JSONObject()
                .put("album_id", id)
                .put("is_buy", 0)
                .put("page", page)
                .put("pagesize", PAGE_SIZE),
        ),
    ).body

    suspend fun artistMeta(id: String): JSONObject = executor.execute(
        KugouRequest(
            method = "POST",
            baseUrl = GATEWAY,
            path = "/kmr/v3/author",
            headers = mapOf("x-router" to "openapi.kugou.com", "kg-tid" to "36"),
            body = JSONObject().put("author_id", id),
        ),
    ).body

    suspend fun artistSongs(id: String, page: Int): JSONObject {
        val clientTime = clockSeconds()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = OPENAPI,
                path = "/kmr/v1/audio_group/author",
                headers = mapOf("x-router" to "openapi.kugou.com", "kg-tid" to "220"),
                body = JSONObject()
                    .put("appid", KugouSigner.APP_ID)
                    .put("clientver", KugouSigner.CLIENT_VERSION)
                    .put("mid", midProvider())
                    .put("clienttime", clientTime)
                    .put("key", KugouSigner.signParamsKey(clientTime))
                    .put("author_id", id)
                    .put("pagesize", PAGE_SIZE)
                    .put("page", page)
                    .put("sort", 1) // EchoMusic artist_audios: 1 hot, 2 newest.
                    .put("area_code", "all"),
            ),
        ).body
    }

    suspend fun artistAlbums(id: String, page: Int): JSONObject = executor.execute(
        KugouRequest(
            method = "POST",
            baseUrl = GATEWAY,
            path = "/kmr/v1/author/albums",
            headers = mapOf("x-router" to "openapi.kugou.com", "kg-tid" to "36"),
            body = JSONObject()
                .put("author_id", id)
                .put("pagesize", PAGE_SIZE)
                .put("page", page)
                .put("sort", 1)
                .put("category", 1)
                .put("area_code", "all"),
        ),
    ).body

    suspend fun artistVideos(id: String, page: Int): JSONObject = executor.execute(
        KugouRequest(
            method = "GET",
            baseUrl = OPENAPI_CDN,
            path = "/kmr/v1/author/videos",
            query = linkedMapOf(
                "author_id" to id,
                "is_fanmade" to "",
                "tag_idx" to "",
                "pagesize" to PAGE_SIZE,
                "page" to page,
            ),
        ),
    ).body

    /**
     * Numeric ids resolve through the openapi `krm_audio` endpoint (entity_id);
     * any other id is treated as a hash and resolves through `audio.js` on the
     * kmr.service host with a millisecond `signParamsKey`.
     */
    suspend fun songMeta(id: String): JSONObject {
        if (id.isNotEmpty() && id.all(Char::isDigit)) {
            return executor.execute(
                KugouRequest(
                    method = "POST",
                    baseUrl = GATEWAY,
                    path = "/kmr/v2/audio",
                    headers = mapOf("x-router" to "openapi.kugou.com", "KG-TID" to "238"),
                    body = JSONObject()
                        .put("data", JSONArray().put(JSONObject().put("entity_id", id.toLongOrNull() ?: id)))
                        .put("fields", "base"),
                ),
            ).body
        }
        val clientTimeMs = System.currentTimeMillis()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = KMR_SERVICE,
                path = "/v1/audio/audio",
                headers = mapOf("x-router" to "kmr.service.kugou.com", "content-type" to "application/json"),
                body = JSONObject()
                    .put("appid", KugouSigner.APP_ID)
                    .put("clienttime", clientTimeMs)
                    .put("clientver", KugouSigner.CLIENT_VERSION)
                    .put("data", JSONArray().put(JSONObject().put("hash", id).put("audio_id", 0)))
                    .put("dfid", "-")
                    .put("key", KugouSigner.signParamsKey(clientTimeMs))
                    .put("mid", midProvider()),
            ),
        ).body
    }

    suspend fun mvMeta(id: String): JSONObject {
        val clientTime = clockSeconds()
        val mid = midProvider()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v1/video",
                headers = mapOf("x-router" to "kmr.service.kugou.com"),
                body = JSONObject()
                    .put("appid", KugouSigner.APP_ID)
                    .put("clientver", KugouSigner.CLIENT_VERSION)
                    .put("clienttime", clientTime)
                    .put("mid", mid)
                    // Simplified from reference md5(dfid + mid); dfid is "-" here.
                    .put("uuid", KugouSigner.md5(mid))
                    .put("dfid", "-")
                    .put("key", KugouSigner.signParamsKey(clientTime))
                    .put("show_resolution", 1)
                    .put("data", JSONArray().put(JSONObject().put("video_id", id))),
            ),
        ).body
    }

    private companion object {
        const val GATEWAY = "https://gateway.kugou.com"
        const val OPENAPI = "https://openapi.kugou.com"
        const val OPENAPI_CDN = "https://openapicdn.kugou.com"
        const val KMR_SERVICE = "https://kmr.service.kugou.com"
        const val PAGE_SIZE = 30
        const val ALBUM_FIELDS =
            "album_id,album_name,publish_date,sizable_cover,intro,language,is_publish,heat,type,quality,authors,exclusive,author_name,trans_param"
    }
}
