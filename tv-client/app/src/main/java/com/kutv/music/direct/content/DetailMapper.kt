package com.kutv.music.direct.content

import com.kutv.music.core.model.AlbumDetail
import com.kutv.music.core.model.ArtistDetail
import com.kutv.music.core.model.Comment
import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentDetail
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.MvDetail
import com.kutv.music.core.model.PagedResult
import com.kutv.music.core.model.PlaylistDetail
import com.kutv.music.core.model.SongDetail
import com.kutv.music.core.model.Track
import com.kutv.music.core.model.UserProfile
import com.kutv.music.direct.KugouTrackMapper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps raw KuGou detail/comment responses onto the [ContentDetail] variants and
 * their child pages. Every probe is fail-closed: a missing field resolves to an
 * empty value instead of throwing, so a malformed upstream body cannot leak
 * sensitive data through an exception.
 */
object DetailMapper {
    fun playlist(id: String, metaJson: JSONObject, tracksJson: JSONObject): PlaylistDetail {
        val data = firstObject(metaJson)
        val creator = data.optJSONObject("userinfo")?.let { user ->
            UserProfile(
                id = firstString(user, "userid", "user_id", "id").orEmpty(),
                nickname = firstString(user, "username", "nickname", "name").orEmpty(),
                avatarUrl = image(firstString(user, "user_pic", "pic", "avatar", "img")),
            )
        }
        return PlaylistDetail(
            id = id,
            title = firstString(data, "name", "listname", "list_name").orEmpty(),
            artworkUrl = image(
                firstString(data, "flexible_cover", "sizable_cover", "img", "imgurl", "pic", "cover", "image_url")
                    ?: data.optJSONObject("trans_param")?.let { firstString(it, "union_cover") },
            ),
            creator = creator,
            playCount = firstLong(data, "play_count", "listencount", "playcount"),
            tags = stringList(data, "tags", "tag"),
            introduction = firstString(data, "intro", "des", "description").orEmpty(),
            trackCount = firstInt(data, "song_count", "music_count", "total", "count"),
            tracks = trackPage(tracksJson),
        )
    }

    fun album(id: String, metaJson: JSONObject): AlbumDetail {
        val data = firstObject(metaJson)
        return AlbumDetail(
            id = id,
            title = firstString(data, "album_name", "albumname", "name").orEmpty(),
            artworkUrl = image(firstString(data, "sizable_cover", "img", "cover", "pic")),
            artist = firstString(data, "author_name", "author", "singername").orEmpty()
                .ifBlank { authorNames(data) },
            publishDate = firstString(data, "publish_date", "publishdate").orEmpty(),
            introduction = firstString(data, "intro", "description", "des").orEmpty(),
        )
    }

    fun artist(id: String, metaJson: JSONObject): ArtistDetail {
        val data = firstObject(metaJson)
        return ArtistDetail(
            id = id,
            title = firstString(data, "author_name", "authorname", "name", "singername").orEmpty(),
            artworkUrl = image(firstString(data, "sizable_avatar", "avatar", "pic", "imgurl", "img", "cover")),
            summary = firstString(data, "intro", "description", "des").orEmpty(),
        )
    }

    fun song(id: String, metaJson: JSONObject, relatedPage: JSONObject): SongDetail {
        val data = firstObject(metaJson)
        val base = data.optJSONObject("base") ?: data
        val responseId = firstString(base, "album_audio_id", "mixsongid")
        val requestedId = id.removePrefix("song:")
        require(responseId == null || !requestedId.all(Char::isDigit) || responseId == requestedId) {
            "Song metadata identity mismatch"
        }
        val track = KugouTrackMapper.map(data)
        return SongDetail(
            id = id,
            title = track?.title ?: firstString(base, "songname", "audio_name", "name", "title").orEmpty(),
            artworkUrl = track?.artworkUri ?: image(firstString(data, "sizable_cover", "img", "album_img", "pic")
                ?: data.optJSONObject("album_info")?.let { firstString(it, "sizable_cover", "cover") }),
            track = track,
            related = cardPage(relatedPage, ContentKind.SONG, "song:"),
        )
    }

