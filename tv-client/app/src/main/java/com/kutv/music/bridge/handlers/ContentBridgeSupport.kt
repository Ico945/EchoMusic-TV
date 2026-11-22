package com.kutv.music.bridge.handlers

import com.kutv.music.bridge.ContentBridgeException
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.LibrarySection
import com.kutv.music.core.model.SearchCategory
import com.kutv.music.data.DirectLoginRequiredException
import com.kutv.music.direct.KugouNetworkErrorKind
import com.kutv.music.direct.KugouNetworkException
import org.json.JSONObject

internal object ContentBridgeSupport {
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,120}$")
    private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")
    private val KIND_PREFIXES = setOf("song", "playlist", "album", "artist", "mv", "user", "cloud")

    fun contentError(error: Throwable, scope: String): ContentBridgeException = when {
        error is com.kutv.music.direct.content.LibraryReadException ->
            ContentBridgeException("LIBRARY_${error.stage.name}", "歌单同步未完成，请保留诊断代码", true, scope)
        error is com.kutv.music.data.content.LikedPlaylistNotFoundException ->
            ContentBridgeException("LIBRARY_LIKED_LIST_MISSING", "未识别到喜欢歌单，请从资料库打开对应歌单", true, scope)
        error is DirectLoginRequiredException ->
            ContentBridgeException("AUTH_EXPIRED", "登录已失效，请重新登录", false, scope)
        error is KugouNetworkException && error.kind == KugouNetworkErrorKind.SESSION_EXPIRED ->
            ContentBridgeException("AUTH_EXPIRED", "登录已失效，请重新登录", false, scope)
        error is KugouNetworkException && error.kind in setOf(KugouNetworkErrorKind.TIMEOUT, KugouNetworkErrorKind.NETWORK) ->
            ContentBridgeException("NETWORK_TIMEOUT", "网络不可用，请重试", true, scope)
        error is KugouNetworkException ->
            ContentBridgeException("CONTENT_INVALID", "内容暂时不可用", false, scope)
        error is UnsupportedOperationException ->
            ContentBridgeException("UNSUPPORTED_OPERATION", "该功能暂不可用", false, scope)
        else -> ContentBridgeException("INTERNAL_ERROR", "暂时无法完成操作", true, scope)
    }

    fun requireQuery(payload: JSONObject): String {
        val query = payload.optString("query", "").trim()
        require(query.length in 1..64) { "query must be 1..64 characters" }
        return query
    }

    fun requireCursor(payload: JSONObject): String? {
        val cursor = payload.opt("cursor") ?: return null
        require(cursor is String && cursor.length in 1..256) { "cursor is invalid" }
        return cursor
    }

    fun requireSearchCategory(payload: JSONObject): SearchCategory {
        val raw = payload.optString("category")
        return runCatching { SearchCategory.valueOf(raw.uppercase()) }.getOrElse {
            throw IllegalArgumentException("category is unsupported")
        }
    }

    fun requireContentKind(payload: JSONObject): ContentKind {
        val raw = payload.optString("kind")
        val kind = runCatching { ContentKind.valueOf(raw.uppercase()) }.getOrElse {
            throw IllegalArgumentException("kind is unsupported")
        }
        require(kind != ContentKind.USER) { "kind is unsupported" }
        return kind
    }

    fun requireCommentKind(payload: JSONObject): com.kutv.music.core.model.CommentTargetKind {
        val raw = payload.optString("kind")
        return when (raw) {
            "song" -> com.kutv.music.core.model.CommentTargetKind.SONG
            "playlist" -> com.kutv.music.core.model.CommentTargetKind.PLAYLIST
            "album" -> com.kutv.music.core.model.CommentTargetKind.ALBUM
            else -> throw IllegalArgumentException("comment kind is unsupported")
        }
    }

    fun requireSection(payload: JSONObject): LibrarySection {
        val raw = payload.optString("section")
        val sectionKey = sectionJsonToEnum[raw] ?: throw IllegalArgumentException("section is unsupported")
        return sectionKey
    }

    fun requireTab(payload: JSONObject): String {
        val tab = payload.optString("tab")
        require(tab in setOf("songs", "albums", "mv")) { "tab is unsupported" }
        return tab
    }

    fun requireId(payload: JSONObject, expectedPrefix: String? = null): String {
        val raw = payload.optString("id")
        require(raw.isNotBlank()) { "id is required" }
        val normalized = if (expectedPrefix != null) {
            require(raw.startsWith("$expectedPrefix:")) { "id prefix is invalid" }
            raw.removePrefix("$expectedPrefix:")
        } else {
            val prefix = raw.substringBefore(':', missingDelimiterValue = "")
            if (prefix in KIND_PREFIXES) raw.substringAfter(':') else raw
        }
        require(ID_PATTERN.matches(normalized)) { "id is invalid" }
        return normalized
    }

    fun requireTrackId(payload: JSONObject, name: String = "trackId"): String {
        val raw = payload.optString(name)
        require(raw.isNotBlank() && ID_PATTERN.matches(raw)) { "$name is invalid" }
        return raw
    }

    fun requireCloudTrackId(payload: JSONObject): String {
        val raw = payload.optString("trackId")
        require(raw.startsWith("cloud:") && raw.length in 7..127) { "trackId is invalid" }
        val bare = raw.removePrefix("cloud:")
        require(ID_PATTERN.matches(bare)) { "trackId is invalid" }
        return raw
    }

    fun requireRequestId(payload: JSONObject): String {
        val requestId = payload.optString("requestId")
        require(REQUEST_ID_PATTERN.matches(requestId)) { "requestId is invalid" }
        return requestId
    }

    fun requireLink(payload: JSONObject): String {
        val link = payload.optString("link").trim()
        require(link.length in 1..2048) { "link is invalid" }
        return link
    }

    fun requireBoolean(payload: JSONObject, name: String): Boolean {
        val value = payload.opt(name)
        require(value is Boolean) { "$name must be a boolean" }
        return value
    }

    fun sectionEnumToJson(section: LibrarySection): String = sectionJsonToEnum.entries
        .firstOrNull { it.value == section }?.key
        ?: throw IllegalArgumentException("section is unsupported")

    private val sectionJsonToEnum = linkedMapOf(
        "favoriteSongs" to LibrarySection.FAVORITE_SONGS,
        "favoriteArtists" to LibrarySection.FAVORITE_ARTISTS,
        "favoriteUsers" to LibrarySection.FAVORITE_USERS,
        "favoriteAlbums" to LibrarySection.FAVORITE_ALBUMS,
        "favoriteMvs" to LibrarySection.FAVORITE_MVS,
        "history" to LibrarySection.HISTORY,
        "cloud" to LibrarySection.CLOUD,
        "purchasedSongs" to LibrarySection.PURCHASED_SONGS,
        "purchasedAlbums" to LibrarySection.PURCHASED_ALBUMS,
        "createdPlaylists" to LibrarySection.CREATED_PLAYLISTS,
        "favoritePlaylists" to LibrarySection.FAVORITE_PLAYLISTS,
    )
}
