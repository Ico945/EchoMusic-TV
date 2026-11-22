package com.kutv.music.bridge.mappers

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import com.kutv.music.web.ArtworkUrlPolicy

object BridgeTrackIds {
    private const val MAX_ENTRIES = 1_024
    private val nativeByPublic = LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true)

    fun publicId(nativeId: String): String {
        require(nativeId.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(nativeId.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return "tv_$digest".also { publicId ->
            synchronized(nativeByPublic) {
                nativeByPublic[publicId] = nativeId
                while (nativeByPublic.size > MAX_ENTRIES) {
                    val oldest = nativeByPublic.entries.iterator()
                    if (oldest.hasNext()) {
                        oldest.next()
                        oldest.remove()
                    }
                }
            }
        }
    }

    fun nativeId(publicId: String): String =
        synchronized(nativeByPublic) {
            nativeByPublic[publicId] ?: throw IllegalArgumentException("Unknown track id")
        }

    fun clear() {
        synchronized(nativeByPublic) { nativeByPublic.clear() }
    }

    fun sanitizeHome(payload: JSONObject): JSONObject = payload.apply {
        optJSONObject("hero")?.sanitizeTrack()
        optJSONArray("secondary")?.sanitizeTracks()
        optJSONArray("shelves")?.let { shelves ->
            repeat(shelves.length()) { index ->
                shelves.optJSONObject(index)?.optJSONArray("tracks")?.sanitizeTracks()
            }
        }
    }

    private fun JSONArray.sanitizeTracks() {
        repeat(length()) { index -> optJSONObject(index)?.sanitizeTrack() }
    }

    private fun JSONObject.sanitizeTrack() {
        val nativeId = optString("id").takeIf(String::isNotBlank) ?: return
        put("id", publicId(nativeId))
        optString("artwork").takeIf(String::isNotBlank)?.let { artwork ->
            val normalized = ArtworkUrlPolicy.normalize(artwork)
            put("artwork", normalized ?: "")
        }
    }
}