    fun mv(id: String, metaJson: JSONObject, relatedPage: JSONObject): MvDetail {
        val data = firstObject(metaJson)
        return MvDetail(
            id = id,
            title = firstString(data, "name", "video_name", "filename", "title").orEmpty(),
            artworkUrl = image(firstString(data, "img", "cover", "pic")),
            artist = firstString(data, "author", "author_name", "singername", "singer").orEmpty(),
            durationMs = durationMs(data),
            playCount = firstLong(data, "play_count", "playcount", "heat"),
            introduction = firstString(data, "intro", "description", "des").orEmpty(),
            related = cardPage(relatedPage, ContentKind.MV, "mv:"),
        )
    }

    fun trackPage(json: JSONObject, pageHint: Int = 1): PagedResult<Track> {
        val tracks = KugouTrackMapper.extractTracks(json)
        val base = json.optJSONObject("data") ?: json
        val total = firstInt(base, "total")
        return PagedResult(tracks, trackNextCursor(base, total, tracks.size, pageHint), total)
    }

    fun cardPage(json: JSONObject, kind: ContentKind, idPrefix: String, pageHint: Int = 1): PagedResult<ContentCard> {
        val data = json.optJSONObject("data")
        // Artist albums/videos return root data[], unlike the older nested feeds.
        val array = json.optJSONArray("data") ?: CARD_CONTAINERS.firstNotNullOfOrNull { key ->
            (data?.optJSONArray(key) ?: json.optJSONArray(key))?.takeIf { it.length() > 0 }
        }
        val cards = buildList {
            if (array != null) repeat(array.length()) { index ->
                array.optJSONObject(index)?.let { toCard(it, kind, idPrefix) }?.let(::add)
            }
        }.distinctBy { it.id }
        val base = data ?: json
        val total = firstInt(base, "total") ?: firstInt(json, "total")
        return PagedResult(cards, trackNextCursor(base, total, array?.length() ?: 0, pageHint), total)
    }

    fun commentPage(json: JSONObject): PagedResult<Comment> {
        val data = json.optJSONObject("data")
        val list = data?.optJSONArray("list") ?: json.optJSONArray("list") ?: return PagedResult(emptyList())
        val comments = buildList {
            repeat(list.length()) { index ->
                list.optJSONObject(index)?.let(::comment)?.let(::add)
            }
        }
        val base = data ?: json
        val total = firstInt(base, "total")
        return PagedResult(comments, nextCursor(base, total), total)
    }

    // --- helpers ---

    private fun firstObject(json: JSONObject): JSONObject {
        json.optJSONObject("data")?.let { data ->
            for (key in listOf("info", "list")) {
                data.optJSONArray(key)?.let { array ->
                    if (array.length() > 0) array.optJSONObject(0)?.let { return it }
                }
            }
            return data
        }
        json.optJSONArray("data")?.let { array ->
            if (array.length() > 0) array.optJSONObject(0)?.let { return it }
        }
        return json
    }

    private fun comment(raw: JSONObject): Comment? {
        val id = firstString(raw, "tid", "id") ?: return null
        val author = raw.optJSONObject("author") ?: JSONObject()
        val user = UserProfile(
            id = firstString(author, "user_id", "userid", "id").orEmpty(),
            nickname = firstString(author, "user_name", "username", "nickname", "name").orEmpty(),
            avatarUrl = image(firstString(author, "avatar", "img", "pic")),
        )
        return Comment(
            id = id,
            user = user,
            content = firstString(raw, "content", "msg", "text").orEmpty(),
            timeEpochMs = firstLong(raw, "time", "addtime", "publish_time") * 1_000L,
            likeCount = firstLong(raw, "like_count", "likecount", "praise_count"),
            replies = null,
        )
    }

