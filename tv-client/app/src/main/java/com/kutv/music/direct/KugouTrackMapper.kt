package com.kutv.music.direct

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Track
import org.json.JSONArray
import org.json.JSONObject

object KugouTrackMapper {
    private val containers = listOf(
        "songs",
        "song_list",
        "songlist",
        "info",
        "lists",
        "audio_info",
        "list",
        "audios",
        "recommend_song",
    )

    fun map(raw: JSONObject): Track? {
        val audio = raw.optJSONObject("audio_info")
        val base = raw.optJSONObject("base")
        val albumInfo = raw.optJSONObject("album_info")
        val hash = firstString(raw, "hash", "hash_128", "FileHash", "fileHash")
            ?: audio?.let { firstString(it, "hash", "hash_128", "FileHash", "fileHash") }
            ?: return null
        val id = firstString(raw, "album_audio_id", "mixsongid", "MixSongID", "Auditoid", "Audioid", "song_id", "audio_id", "id")
            ?: base?.let { firstString(it, "album_audio_id", "mixsongid", "song_id", "audio_id", "id") }
            ?: audio?.let { firstString(it, "album_audio_id", "mixsongid", "song_id", "audio_id", "id") }
            ?: hash
        val rawTitle = (firstString(raw, "songname", "SongName", "audio_name", "title", "filename", "FileName", "name")
            ?: base?.let { firstString(it, "songname", "audio_name", "title", "filename", "name") })
            .orEmpty()
        var artistText = (firstString(raw, "singername", "SingerName", "author_name", "singer")
            ?: base?.let { firstString(it, "singername", "author_name", "singer") }
            ?: firstAuthorName(raw))
            .orEmpty()
        val title = if (artistText.isBlank() && " - " in rawTitle) {
            val (artist, name) = rawTitle.split(" - ", limit = 2)
            artistText = artist.trim()
            name.trim().ifBlank { rawTitle }
        } else rawTitle
        val artists = artistText.split(Regex("[、,;；/]")).map(String::trim).filter(String::isNotBlank)
        val albumId = firstString(raw, "album_id", "albumid", "AlbumID", "AlbumId")
            ?: base?.let { firstString(it, "album_id", "albumid") }
            ?: albumInfo?.let { firstString(it, "album_id", "albumid") }
        val album = (firstString(raw, "album_name", "album", "AlbumName")
            ?: base?.let { firstString(it, "album_name", "album") }
            ?: albumInfo?.let { firstString(it, "album_name", "album") })
            .orEmpty()
        val durationMs = durationMillis(raw).takeIf { it > 0 }
            ?: audio?.let(::durationMillis)
            ?: 0L
        // EchoMusic's current feeds commonly put artwork in sized/nested fields.
        val image = (firstString(raw, "img", "album_img", "pic", "cover", "album_sizable_cover", "sizable_cover", "Image")
            ?: raw.optJSONObject("trans_param")?.let { firstString(it, "union_cover") }
            ?: raw.optJSONObject("album_info")?.let { firstString(it, "sizable_cover", "cover") }
            ?: raw.optJSONObject("audio_info")?.let { firstString(it, "img") })
            .orEmpty()
            .replace("{size}", "400")
            .let { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }
        val lyricId = firstString(raw, "lyric_id", "lyrics_id", "ksc_id")
            ?: audio?.let { firstString(it, "lyric_id", "lyrics_id", "ksc_id") }
        val mvId = sequenceOf(raw, audio, base).filterNotNull().flatMap { source ->
            sequenceOf("mv_id", "video_id").mapNotNull { firstString(source, it)?.trim()?.removePrefix("mv:") }
        }
            .firstOrNull { value ->
                value.matches(Regex("^[A-Za-z0-9_-]{1,120}$")) &&
                    // Zero/negative numeric sentinels mean no video, not MV 0.
                    (!value.matches(Regex("^-?[0-9]+$")) || value.toBigInteger().signum() > 0)
            }
        val qualities = raw.optJSONArray("qualities")?.stringValues()
            ?.filter { it in setOf("128", "320", "flac", "hires") }
            .orEmpty()
            .ifEmpty { listOf("128") }
        return Track(
            id = id,
            title = title.ifBlank { "未命名歌曲" },
            artists = artists.ifEmpty { listOf("未知歌手") },
            album = album,
            artworkUri = image,
            durationMs = durationMs,
            qualities = qualities,
            source = PlaybackSource.Kugou(
                trackId = id,
                hash = hash,
                albumId = albumId,
                lyricId = lyricId,
                preferredQuality = "auto",
            ),
            mvId = mvId,
        )
    }

    fun extractTracks(response: JSONObject): List<Track> {
        val candidates = buildList {
            response.optJSONObject("data")?.let { data ->
                containers.forEach { key -> data.optJSONArray(key)?.let(::add) }
                data.optJSONArray("data")?.let(::add)
            }
            containers.forEach { key -> response.optJSONArray(key)?.let(::add) }
            response.optJSONArray("data")?.let(::add)
        }
        for (array in candidates) {
            val tracks = buildList {
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.let(::map)?.let(::add)
                }
            }
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    private fun firstString(raw: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = raw.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value is Number) return value.toString()
        }
        return null
    }

    private fun firstAuthorName(raw: JSONObject): String? {
        val authors = raw.optJSONArray("authors") ?: raw.optJSONArray("Singers") ?: return null
        repeat(authors.length()) { index ->
            val author = authors.optJSONObject(index) ?: return@repeat
            firstString(author, "author_name", "AuthorName", "singername", "name")?.let { return it }
        }
        return null
    }

    private fun durationMillis(raw: JSONObject): Long {
        // Echo's current new-song feed exposes timelength in milliseconds.
        // Preserve legacy flat duration/second semantics; never infer units by magnitude.
        val millis = firstPositiveLong(raw, "durationms", "durationMs", "duration_ms", "timelength", "timelen", "duration_128")
        if (millis > 0) return millis
        val seconds = firstPositiveLong(raw, "time_length", "duration", "Duration", "second")
        return if (seconds <= Long.MAX_VALUE / 1_000L) seconds * 1_000L else 0L
    }

    private fun firstPositiveLong(raw: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            val value = raw.opt(key)
            val parsed = when (value) {
                is Number -> if (value.toDouble().isFinite()) value.toLong() else null
                is String -> value.toLongOrNull()
                else -> null
            }
            if (parsed != null && parsed > 0) return parsed
        }
        return 0L
    }

    private fun JSONArray.stringValues(): List<String> = buildList {
        repeat(length()) { index -> optString(index).takeIf(String::isNotBlank)?.let(::add) }
    }
}
