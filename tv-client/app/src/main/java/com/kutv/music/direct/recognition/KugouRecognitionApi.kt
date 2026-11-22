package com.kutv.music.direct.recognition

import com.kutv.music.direct.KugouRawRequestExecutor
import com.kutv.music.direct.KugouRawResponse
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouSignMode
import org.json.JSONArray
import org.json.JSONObject

data class RecognitionMatch(
    val id: String,
    val title: String,
    val artist: String,
    val confidence: Float,
)

/**
 * Port for the 听歌识曲 (song recognition) endpoint. Split from the transport
 * so the use case and bridge handler can be driven with fakes; the concrete
 * implementation is [DirectKugouRecognitionApi].
 */
interface KugouRecognitionApi {
    suspend fun match(pcm: ByteArray): List<RecognitionMatch>
}

/**
 * Matches the ported EchoMusic `server/module/audio_match.js` protocol:
 * `POST /fingerprint.service/v1/music_trackid_mulit` on the KuGou gateway with
 * Android signing, an `application/octet-stream` body of raw PCM bytes, and
 * `fpid/area_code/include_unpublish/useid/multi_result` query parameters.
 * The JSON response is parsed fail-closed: any malformed or empty payload
 * yields an empty match list rather than surfacing upstream details.
 */
class DirectKugouRecognitionApi(
    private val rawExecutor: KugouRawRequestExecutor,
    private val userIdProvider: () -> Long = { 0L },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : KugouRecognitionApi {
    override suspend fun match(pcm: ByteArray): List<RecognitionMatch> {
        require(pcm.isNotEmpty()) { "pcm must not be empty" }
        val response = rawExecutor.executeRaw(
            KugouRequest(
                method = "POST",
                baseUrl = GATEWAY,
                path = PATH,
                query = linkedMapOf(
                    "fpid" to clockMs(),
                    "area_code" to 1,
                    "include_unpublish" to 1,
                    "useid" to userIdProvider(),
                    "multi_result" to 1,
                ),
                headers = mapOf(
                    "content-type" to "application/octet-stream",
                    "user-agent" to "KuGou/11490 (Android)",
                ),
                body = pcm,
                signMode = KugouSignMode.ANDROID,
            ),
        )
        return parseMatches(response)
    }

    private fun parseMatches(response: KugouRawResponse): List<RecognitionMatch> = runCatching {
        val bytes = response.bodyBytes?.takeIf { it.isNotEmpty() }
            ?: response.body.toByteArray(Charsets.UTF_8)
        extractMatches(JSONObject(String(bytes, Charsets.UTF_8)))
    }.getOrDefault(emptyList())

    private fun extractMatches(json: JSONObject): List<RecognitionMatch> {
        val data = json.opt("data")
        val candidates: List<JSONObject> = when (data) {
            is JSONArray -> (0 until data.length()).mapNotNull(data::optJSONObject)
            is JSONObject -> candidateObjects(data)
            else -> candidateObjects(json)
        }
        return candidates.mapNotNull(::toMatch)
    }

    private fun candidateObjects(data: JSONObject): List<JSONObject> {
        for (key in LIST_KEYS) {
            val array = data.optJSONArray(key)
            if (array != null) return (0 until array.length()).mapNotNull(array::optJSONObject)
        }
        return if (data.has("song_name") || data.has("hash") || data.has("audio_id")) {
            listOf(data)
        } else {
            emptyList()
        }
    }

    private fun toMatch(obj: JSONObject): RecognitionMatch? {
        val id = firstNonBlank(obj, "hash", "audio_id", "mixsongid", "id", "song_id") ?: return null
        val title = firstNonBlank(obj, "song_name", "songname", "name", "title") ?: return null
        val artist = firstNonBlank(obj, "singer_name", "singername", "singer", "author", "artist").orEmpty()
        return RecognitionMatch(id = id, title = title, artist = artist, confidence = confidenceOf(obj))
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String? =
        keys.asSequence().map { key -> obj.optString(key) }.firstOrNull { it.isNotBlank() }

    private fun confidenceOf(obj: JSONObject): Float {
        val raw = listOf("score", "confidence", "similarity", "match_ratio")
            .asSequence()
            .map { key -> obj.opt(key) }
            .firstOrNull { it != null && it !== JSONObject.NULL } ?: return 0f
        val value = when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull() ?: return 0f
            else -> return 0f
        }
        return if (value in 0f..1f) value else value / 100f
    }

    private companion object {
        const val GATEWAY = "https://gateway.kugou.com"
        const val PATH = "/fingerprint.service/v1/music_trackid_mulit"
        val LIST_KEYS = listOf("list", "tracks", "matches", "songs", "results", "music_list")
    }
}
