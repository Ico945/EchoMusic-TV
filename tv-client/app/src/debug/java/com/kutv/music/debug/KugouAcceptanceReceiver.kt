package com.kutv.music.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kutv.music.AppGraph
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.direct.KugouDomainPolicy
import com.kutv.music.direct.KugouHttpClient
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouSigner
import com.kutv.music.player.QueueTrack
import java.io.File
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, credential-safe acceptance entry point. No token, URL or user ID is persisted or logged. */
class KugouAcceptanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val report = runCatching { runAcceptance(context.applicationContext) }
                .getOrElse { error ->
                    JSONObject()
                        .put("status", "failed")
                        .put("stage", error.message.orEmpty().take(1_000))
                        .put("errorType", error::class.java.simpleName)
                }
            File(context.filesDir, REPORT_FILE).writeText(report.toString())
            Log.i(TAG, "KUTV_ACCEPTANCE_COMPLETE status=${report.optString("status")}")
            pending.finish()
        }
    }

    private suspend fun runAcceptance(context: Context): JSONObject {
        AppGraph.initialize(context)
        checkNotNull(AppGraph.credentialStore.loadCredentials()) { "credentials" }
        AppGraph.sessionManager.restoreSession()
        checkNotNull(AppGraph.credentialStore.loadCredentials()) { "session" }

        val home = AppGraph.directApi.home()
        val homeCount = (listOfNotNull(home.hero) + home.newSongs + home.daily + home.history)
            .distinctBy { it.id }
            .size
        check(homeCount > 0) { "home" }

        val results = AppGraph.directApi.search("周杰伦")
        check(results.isNotEmpty()) { "search" }
        var resolved = false
        var lyricCount = 0
        var lyricProbeHash: String? = null
        var lyricProbeExpectedId: String? = null
        var mediaReady = false
        var mediaError = "not-run"
        var mediaDiagnosticHash: String? = null
        var mediaDiagnosticQuality = "128"
        var privilegeChecks = 0
        var playableCount = 0
        var firstPlayableHash: String? = null
        var firstPlayableQualityHashes: Map<String, String> = emptyMap()
        val resolverErrors = linkedMapOf<String, Int>()
        for (track in results.take(10)) {
            val source = track.source as? PlaybackSource.Kugou ?: continue
            privilegeChecks++
            val privilege = runCatching { AppGraph.directApi.getPrivilege(source.hash) }
                .onFailure { error ->
                    val kind = error::class.java.simpleName
                    resolverErrors[kind] = (resolverErrors[kind] ?: 0) + 1
                }
                .getOrNull()
            if (privilege?.playable == true) {
                playableCount++
                if (firstPlayableHash == null) {
                    firstPlayableHash = source.hash
                    firstPlayableQualityHashes = privilege.qualityHashes
                }
            }
            if (privilege?.playable != true) continue
            val item = QueueTrack(
                id = track.id,
                title = track.title,
                artists = track.artists,
                artworkUrl = track.artworkUri,
                album = track.album,
                source = source,
                durationMs = track.durationMs,
            )
            val stream = runCatching { AppGraph.playbackResolver.resolve(item) }
                .onFailure { error ->
                    val kind = error.message.orEmpty().take(30).ifBlank { error::class.java.simpleName }
                    resolverErrors[kind] = (resolverErrors[kind] ?: 0) + 1
                }
                .getOrNull() ?: continue
            if (!KugouDomainPolicy.isAllowedMediaUrl(stream.streamUrl)) continue
            resolved = true
            lyricProbeHash = source.hash
            lyricProbeExpectedId = source.lyricId
            lyricCount = runCatching { AppGraph.directApi.lyrics(track.id).size }.getOrDefault(0)
            if (lyricCount == 0) break
            val mediaProbe = runCatching { media3Ready(context, stream.streamUrl) }
                .getOrElse { error ->
                    MediaProbe(false, error::class.java.simpleName, error.cause?.javaClass?.simpleName.orEmpty())
                }
            mediaReady = mediaProbe.ready
            mediaError = "${mediaProbe.errorCode}:${mediaProbe.causeType}"
            mediaDiagnosticHash = privilege?.qualityHashes?.get(stream.quality) ?: source.hash
            mediaDiagnosticQuality = stream.quality
            break
        }
        check(playableCount > 0) {
            val firstHash = (results.firstOrNull()?.source as? PlaybackSource.Kugou)?.hash.orEmpty()
            val diagnostic = runCatching { privilegeDiagnostic(firstHash) }.getOrDefault("diagnostic-unavailable")
            "privilege:checks=$privilegeChecks,playable=$playableCount,errors=$resolverErrors,$diagnostic"
        }
        check(resolved) {
            val diagnostic = firstPlayableHash?.let { hash ->
                val privilegeShape = runCatching { privilegeDiagnostic(hash) }
                    .getOrDefault("privilege-diagnostic-unavailable")
                val relatedUrlResults = mutableListOf<String>()
                val relatedEntries = firstPlayableQualityHashes.entries
                    .filter { it.key in setOf("128", "320", "flac", "high") }
                    .take(4)
                for ((quality, relatedHash) in relatedEntries) {
                    val result = runCatching { songUrlDiagnostic(relatedHash, quality) }
                        .getOrDefault("url-diagnostic-unavailable")
                    relatedUrlResults += "$quality={$result}"
                }
                val relatedUrls = relatedUrlResults.joinToString(";")
                val baseUrl = runCatching { songUrlDiagnostic(hash, "128") }
                    .getOrDefault("url-diagnostic-unavailable")
                "$privilegeShape,relatedUrls=$relatedUrls,baseUrl={$baseUrl}"
            } ?: "no-playable-hash"
            "resolve:errors=$resolverErrors,$diagnostic"
        }
        check(lyricCount > 0) {
            lyricProbeHash?.let { hash ->
                runCatching { lyricsDiagnostic(hash, lyricProbeExpectedId) }
                    .getOrDefault("lyrics-diagnostic-unavailable")
            } ?: "lyrics-no-hash"
        }
        val mediaDiagnostic = if (!mediaReady && mediaDiagnosticHash != null) {
            runCatching { songUrlDiagnostic(checkNotNull(mediaDiagnosticHash), mediaDiagnosticQuality) }
                .getOrDefault("media-url-diagnostic-unavailable")
        } else ""
        check(mediaReady) { "media3:$mediaError,$mediaDiagnostic" }

        return JSONObject()
            .put("status", "passed")
            .put("homeCount", homeCount)
            .put("searchCount", results.size)
            .put("resolved", true)
            .put("lyricCount", lyricCount)
            .put("mediaReady", true)
    }

    private suspend fun media3Ready(context: Context, streamUrl: String): MediaProbe {
        val ready = CompletableDeferred<MediaProbe>()
        val player = withContext(Dispatchers.Main) {
            ExoPlayer.Builder(context).build().also { player ->
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && !ready.isCompleted) {
                            ready.complete(MediaProbe(true, "", ""))
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!ready.isCompleted) {
                            val causeTypes = generateSequence(error.cause) { it.cause }
                                .take(4)
                                .joinToString(">") { it::class.java.simpleName }
                            ready.complete(MediaProbe(false, error.errorCodeName, causeTypes))
                        }
                    }
                })
                player.setMediaItem(MediaItem.fromUri(streamUrl))
                player.prepare()
            }
        }
        return try {
            withTimeout(20_000) { ready.await() }
        } finally {
            withContext(Dispatchers.Main) { player.release() }
        }
    }

    private data class MediaProbe(
        val ready: Boolean,
        val errorCode: String,
        val causeType: String,
    )

    private suspend fun privilegeDiagnostic(hash: String): String {
        val credentials = checkNotNull(AppGraph.credentialStore.loadCredentials())
        val client = KugouHttpClient(
            deviceProvider = AppGraph.deviceStore::getOrCreate,
            credentialProvider = AppGraph.credentialStore::loadCredentials,
        )
        val raw = client.executeRaw(
            KugouRequest(
                method = "POST",
                baseUrl = "https://gateway.kugou.com",
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
        val json = JSONObject(raw.body)
        val keys = json.keys().asSequence().toList().sorted().joinToString("|")
        val rawData = json.opt("data")
        val first = (rawData as? JSONArray)?.optJSONObject(0)
            ?: (rawData as? JSONObject)?.optJSONArray("privilege")?.optJSONObject(0)
            ?: (rawData as? JSONObject)?.optJSONArray("resource")?.optJSONObject(0)
        val itemKeys = first?.keys()?.asSequence()?.toList()?.sorted()?.joinToString("|").orEmpty().take(240)
        val relate = first?.optJSONArray("relate_goods") ?: first?.optJSONArray("relateGoods")
        val qualities = buildList {
            if (relate != null) repeat(relate.length()) { index ->
                relate.optJSONObject(index)?.optString("quality")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().sorted().joinToString("|")
        val message = json.optString("error_msg")
            .ifBlank { json.optString("msg") }
            .ifBlank { json.optString("message") }
            .ifBlank { json.optString("errmsg") }
            .take(100)
        return "code=${json.optInt("error_code", -1)},status=${json.optInt("status", -1)}," +
            "message=$message,keys=$keys,relateCount=${relate?.length() ?: 0}," +
            "qualities=$qualities,itemStatus=${first?.optInt("status", -1) ?: -1},itemKeys=$itemKeys"
    }

    private suspend fun songUrlDiagnostic(hash: String, quality: String): String {
        val credentials = checkNotNull(AppGraph.credentialStore.loadCredentials())
        val device = AppGraph.deviceStore.getOrCreate()
        val normalizedHash = hash.lowercase()
        val key = KugouSigner.signKey(
            normalizedHash,
            com.kutv.music.direct.KugouCrypto.deriveMid(device.mid),
            credentials.userId,
        )
        val client = KugouHttpClient(
            deviceProvider = AppGraph.deviceStore::getOrCreate,
            credentialProvider = AppGraph.credentialStore::loadCredentials,
        )
        val raw = client.executeRaw(
            KugouRequest(
                method = "GET",
                baseUrl = "https://gateway.kugou.com",
                path = "/v5/url",
                query = linkedMapOf(
                    "album_id" to 0,
                    "area_code" to 1,
                    "hash" to normalizedHash,
                    "ssa_flag" to "is_fromtrack",
                    "version" to 11430,
                    "page_id" to 967177915,
                    "quality" to quality,
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
                signMode = com.kutv.music.direct.KugouSignMode.ANDROID,
                includeCredentials = true,
            ),
        )
        val json = runCatching { JSONObject(raw.body) }.getOrElse {
            return "urlHttp=${raw.statusCode},json=false"
        }
        val data = json.opt("data")
        val dataObject = data as? JSONObject
        val firstItem = (data as? JSONArray)?.optJSONObject(0)
            ?: dataObject?.optJSONArray("url")?.optJSONObject(0)
        val dataKeys = (dataObject ?: firstItem)?.keys()?.asSequence()?.toList()?.sorted()?.joinToString("|").orEmpty()
        val streamUrl = sequenceOf(dataObject, firstItem, json)
            .filterNotNull()
            .map(::firstUrlValue)
            .firstOrNull(String::isNotBlank).orEmpty()
        val streamUri = runCatching { URI(streamUrl) }.getOrNull()
        val endpoints = allUrlValues(json)
            .distinct()
            .take(8)
            .joinToString("|") { candidate ->
                val uri = runCatching { URI(candidate) }.getOrNull()
                "${uri?.scheme.orEmpty()}@${uri?.host.orEmpty()}"
            }
        val urlPresent = streamUrl.isNotBlank()
        val message = json.optString("error_msg")
            .ifBlank { json.optString("msg") }
            .ifBlank { json.optString("message") }
            .take(100)
        val keys = json.keys().asSequence().toList().sorted().joinToString("|")
        return "urlHttp=${raw.statusCode},code=${json.optInt("error_code", json.optInt("errcode", -1))}," +
            "status=${json.optInt("status", -1)},message=$message,keys=$keys," +
            "dataType=${data?.javaClass?.simpleName ?: "null"},dataKeys=$dataKeys,urlPresent=$urlPresent," +
            "scheme=${streamUri?.scheme.orEmpty()},host=${streamUri?.host.orEmpty()}," +
            "allowed=${KugouDomainPolicy.isAllowedMediaUrl(streamUrl)},endpoints=$endpoints"
    }

    private suspend fun lyricsDiagnostic(hash: String, expectedId: String?): String {
        val client = KugouHttpClient(
            deviceProvider = AppGraph.deviceStore::getOrCreate,
            credentialProvider = AppGraph.credentialStore::loadCredentials,
        )
        val variants = listOf(
            Triple("standardSigned", KugouSigner.STANDARD_APP_ID to KugouSigner.STANDARD_CLIENT_VERSION, com.kutv.music.direct.KugouSignMode.ANDROID),
            Triple("standardUnsigned", KugouSigner.STANDARD_APP_ID to KugouSigner.STANDARD_CLIENT_VERSION, com.kutv.music.direct.KugouSignMode.NONE),
            Triple("liteSigned", KugouSigner.APP_ID to KugouSigner.CLIENT_VERSION, com.kutv.music.direct.KugouSignMode.ANDROID),
            Triple("liteUnsigned", KugouSigner.APP_ID to KugouSigner.CLIENT_VERSION, com.kutv.music.direct.KugouSignMode.NONE),
        )
        val summaries = mutableListOf<String>()
        var selectedRaw: com.kutv.music.direct.KugouRawResponse? = null
        var selectedSearch: JSONObject? = null
        var selectedVariant = ""
        for ((name, versions, signMode) in variants) {
            val raw = client.executeRaw(
                KugouRequest.get(
                    baseUrl = "https://lyrics.kugou.com",
                    path = "/v1/search",
                    query = linkedMapOf(
                        "album_audio_id" to 0,
                        "appid" to versions.first,
                        "clientver" to versions.second,
                        "duration" to 0,
                        "hash" to hash,
                        "keyword" to "",
                        "lrctxt" to 1,
                        "man" to "no",
                    ),
                    signMode = signMode,
                    clearDefaultParams = true,
                ),
            )
            val json = runCatching { JSONObject(raw.body) }.getOrNull()
            val data = json?.optJSONObject("data") ?: json
            val count = data?.optJSONArray("candidates")?.length()
                ?: data?.optJSONArray("info")?.length()
                ?: 0
            summaries += "$name(http=${raw.statusCode},bytes=${raw.bodyBytes?.size ?: raw.body.length}," +
                "json=${json != null},status=${json?.optInt("status", -1) ?: -1}," +
                "code=${json?.optInt("error_code", -1) ?: -1},candidates=$count)"
            if (json != null && count > 0) {
                selectedRaw = raw
                selectedSearch = json
                selectedVariant = name
                break
            }
        }
        val searchRaw = selectedRaw ?: return "lyricVariants=${summaries.joinToString(";")}"
        val search = selectedSearch ?: return "lyricVariants=${summaries.joinToString(";")}"
        val searchData = search.optJSONObject("data") ?: search
        val candidates = searchData.optJSONArray("candidates") ?: searchData.optJSONArray("info")
        val first = candidates?.optJSONObject(0)
        val idMatch = expectedId.isNullOrBlank() || (0 until (candidates?.length() ?: 0)).any { index ->
            candidates?.optJSONObject(index)?.optString("id") == expectedId
        }
        val searchKeys = search.keys().asSequence().toList().sorted().joinToString("|")
        if (first == null) {
            return "lyricSearchHttp=${searchRaw.statusCode},status=${search.optInt("status", -1)}," +
                "code=${search.optInt("error_code", -1)},keys=$searchKeys,candidates=0"
        }
        val id = first.optString("id")
        val accessKey = first.optString("accesskey")
        if (id.isBlank() || accessKey.isBlank()) {
            return "lyricSearchHttp=${searchRaw.statusCode},keys=$searchKeys," +
                "candidates=${candidates?.length() ?: 0},usable=false,idMatch=$idMatch"
        }
        val downloadRaw = client.executeRaw(
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
        val download = runCatching { JSONObject(downloadRaw.body) }.getOrElse {
            return "lyricCandidates=${candidates?.length() ?: 0},idMatch=$idMatch," +
                "downloadHttp=${downloadRaw.statusCode},json=false"
        }
        val data = download.optJSONObject("data") ?: download
        val content = data.optString("content")
        val decoded = runCatching { Base64.getDecoder().decode(content).toString(Charsets.UTF_8) }.getOrDefault("")
        val parsedLines = com.kutv.music.direct.KugouLyrics.parseTimed(decoded).size
        val downloadKeys = download.keys().asSequence().toList().sorted().joinToString("|")
        return "lyricVariant=$selectedVariant,lyricSearchHttp=${searchRaw.statusCode},keys=$searchKeys," +
            "candidates=${candidates?.length() ?: 0},idMatch=$idMatch," +
            "downloadHttp=${downloadRaw.statusCode},downloadKeys=$downloadKeys," +
            "content=${content.isNotBlank()},contentType=${data.optInt("contenttype", -1)},parsedLines=$parsedLines"
    }

    private fun firstUrlValue(value: Any?): String = when (value) {
        is String -> value.trim()
        is JSONObject -> sequenceOf("url", "play_url", "backupUrl", "backup_url")
            .map { key -> firstUrlValue(value.opt(key)) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        is JSONArray -> (0 until value.length()).asSequence()
            .map { index -> firstUrlValue(value.opt(index)) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        else -> ""
    }

    private fun allUrlValues(value: Any?): List<String> = when (value) {
        is String -> listOf(value.trim()).filter(String::isNotBlank)
        is JSONObject -> sequenceOf("url", "play_url", "backupUrl", "backup_url")
            .flatMap { key -> allUrlValues(value.opt(key)).asSequence() }
            .toList()
        is JSONArray -> (0 until value.length()).flatMap { index -> allUrlValues(value.opt(index)) }
        else -> emptyList()
    }

    private companion object {
        const val ACTION = "com.kutv.music.DEBUG_ACCOUNT_ACCEPTANCE"
        const val REPORT_FILE = "kugou-acceptance.json"
        const val TAG = "KutvAcceptance"
    }
}
