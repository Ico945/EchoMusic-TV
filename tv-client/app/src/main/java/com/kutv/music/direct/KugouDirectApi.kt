package com.kutv.music.direct

import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import com.kutv.music.data.DirectLoginRequiredException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class KugouPrivilege(
    val playable: Boolean,
    val trial: Boolean,
    val qualities: List<String>,
    val qualityHashes: Map<String, String> = emptyMap(),
)

data class KugouStream(
    val url: String,
    val mimeType: String,
    val quality: String,
)

interface KugouPlaybackGateway {
    suspend fun getPrivilege(hash: String): KugouPrivilege
    suspend fun getSongUrl(hash: String, quality: String): KugouStream
}

data class KugouHomeFeed(
    val hero: Track?,
    val newSongs: List<Track>,
    val daily: List<Track>,
    val history: List<Track>,
)

interface KugouContentGateway {
    suspend fun home(): KugouHomeFeed
    suspend fun newReleases(): List<Track>
    suspend fun library(): List<Track>
    suspend fun search(query: String): List<Track>
    suspend fun lyrics(trackId: String): List<TimedLyricLine>
}

class KugouDirectApi(
    private val executor: KugouRequestExecutor,
    private val credentialProvider: () -> KugouCredentials?,
    private val deviceProvider: (() -> KugouDeviceIdentity)? = null,
    private val ensureDeviceRegistered: (suspend () -> Unit)? = null,
    private val homeShelfTimeoutMs: Long = 8_000L,
) : KugouContentGateway, KugouPlaybackGateway {
    init {
        require(homeShelfTimeoutMs in 1..8_000L)
    }
    private val bindings = linkedMapOf<String, Track>()

    override suspend fun home(): KugouHomeFeed = coroutineScope {
        // Public browse endpoints accept the existing identity even without dfid.
        // Registration failure must not suppress real content available upstream.
        // Run independent shelves together, leaving 2s of the Bridge's 10s
        // budget for dispatch/serialization. A slow shelf cannot discard peers.
        suspend fun shelf(load: suspend () -> List<Track>): Result<List<Track>> = try {
            Result.success(withTimeoutOrNull(homeShelfTimeoutMs) { load() }
                ?: throw KugouNetworkException(KugouNetworkErrorKind.TIMEOUT))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
        val newSongsTask = async { shelf { newReleases() } }
        val dailyTask = async {
            shelf {
                val response = executor.execute(
                    KugouRequest(
                        method = "POST",
                        baseUrl = GATEWAY,
                        path = "/everyday_song_recommend",
                        query = mapOf("platform" to "ios"),
                        headers = mapOf("x-router" to "everydayrec.service.kugou.com"),
                        // Must match Echo: no JSON body, including in the signature.
                        body = null,
                        includeCredentials = credentialProvider() != null,
                    ),
                )
                remember(KugouTrackMapper.extractTracks(response.body))
            }
        }
        val historyTask = async {
            if (credentialProvider() == null) Result.success(emptyList()) else shelf { library() }
        }
        val results = listOf(newSongsTask.await(), dailyTask.await(), historyTask.await())
        val (newSongs, daily, history) = results.map { it.getOrDefault(emptyList()) }
        if (newSongs.isEmpty() && daily.isEmpty() && history.isEmpty()) {
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
        KugouHomeFeed(
            hero = (daily + newSongs + history).firstOrNull(),
            newSongs = newSongs,
            daily = daily,
            history = history,
        )
    }

    override suspend fun newReleases(): List<Track> {
        // No mandatory registration for this public endpoint; upstream still
        // enforces its own access rules, and playback authorization is unchanged.
        val userId = credentialProvider()?.userId?.toLongOrNull() ?: 0L
        val response = executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/musicadservice/container/v1/newsong_publish",
                body = JSONObject()
                    .put("rank_id", 21608)
                    .put("userid", userId)
                    .put("page", 1)
                    .put("pagesize", 30)
                    .put("tags", JSONArray()),
                includeCredentials = userId > 0,
            ),
        )
        return remember(KugouTrackMapper.extractTracks(response.body))
    }

    override suspend fun library(): List<Track> {
        ensureDeviceRegistered?.invoke()
        val credentials = credentialProvider() ?: throw DirectLoginRequiredException()
        val response = executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/playhistory/v1/get_songs",
                body = JSONObject()
                    .put("token", credentials.token)
                    .put("userid", credentials.userId.toLongOrNull() ?: 0L)
                    .put("source_classify", "app")
                    .put("to_subdivide_sr", 1),
                includeCredentials = true,
            ),
        )
        return remember(KugouTrackMapper.extractTracks(response.body))
    }

    override suspend fun search(query: String): List<Track> {
        ensureDeviceRegistered?.invoke()
        val response = executor.execute(
            KugouRequest(
                method = "GET",
                baseUrl = GATEWAY,
                path = "/v3/search/song",
                query = linkedMapOf(
                    "albumhide" to 0,
                    "iscorrection" to 1,
                    "keyword" to query,
                    "nocollect" to 0,
                    "page" to 1,
                    "pagesize" to 30,
                    "platform" to "AndroidFilter",
                ),
                headers = mapOf("x-router" to "complexsearch.kugou.com"),
                includeCredentials = credentialProvider() != null,
            ),
        )
        return remember(KugouTrackMapper.extractTracks(response.body))
    }

    override suspend fun lyrics(trackId: String): List<TimedLyricLine> {
        val source = track(trackId)?.source as? com.kutv.music.core.model.PlaybackSource.Kugou
            ?: return emptyList()
        val search = executor.execute(
            KugouRequest.get(
                baseUrl = "https://lyrics.kugou.com",
                path = "/v1/search",
                query = linkedMapOf(
                    "album_audio_id" to 0,
                    // Real concept-app sessions return an empty body for the
                    // standard client pair; keep the lite identity used by QR.
                    "appid" to KugouSigner.APP_ID,
                    "clientver" to KugouSigner.CLIENT_VERSION,
                    "duration" to 0,
                    "hash" to source.hash,
                    "keyword" to "",
                    "lrctxt" to 1,
                    "man" to "no",
                ),
                // KuGouMusicApi's `notSign` spelling is not consumed by its
                // transport, so the working EchoMusic request is Android-signed.
                signMode = KugouSignMode.ANDROID,
                clearDefaultParams = true,
            ),
        )
        val searchData = search.body.optJSONObject("data") ?: search.body
        val candidates = searchData.optJSONArray("candidates")
            ?: searchData.optJSONArray("info")
            ?: return emptyList()
        val candidate = buildList {
            repeat(candidates.length()) { index -> candidates.optJSONObject(index)?.let(::add) }
        }.firstOrNull { item ->
            val id = item.optString("id")
            source.lyricId == null || id == source.lyricId
        } ?: return emptyList()
        val id = candidate.optString("id")
        val accessKey = candidate.optString("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return emptyList()
        val download = executor.execute(
            KugouRequest.get(
                baseUrl = "https://lyrics.kugou.com",
                path = "/download",
                query = linkedMapOf(
                    "ver" to 1,
                    "client" to "android",
                    "id" to id,
                    "accesskey" to accessKey,
                    "fmt" to "lrc",
                    "charset" to "utf8",
                ),
            ),
        )
        val data = download.body.optJSONObject("data") ?: download.body
        val content = data.optString("content")
        if (content.isBlank()) return emptyList()
        val text = if (data.optInt("contenttype", 1) == 0) {
            KugouLyrics.decodeKrcBase64(content)
        } else {
            runCatching { Base64.getDecoder().decode(content).toString(Charsets.UTF_8) }.getOrDefault("")
        }
        return KugouLyrics.parseTimed(text)
    }

    override suspend fun getPrivilege(hash: String): KugouPrivilege {
        val credentials = credentialProvider() ?: throw DirectLoginRequiredException()
        ensureDeviceRegistered?.invoke()
        val response = executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v2/get_res_privilege/lite",
                headers = mapOf("x-router" to "media.store.kugou.com", "content-type" to "application/json"),
                body = JSONObject()
                    .put("appid", KugouSigner.APP_ID)
                    .put("area_code", 1)
                    .put("behavior", "play")
                    .put("clientver", KugouSigner.CLIENT_VERSION)
                    .put("need_hash_offset", 1)
                    .put("relate", 1)
                    .put("support_verify", 1)
                    .put("resource", JSONArray().put(JSONObject().put("type", "audio").put("page_id", 0).put("hash", hash).put("album_id", 0)))
                    .put("qualities", JSONArray(listOf("128", "320", "flac", "high", "super", "multitrack")))
                    .put("userid", credentials.userId.toLongOrNull() ?: 0L)
                    .put("token", credentials.token),
                includeCredentials = true,
            ),
        )
        val data = response.body.optJSONObject("data")
        val arrays = listOfNotNull(data?.optJSONArray("privilege"), data?.optJSONArray("resource"), response.body.optJSONArray("data"))
        val item = arrays.asSequence().flatMap { array ->
            (0 until array.length()).asSequence().mapNotNull(array::optJSONObject)
        }.firstOrNull { it.optString("hash").ifBlank { it.optString("file_hash") }.equals(hash, true) }
            ?: return KugouPrivilege(false, false, emptyList())
        val playable = when (val value = item.opt("playable") ?: item.opt("status") ?: item.opt("is_free")) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> false
        }
        val directQualities = (item.optJSONArray("qualities") ?: item.optJSONArray("quality"))?.let { array ->
            buildList {
                repeat(array.length()) { index ->
                    array.optString(index).let(::normalizePlaybackQuality).takeIf { it in PLAYBACK_QUALITIES }?.let(::add)
                }
            }
        }.orEmpty()
        val qualityHashes = linkedMapOf<String, String>()
        val relateGoods = item.optJSONArray("relate_goods") ?: item.optJSONArray("relateGoods")
        if (relateGoods != null) {
            repeat(relateGoods.length()) { index ->
                val good = relateGoods.optJSONObject(index) ?: return@repeat
                val quality = normalizePlaybackQuality(good.optString("quality"))
                val relatedHash = good.optString("hash")
                if (quality in PLAYBACK_QUALITIES && relatedHash.isNotBlank()) {
                    qualityHashes[quality] = relatedHash
                }
            }
        }
        val qualities = qualityHashes.keys.toList().ifEmpty { directQualities }
        return KugouPrivilege(
            playable = playable,
            trial = item.optBoolean("trial") || item.optInt("trial_flag", 0) == 1,
            qualities = qualities,
            qualityHashes = qualityHashes,
        )
    }

    override suspend fun getSongUrl(hash: String, quality: String): KugouStream {
        val credentials = credentialProvider() ?: throw DirectLoginRequiredException()
        val device = deviceProvider?.invoke() ?: throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        val mid = KugouCrypto.deriveMid(device.mid)
        // KuGou signs the exact hash value sent to /v5/url.  The upstream
        // client normalizes it to lowercase before both operations; signing
        // an uppercase catalog hash produces a valid-looking but rejected key.
        val normalizedHash = hash.lowercase()
        val key = KugouSigner.signKey(normalizedHash, mid, credentials.userId)
        val response = executor.execute(
            KugouRequest(
                method = "GET",
                baseUrl = GATEWAY,
                path = "/v5/url",
                query = linkedMapOf(
                    "album_id" to 0,
                    "area_code" to 1,
                    "hash" to normalizedHash,
                    "ssa_flag" to "is_fromtrack",
                    "version" to 11430,
                    "page_id" to 967177915,
                    "quality" to if (quality == "hires") "high" else quality,
                    "album_audio_id" to 0,
                    "behavior" to "play",
                    "pid" to 411,
                    "cmd" to 26,
                    "pidversion" to 3001,
                    "IsFreePart" to 0,
                    "ppage_id" to "356753938,823673182,967485191",
                    "cdnBackup" to 1,
                    "module" to "",
                    "clientver" to 11430,
                    "key" to key,
                ),
                headers = mapOf("x-router" to "trackercdn.kugou.com"),
                // EchoMusic's current KuGouMusicApi path still signs /v5/url:
                // song_url.js uses `notSign`, while the transport only honors
                // `notSignature`. Real upstream requests without this Android
                // signature are rejected with errcode 20006.
                signMode = KugouSignMode.ANDROID,
                includeCredentials = true,
            ),
        )
        val url = KugouDomainPolicy.normalizeMediaUrl(extractStreamUrl(response.body))
            ?: throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        return KugouStream(
            url = url,
            mimeType = if (quality == "flac" || quality == "hires") "audio/flac" else "audio/mpeg",
            quality = quality,
        )
    }

    fun track(trackId: String): Track? = synchronized(bindings) { bindings[trackId] }

    fun rememberTracks(tracks: List<Track>): List<Track> = remember(tracks)

    private fun remember(tracks: List<Track>): List<Track> = tracks.also { items ->
        synchronized(bindings) { items.forEach { bindings[it.id] = it } }
    }

    private fun extractStreamUrl(body: JSONObject): String {
        extractUrlValue(body.opt("data")).takeIf(String::isNotBlank)?.let { return it }
        return extractUrlValue(body)
    }

    private fun extractUrlValue(value: Any?): String = when (value) {
        // Validate before choosing a candidate. A malformed/untrusted primary
        // must not prevent trying a permitted URL later in the same array or
        // backup field. Never search unrelated response metadata for audio.
        is String -> KugouDomainPolicy.normalizeMediaUrl(value.trim()).orEmpty()
        is JSONObject -> {
            sequenceOf("url", "play_url", "backupUrl", "backup_url")
                .map { key -> extractUrlValue(value.opt(key)) }
                .firstOrNull(String::isNotBlank)
                .orEmpty()
        }
        is JSONArray -> {
            (0 until value.length()).asSequence()
                .map { index -> extractUrlValue(value.opt(index)) }
                .firstOrNull(String::isNotBlank)
                .orEmpty()
        }
        else -> ""
    }

    private companion object {
        const val GATEWAY = "https://gateway.kugou.com"
        // Echo's upstream high means Hi-Res; the frozen TV contract uses hires.
        fun normalizePlaybackQuality(value: String) = if (value == "high") "hires" else value
        val PLAYBACK_QUALITIES = setOf("128", "320", "flac", "hires", "super")
    }
}