    private fun toCard(raw: JSONObject, kind: ContentKind, idPrefix: String): ContentCard? {
        // A video can carry album_audio_id/album_id too; those are NOT video IDs.
        val idKeys = when (kind) {
            ContentKind.SONG -> arrayOf("album_audio_id", "mixsongid", "song_id", "audio_id", "id")
            ContentKind.ALBUM -> arrayOf("album_id", "albumid", "id")
            ContentKind.MV -> arrayOf("video_id", "videoid", "id")
            ContentKind.ARTIST -> arrayOf("author_id", "singerid", "id")
            ContentKind.PLAYLIST -> arrayOf("global_collection_id", "listid", "specialid", "id")
            ContentKind.USER -> arrayOf("userid", "user_id", "id")
        }
        val titleKeys = when (kind) {
            ContentKind.SONG -> arrayOf("songname", "audio_name", "name", "title", "filename")
            ContentKind.ALBUM -> arrayOf("album_name", "albumname", "name", "title")
            ContentKind.MV -> arrayOf("video_name", "name", "title", "filename")
            ContentKind.ARTIST -> arrayOf("author_name", "singername", "name", "title")
            ContentKind.PLAYLIST -> arrayOf("listname", "specialname", "name", "title")
            ContentKind.USER -> arrayOf("nickname", "username", "name", "title")
        }
        val rawId = firstString(raw, *idKeys) ?: return null
        return ContentCard(
            id = idPrefix + rawId,
            title = firstString(raw, *titleKeys).orEmpty(),
            kind = kind,
            subtitle = firstString(raw, "singername", "author_name", "author", "singer").orEmpty(),
            artworkUrl = image(firstString(raw, "hdpic", "sizable_cover", "img", "album_img", "pic", "cover")),
        )
    }

    private fun authorNames(raw: JSONObject): String {
        val authors = raw.optJSONArray("authors") ?: return ""
        return buildList {
            repeat(authors.length()) { index ->
                authors.optJSONObject(index)
                    ?.let { firstString(it, "author_name", "name", "singername") }
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.joinToString("、")
    }

    private fun stringList(raw: JSONObject, vararg keys: String): List<String> {
        for (key in keys) {
            when (val value = raw.opt(key)) {
                is String -> if (value.isNotBlank()) {
                    return value.split(Regex("[,，;；]")).map(String::trim).filter(String::isNotBlank)
                }
                is JSONArray -> {
                    val list = buildList {
                        repeat(value.length()) { index ->
                            value.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                    if (list.isNotEmpty()) return list
                }
            }
        }
        return emptyList()
    }

    private fun durationMs(raw: JSONObject): Long {
        val millis = firstLong(raw, "durationms", "duration_ms")
        if (millis > 0) return millis
        val seconds = firstLong(raw, "seconds", "duration")
        return if (seconds > 0) seconds * 1_000L else 0L
    }

    private fun nextCursor(data: JSONObject, total: Int?): String? {
        val page = intValue(data.opt("page"))
        val pagesize = intValue(data.opt("pagesize")) ?: PAGE_SIZE
        if (page == null || page <= 0 || total == null || pagesize <= 0) return null
        return if (page.toLong() * pagesize < total) (page + 1).toString() else null
    }

    private fun trackNextCursor(data: JSONObject, total: Int?, itemCount: Int, pageHint: Int): String? {
        val page = intValue(data.opt("page"))?.takeIf { it > 0 } ?: pageHint.coerceAtLeast(1)
        val pagesize = intValue(data.opt("pagesize"))?.takeIf { it > 0 } ?: PAGE_SIZE
        if (total != null) return if (page.toLong() * pagesize < total) (page + 1).toString() else null
        return if (itemCount >= pagesize) (page + 1).toString() else null
    }

    private fun firstString(raw: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            when (val value = raw.opt(key)) {
                is String -> if (value.isNotBlank()) return value
                is Number -> return value.toString()
            }
        }
        return null
    }

    private fun firstLong(raw: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            when (val value = raw.opt(key)) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    private fun firstInt(raw: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            when (val value = raw.opt(key)) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun intValue(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun image(value: String?): String = value.orEmpty()
        .replace("{size}", "400")
        .let { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }

    private const val PAGE_SIZE = 30
    private val CARD_CONTAINERS = listOf("audios", "albums", "videos", "lists", "info", "list", "items")
}
