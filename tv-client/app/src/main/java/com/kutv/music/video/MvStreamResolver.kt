package com.kutv.music.video

import com.kutv.music.direct.KugouCredentials
import com.kutv.music.direct.KugouCrypto
import com.kutv.music.direct.KugouDeviceIdentity
import com.kutv.music.direct.KugouDomainPolicy
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouSignMode
import com.kutv.music.direct.KugouSigner
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class ResolvedMvStream(
    val url: String,
    val mimeType: String,
    val quality: String,
    val availableQualities: List<String> = emptyList(),
)

fun interface MvStreamResolver {
    suspend fun resolve(mvId: String, quality: String): Result<ResolvedMvStream>
}

class KugouMvStreamResolver(
    private val executor: KugouRequestExecutor,
    private val deviceProvider: () -> KugouDeviceIdentity,
    private val credentialProvider: () -> KugouCredentials?,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : MvStreamResolver {
    override suspend fun resolve(mvId: String, quality: String): Result<ResolvedMvStream> = try {
        require(mvId.matches(MV_ID_PATTERN))
        require(quality in QUALITIES)
        val hashes = resolveQualityHashes(mvId.removePrefix("mv:"))
        // Prefer the requested tier, then the nearest lower tier. Some real
        // artist videos publish SD/QHD/FHD but leave HD empty.
        val preference = QUALITIES.drop(QUALITIES.indexOf(quality)) +
            QUALITIES.take(QUALITIES.indexOf(quality)).reversed()
        val selected = preference.firstOrNull { it in hashes } ?: error("MV quality is unavailable")
        Result.success(resolveStream(hashes.getValue(selected), selected).copy(availableQualities = hashes.keys.toList()))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private suspend fun resolveQualityHashes(videoId: String): Map<String, String> {
        val device = deviceProvider()
        val credentials = credentialProvider()
        val clientTime = clockSeconds()
        val mid = KugouCrypto.deriveMid(device.mid)
        val dfid = device.dfid ?: "-"
        val body = JSONObject()
            .put("appid", KugouSigner.APP_ID)
            .put("clientver", KugouSigner.CLIENT_VERSION)
            .put("clienttime", clientTime)
            .put("mid", mid)
            .put("uuid", md5("$dfid$mid"))
            .put("dfid", dfid)
            .put("token", credentials?.token.orEmpty())
            .put("key", KugouSigner.signParamsKey(clientTime.toString()))
            .put("show_resolution", 1)
            .put("data", JSONArray().put(JSONObject().put("video_id", videoId)))
        val detail = executor.execute(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = "/v1/video",
                body = body,
                clearDefaultParams = true,
                headers = mapOf("x-router" to "kmr.service.kugou.com"),
                signMode = KugouSignMode.ANDROID,
            ),
        )
        return QUALITIES.mapNotNull { quality ->
            findQualityHash(detail.body, quality)?.let { quality to it }
        }.toMap()
    }

    private suspend fun resolveStream(hash: String, quality: String): ResolvedMvStream {
        val device = deviceProvider()
        val credentials = credentialProvider()
        val mid = KugouCrypto.deriveMid(device.mid)
        val response = executor.execute(
            KugouRequest(
                method = "GET",
                baseUrl = GATEWAY,
                path = "/v2/interface/index",
                query = linkedMapOf(
                    "backupdomain" to 1,
                    "cmd" to 123,
                    "ext" to "mp4",
                    "ismp3" to 0,
                    "hash" to hash,
                    "pid" to 1,
                    "type" to 1,
                    "key" to KugouSigner.signKey(hash, mid, credentials?.userId ?: "0"),
                ),
                headers = mapOf("x-router" to "trackermv.kugou.com"),
                includeCredentials = credentials != null,
                signMode = KugouSignMode.ANDROID,
            ),
        )
        val url = findStreamUrl(response.body)
            ?.let(KugouDomainPolicy::normalizeMediaUrl)
            ?: error("MV stream is unavailable")
        return ResolvedMvStream(url = url, mimeType = "video/mp4", quality = quality)
    }

    private fun findQualityHash(value: Any?, quality: String, allowDirect: Boolean = false): String? = when (value) {
        is JSONObject -> QUALITY_CODECS.asSequence()
            .mapNotNull { codec -> validHash(value.optJSONObject(codec)?.opt("${quality}_hash")) }
            .firstOrNull()
            ?: (if (allowDirect) validHash(value.opt("${quality}_hash")) else null)
            ?: QUALITY_CONTAINERS.asSequence()
                .mapNotNull { key ->
                    findQualityHash(
                        value.opt(key),
                        quality,
                        allowDirect = key in DIRECT_HASH_CONTAINERS,
                    )
                }
                .firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { index -> findQualityHash(value.opt(index), quality, allowDirect) }
            .firstOrNull()
        else -> null
    }

    private fun findStreamUrl(value: Any?): String? = when (value) {
        is JSONObject -> {
            STREAM_URL_FIELDS.asSequence()
                .mapNotNull { key -> extractStreamUrlValue(value.opt(key)) }
                .firstOrNull()
                ?: value.keys().asSequence()
                    .mapNotNull { key -> value.opt(key) }
                    .filter { child -> child is JSONObject || child is JSONArray }
                    .mapNotNull(::findStreamUrl)
                    .firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { index -> findStreamUrl(value.opt(index)) }
            .firstOrNull()
        else -> null
    }

    private fun validHash(value: Any?): String? = (value as? String)?.lowercase()
        ?.takeIf { it.matches(HASH_PATTERN) }

    private fun extractStreamUrlValue(value: Any?): String? = when (value) {
        is JSONObject -> findStreamUrl(value)
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { index -> extractStreamUrlValue(value.opt(index)) }
            .firstOrNull()
        // Use the same scoped CDN policy as audio playback. Validate each
        // candidate before choosing it, so an invalid primary cannot hide a
        // valid backup. API requests themselves remain HTTPS-only.
        is String -> KugouDomainPolicy.normalizeMediaUrl(value.trim())
        else -> null
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val GATEWAY = "https://gateway.kugou.com"
        val QUALITIES = listOf("fhd", "hd", "qhd", "sd", "ld")
        val MV_ID_PATTERN = Regex("^[A-Za-z0-9:_-]{1,128}$")
        val HASH_PATTERN = Regex("^[a-f0-9]{32}$")
        val QUALITY_CODECS = listOf("h264", "h265", "mkv")
        val QUALITY_CONTAINERS = listOf("data", "info", "list", "video", "mvdata")
        // /v1/video returns flat quality fields directly on data[] records.
        // Keep traversal scoped to known containers; unrelated metadata is not a source.
        val DIRECT_HASH_CONTAINERS = setOf("data", "info", "list", "video", "mvdata")
        val STREAM_URL_FIELDS = listOf("downurl", "url", "play_url", "backupdownurl", "data")
    }
}
