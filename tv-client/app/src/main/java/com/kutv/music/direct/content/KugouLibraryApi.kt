package com.kutv.music.direct.content

import com.kutv.music.data.DirectLoginRequiredException
import com.kutv.music.direct.KugouCredentials
import com.kutv.music.direct.KugouCrypto
import com.kutv.music.direct.KugouCryptoExtensions
import com.kutv.music.direct.KugouDeviceIdentity
import com.kutv.music.direct.KugouNetworkErrorKind
import com.kutv.music.direct.KugouNetworkException
import com.kutv.music.direct.KugouRawRequestExecutor
import com.kutv.music.direct.KugouRawResponse
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouSignMode
import com.kutv.music.direct.KugouSigner
import com.kutv.music.direct.PlaylistCiphertext
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct KuGou endpoints for the library/profile/share domain.
 *
 * Every login-required method throws [DirectLoginRequiredException] when the injected
 * [credentialsProvider] returns null. Cloud and playlist-delete endpoints use the same
 * AES+RSA envelope as [com.kutv.music.direct.KugouDeviceRegistrar]: the AES ciphertext
 * is sent as the Base64 body string and the RSA-wrapped key rides the `p` query param.
 */
class KugouLibraryApi(
    private val executor: KugouRequestExecutor,
    private val rawExecutor: KugouRawRequestExecutor,
    private val credentialsProvider: () -> KugouCredentials?,
    private val deviceProvider: () -> KugouDeviceIdentity,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val randomKey: () -> String = { KugouCryptoExtensions.randomString(16) },
) {
    fun currentUserId(): String = requireCredentials().userId

    suspend fun history(): JSONObject {
        val credentials = requireCredentials()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/playhistory/v1/get_songs",
                body = JSONObject()
                    .put("token", credentials.token)
                    .put("userid", uid(credentials))
                    .put("source_classify", "app")
                    .put("to_subdivide_sr", 1),
                includeCredentials = true,
            ),
        ).body
    }

    suspend fun userPlaylists(userId: Long?, page: Int, pageSize: Int): JSONObject {
        val credentials = requireCredentials()
        val targetUserId = userId ?: uid(credentials)
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v7/get_all_list",
                query = mapOf("plat" to 1),
                headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
                body = JSONObject()
                    .put("userid", targetUserId)
                    .put("token", credentials.token)
                    .put("total_ver", 979)
                    .put("type", 2)
                    .put("page", page)
                    .put("pagesize", pageSize),
                includeCredentials = true,
            ),
        ).body.also { raw ->
            if (raw.has("status") && raw.optInt("status", 0) != 1) {
                throw LibraryReadException(LibraryReadException.Stage.UPSTREAM_STATUS)
            }
        }
    }

    private fun requireSuccessfulStatus(raw: JSONObject) {
        if (raw.has("status") && raw.optInt("status", 0) != 1) {
            throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        }
    }

    suspend fun playlistTracks(listId: String, page: Int, pageSize: Int): JSONObject {
        val credentials = requireCredentials()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v4/get_list_all_file",
                headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
                body = JSONObject()
                    .put("listid", listId)
                    .put("userid", uid(credentials))
                    .put("area_code", 1)
                    .put("show_relate_goods", 0)
                    .put("pagesize", pageSize)
                    .put("allplatform", 1)
                    .put("show_cover", 1)
                    .put("type", 0)
                    .put("token", credentials.token)
                    .put("page", page),
                includeCredentials = true,
            ),
        ).body.also(::requireSuccessfulStatus)
    }

    suspend fun cloudList(page: Int, pageSize: Int): JSONObject {
        val credentials = requireCredentials()
        return encryptedCloud(
            path = "/v1/get_list",
            plaintext = JSONObject()
                .put("page", page)
                .put("pagesize", pageSize)
                .put("getkmr", 1),
            credentials = credentials,
        )
    }

    /** Echo favoritesActions + playlist_track_all.js read by gid, never by listid. */
    suspend fun playlistCollectionTracks(collectionId: String, page: Int, pageSize: Int): JSONObject {
        requireCredentials()
        return executor.execute(
            KugouRequest(
                method = "GET", baseUrl = GATEWAY, path = "/pubsongs/v2/get_other_list_file_nofilt",
                query = mapOf(
                    "area_code" to 1, "begin_idx" to (page - 1) * pageSize, "plat" to 1,
                    "type" to 1, "mode" to 1, "personal_switch" to 1,
                    "extend_fields" to "abtags,hot_cmt,popularization", "pagesize" to pageSize,
                    "global_collection_id" to collectionId,
                ),
                includeCredentials = true,
            ),
        ).body.also(::requireSuccessfulStatus)
    }

    suspend fun cloudDelete(kvId: String, albumAudioId: String): JSONObject {
        val credentials = requireCredentials()
        val item = JSONObject()
            .put("kv_id", kvId.toLongOrNull() ?: kvId)
            .put("album_audio_id", albumAudioId.toLongOrNull() ?: 0L)
        return encryptedCloud(
            path = "/v1/del_files",
            plaintext = JSONObject().put("data", JSONArray().put(item)),
            credentials = credentials,
        )
    }

    suspend fun purchasedSongs(page: Int, pageSize: Int): JSONObject {
        val credentials = requireCredentials()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/openapi/copyright/v1/audio/get_goods",
                body = JSONObject()
                    .put("appid", KugouSigner.APP_ID)
                    .put("userid", uid(credentials))
                    .put("token", credentials.token)
                    .put("page", page)
                    .put("pagesize", pageSize)
                    .put("clientver", KugouSigner.CLIENT_VERSION)
                    .put("deleted", 0)
                    .put("need_audio_info", 1)
                    .put("area_code", "1"),
                includeCredentials = true,
            ),
        ).body
    }

    suspend fun purchasedAlbums(page: Int, pageSize: Int): JSONObject {
        val credentials = requireCredentials()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/openapi/v1/copyright/get_album_goods",
                body = JSONObject()
                    .put("appid", KugouSigner.APP_ID)
                    .put("userid", uid(credentials))
                    .put("token", credentials.token)
                    .put("page", page)
                    .put("pagesize", pageSize)
                    .put("clientver", KugouSigner.CLIENT_VERSION)
                    .put("deleted", 0),
                includeCredentials = true,
            ),
        ).body
    }

    suspend fun profile(userId: Long): JSONObject {
        val credentials = requireCredentials()
        val clientTime = clockSeconds()
        val p = KugouCrypto.rsaRawEncrypt(
            JSONObject()
                .put("token", credentials.token)
                .put("clienttime", clientTime)
                .toString(),
        ).uppercase()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v3/get_my_info",
                query = mapOf("plat" to 1),
                headers = mapOf("x-router" to "usercenter.kugou.com"),
                body = JSONObject()
                    .put("visit_time", clientTime)
                    .put("usertype", 1)
                    .put("p", p)
                    .put("userid", userId),
                includeCredentials = true,
            ),
        ).body
    }

    suspend fun followArtists(): JSONObject {
        val credentials = requireCredentials()
        val clientTime = clockSeconds()
        val p = KugouCrypto.rsaRawEncrypt(
            JSONObject()
                .put("clienttime", clientTime)
                .put("token", credentials.token)
                .toString(),
        ).uppercase()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v4/follow_list",
                query = mapOf("plat" to 1),
                headers = mapOf("x-router" to "relationuser.kugou.com"),
                body = JSONObject()
                    .put("merge", 2)
                    .put("need_iden_type", 1)
                    .put("ext_params", "k_pic,jumptype,singerid,score")
                    .put("userid", uid(credentials))
                    .put("type", 0)
                    .put("id_type", 0)
                    .put("p", p),
                includeCredentials = true,
            ),
        ).body
    }

    suspend fun collectVideos(page: Int, pageSize: Int): JSONObject {
        val credentials = requireCredentials()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/collectservice/v2/collect_list_mixvideo",
                query = mapOf("plat" to 1),
                body = JSONObject()
                    .put("userid", uid(credentials))
                    .put("token", credentials.token)
                    .put("page", page)
                    .put("pagesize", pageSize),
                includeCredentials = true,
            ),
        ).body
    }

    suspend fun playlistTracksAdd(
        listId: String,
        name: String,
        hash: String,
        albumId: String?,
        mixsongId: String,
    ): JSONObject {
        val credentials = requireCredentials()
        val clientTime = clockSeconds()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/cloudlist.service/v6/add_song",
                query = mapOf("last_time" to clientTime, "last_area" to "gztx"),
                body = JSONObject()
                    .put("userid", uid(credentials))
                    .put("token", credentials.token)
                    .put("listid", listId)
                    .put("list_ver", 0)
                    .put("type", 0)
                    .put("slow_upload", 1)
                    .put("scene", "false;null")
                    .put(
                        "data",
                        JSONArray().put(
                            JSONObject()
                                .put("number", 1)
                                .put("name", name)
                                .put("hash", hash)
                                .put("size", 0)
                                .put("sort", 0)
                                .put("timelen", 0)
                                .put("bitrate", 0)
                                .put("album_id", albumId?.toLongOrNull() ?: 0L)
                                .put("mixsongid", mixsongId.toLongOrNull() ?: 0L),
                        ),
                    ),
                includeCredentials = true,
            ),
        ).body.also(::requireMutationSuccess)
    }

    suspend fun playlistTracksDel(listId: String, fileId: String): JSONObject {
        val credentials = requireCredentials()
        val numericFileId = fileId.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("有效的歌单文件编号不能为空")
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v4/delete_songs",
                headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
                body = JSONObject()
                    .put("listid", listId)
                    .put("userid", uid(credentials))
                    .put("data", JSONArray().put(JSONObject().put("fileid", numericFileId)))
                    .put("type", 0)
                    .put("token", credentials.token)
                    .put("list_ver", 0),
                includeCredentials = true,
            ),
        ).body.also(::requireMutationSuccess)
    }

    // Echo accepts song writes only when status === 1. HTTP 200 alone is not an acknowledgement.
    private fun requireMutationSuccess(raw: JSONObject) {
        if (raw.optInt("status", 0) != 1 || raw.optInt("error_code", 0) != 0 || raw.optInt("errcode", 0) != 0) {
            throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        }
    }

    suspend fun addPlaylist(
        listCreateListId: String,
        type: Int,
        source: Int,
        name: String? = null,
        listCreateUserId: String? = null,
    ): JSONObject {
        val credentials = requireCredentials()
        val clientTime = clockSeconds()
        val body = JSONObject()
            .put("userid", uid(credentials))
            .put("token", credentials.token)
            .put("total_ver", 0)
            .put("type", type)
            .put("source", source)
            .put("is_pri", 0)
            .put("list_create_listid", listCreateListId)
            .put("list_create_gid", "")
            .put("from_shupinmv", 0)
        if (name != null) body.put("name", name)
        if (listCreateUserId != null) body.put("list_create_userid", listCreateUserId)
        val query = if (type == 0) mapOf("last_time" to clientTime, "last_area" to "gztx") else emptyMap()
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/cloudlist.service/v5/add_list",
                query = query,
                body = body,
                includeCredentials = true,
            ),
        ).body
    }

    suspend fun deletePlaylist(listId: String): JSONObject {
        val credentials = requireCredentials()
        val clientTime = clockSeconds()
        val plaintext = JSONObject()
            .put("listid", listId.toLongOrNull() ?: 0L)
            .put("total_ver", 0)
            .put("type", 1)
        val encrypted = KugouCrypto.playlistEncrypt(plaintext)
        val p = rsaWrapKey(encrypted.key, credentials)
        val raw = rawExecutor.executeRaw(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v2/delete_list",
                query = linkedMapOf(
                    "clienttime" to clientTime,
                    "key" to KugouSigner.signParamsKey(clientTime.toString()),
                    "last_area" to "gztx",
                    "clientver" to KugouSigner.CLIENT_VERSION,
                    "appid" to KugouSigner.APP_ID,
                    "last_time" to clientTime,
                    "p" to p,
                ),
                headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
                body = encrypted.encoded,
                signMode = KugouSignMode.ANDROID,
            ),
        )
        if (raw.statusCode !in 200..299) throw KugouNetworkException(KugouNetworkErrorKind.HTTP, raw.statusCode)
        return decodeEncrypted(raw, encrypted.key)
    }

    suspend fun artistFollow(singerId: String): JSONObject =
        artistMutation("/followservice/v3/follow_singer", singerId, includeClientTimeQuery = true)

    suspend fun artistUnfollow(singerId: String): JSONObject =
        artistMutation("/followservice/v3/unfollow_singer", singerId, includeClientTimeQuery = false)

    private suspend fun artistMutation(
        path: String,
        singerId: String,
        includeClientTimeQuery: Boolean,
    ): JSONObject {
        val credentials = requireCredentials()
        val clientTime = clockSeconds()
        val singerIdNumber = singerId.toLongOrNull() ?: 0L
        val encrypted = KugouCryptoExtensions.cryptoAesEncrypt(
            JSONObject().put("singerid", singerIdNumber).put("token", credentials.token),
            optKey = randomKey(),
        )
        val p = KugouCrypto.rsaEncryptPkcs1(
            JSONObject().put("clienttime", clientTime).put("key", encrypted.key).toString(),
            KugouCrypto.LITE_PUBLIC_RSA_KEY,
        )
        return executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = path,
                query = if (includeClientTimeQuery) mapOf("clienttime" to clientTime) else emptyMap(),
                body = JSONObject()
                    .put("plat", 0)
                    .put("userid", uid(credentials))
                    .put("singerid", singerIdNumber)
                    .put("source", 7)
                    .put("p", p)
                    .put("params", encrypted.str),
                includeCredentials = true,
            ),
        ).body
    }

    private suspend fun encryptedCloud(path: String, plaintext: JSONObject, credentials: KugouCredentials): JSONObject {
        val clientTime = clockSeconds()
        val mid = KugouCrypto.deriveMid(deviceProvider().mid)
        val encrypted = KugouCrypto.playlistEncrypt(plaintext)
        val p = rsaWrapKey(encrypted.key, credentials)
        val raw = rawExecutor.executeRaw(
            KugouRequest(
                method = "POST",
                baseUrl = CLOUD_BASE,
                path = path,
                query = linkedMapOf(
                    "clienttime" to clientTime,
                    "mid" to mid,
                    "key" to KugouSigner.signParamsKey(clientTime.toString()),
                    "clientver" to KugouSigner.CLIENT_VERSION,
                    "appid" to KugouSigner.APP_ID,
                    "p" to p,
                ),
                body = encrypted.encoded,
                signMode = KugouSignMode.ANDROID,
                clearDefaultParams = true,
            ),
        )
        if (raw.statusCode !in 200..299) throw KugouNetworkException(KugouNetworkErrorKind.HTTP, raw.statusCode)
        return decodeEncrypted(raw, encrypted.key)
    }

    private fun rsaWrapKey(key: String, credentials: KugouCredentials): String =
        KugouCrypto.rsaEncryptPkcs1(
            JSONObject()
                .put("aes", key)
                .put("uid", credentials.userId)
                .put("token", credentials.token)
                .toString(),
            KugouCrypto.LITE_PUBLIC_RSA_KEY,
        ).uppercase()

    private fun decodeEncrypted(raw: KugouRawResponse, key: String): JSONObject {
        val bytes = raw.bodyBytes ?: raw.body.toByteArray(Charsets.ISO_8859_1)
        val decoded = runCatching {
            KugouCrypto.playlistDecrypt(PlaylistCiphertext(key, Base64.getEncoder().encodeToString(bytes)))
        }.getOrNull()
        val json = when (decoded) {
            is JSONObject -> decoded
            is JSONArray -> JSONObject().put("data", decoded)
            else -> runCatching { JSONObject(raw.body) }.getOrNull()
        } ?: throw KugouNetworkException(KugouNetworkErrorKind.DATA, raw.statusCode)
        if (json.optInt("error_code", 0) == 20018) {
            throw KugouNetworkException(KugouNetworkErrorKind.SESSION_EXPIRED, raw.statusCode)
        }
        return json
    }

    private fun requireCredentials(): KugouCredentials =
        credentialsProvider() ?: throw DirectLoginRequiredException()

    private fun uid(credentials: KugouCredentials): Long = credentials.userId.toLongOrNull() ?: 0L

    private companion object {
        const val GATEWAY = "https://gateway.kugou.com"
        const val CLOUD_BASE = "https://mcloudservice.kugou.com"
    }
}
