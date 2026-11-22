package com.kutv.music.direct.content

import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.LibrarySection
import com.kutv.music.core.model.LibrarySyncInfo
import com.kutv.music.core.model.UserProfile
import com.kutv.music.direct.KugouTrackMapper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps KuGou library responses. Invalid playlist data must not masquerade as
 * a successful empty account; optional legacy sections keep their own parsing.
 */
object LibraryMapper {
    /** Count the upstream mixed page, not the filtered cards. Missing data is not an empty library. */
    fun playlistPageSize(raw: JSONObject): Int =
        playlistRows(raw).length()

    fun playlistSyncInfo(raw: JSONObject, currentUserId: String, page: Int): LibrarySyncInfo {
        val entries = playlistEntries(raw)
        val playlists = entries.filter { it.source != 2 }
        val created = playlists.count { it.isOwned(currentUserId) }
        return LibrarySyncInfo(page, entries.size, playlists.size, created, playlists.size - created,
            entries.size - playlists.size, playlists.count { it.creatorUserId == null })
    }

    fun sectionCards(section: LibrarySection, raw: JSONObject?, currentUserId: String? = null): List<ContentCard> {
        if (raw == null) return emptyList()
        return run {
            when (section) {
                LibrarySection.FAVORITE_SONGS,
                LibrarySection.HISTORY -> trackCards(raw)

                LibrarySection.FAVORITE_ARTISTS -> followArtists(raw)
                LibrarySection.FAVORITE_USERS -> emptyList()
                LibrarySection.FAVORITE_ALBUMS -> favoriteAlbums(raw)
                LibrarySection.FAVORITE_MVS -> collectVideos(raw)
                LibrarySection.CLOUD -> cloudItems(raw)
                LibrarySection.PURCHASED_SONGS -> purchasedSongs(raw)
                LibrarySection.PURCHASED_ALBUMS -> purchasedAlbums(raw)
                LibrarySection.CREATED_PLAYLISTS -> createdPlaylists(raw, currentUserId)
                LibrarySection.FAVORITE_PLAYLISTS -> favoritePlaylists(raw, currentUserId)
            }
        }
    }

    fun trackCards(raw: JSONObject): List<ContentCard> = KugouTrackMapper.extractTracks(raw).map { track ->
        ContentCard(
            id = track.id,
            title = track.title,
            kind = ContentKind.SONG,
            subtitle = track.artists.joinToString("、"),
            artworkUrl = track.artworkUri,
        )
    }

    fun playlistCards(raw: JSONObject): List<ContentCard> = playlistEntries(raw).filter { it.source != 2 }.map { it.toPlaylistCard() }

    fun createdPlaylists(raw: JSONObject, currentUserId: String? = null): List<ContentCard> =
        playlistEntries(raw).filter { it.source != 2 && it.isOwned(currentUserId) }.map { it.toPlaylistCard() }

    fun favoritePlaylists(raw: JSONObject, currentUserId: String? = null): List<ContentCard> =
        playlistEntries(raw).filter { it.source != 2 && !it.isOwned(currentUserId) }.map { it.toPlaylistCard() }

    fun favoriteAlbums(raw: JSONObject): List<ContentCard> =
        playlistEntries(raw).filter { it.source == 2 }.map { entry ->
            ContentCard(
                id = entry.listCreateListId.ifBlank { entry.listId },
                title = entry.name,
                kind = ContentKind.ALBUM,
                artworkUrl = entry.img,
            )
        }

    fun cloudItems(raw: JSONObject): List<ContentCard> {
        val array = container(raw, "list", "files", "info", "songs", "data") ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val albumAudioId = firstString(item, "album_audio_id", "audio_id", "mixsongid") ?: return@repeat
                add(
                    ContentCard(
                        id = "cloud:$albumAudioId",
                        title = firstString(item, "filename", "name", "songname", "title").orEmpty(),
                        kind = ContentKind.SONG,
                        artworkUrl = image(firstString(item, "img", "pic", "cover").orEmpty()),
                    ),
                )
            }
        }
    }

    fun cloudKvIds(raw: JSONObject): Map<String, String> {
        val array = container(raw, "list", "files", "info", "songs", "data") ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        repeat(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@repeat
            val albumAudioId = firstString(item, "album_audio_id", "audio_id", "mixsongid") ?: return@repeat
            val kvId = firstString(item, "kv_id", "fileid", "file_id") ?: return@repeat
            result["cloud:$albumAudioId"] = kvId
        }
        return result
    }

    fun purchasedSongs(raw: JSONObject): List<ContentCard> = trackCards(raw)

    fun purchasedAlbums(raw: JSONObject): List<ContentCard> {
        val array = container(raw, "list", "goods", "albums", "info") ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = firstString(item, "album_id", "albumid", "id") ?: return@repeat
                add(
                    ContentCard(
                        id = id,
                        title = firstString(item, "album_name", "name", "title").orEmpty(),
                        kind = ContentKind.ALBUM,
                        subtitle = firstString(item, "author_name", "singer", "singername").orEmpty(),
                        artworkUrl = image(firstString(item, "sizable_cover", "img", "pic", "cover").orEmpty()),
                    ),
                )
            }
        }
    }

    fun followArtists(raw: JSONObject): List<ContentCard> {
        val array = container(raw, "list", "info", "users") ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = firstString(item, "singerid", "singer_id", "id") ?: return@repeat
                add(
                    ContentCard(
                        id = id,
                        title = firstString(item, "singername", "name", "author_name").orEmpty(),
                        kind = ContentKind.ARTIST,
                        artworkUrl = image(firstString(item, "pic", "img", "avatar").orEmpty()),
                    ),
                )
            }
        }
    }

    fun collectVideos(raw: JSONObject): List<ContentCard> {
        val array = container(raw, "list", "info", "videos") ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = firstString(item, "video_id", "id") ?: return@repeat
                add(
                    ContentCard(
                        id = id,
                        title = firstString(item, "name", "title", "filename").orEmpty(),
                        kind = ContentKind.MV,
                        artworkUrl = image(firstString(item, "pic", "img", "cover").orEmpty()),
                    ),
                )
            }
        }
    }

    fun profile(raw: JSONObject, id: String): UserProfile? {
        val data = raw.optJSONObject("data") ?: return null
        val nickname = data.optString("nickname")
            .ifBlank { data.optString("user_name") }
            .ifBlank { data.optString("username") }
        if (nickname.isBlank()) return null
        return UserProfile(
            id = id,
            nickname = nickname,
            avatarUrl = image(data.optString("pic").ifBlank { data.optString("avatar") }),
            summary = data.optString("signature")
                .ifBlank { data.optString("introduce") }
                .ifBlank { data.optString("introduction") },
        )
    }

    fun favoritePlaylistId(raw: JSONObject): String? =
        playlistEntries(raw).let { entries ->
            val playlists = entries.filter { it.source != 2 }
            // Concept accounts can contain BOTH an empty legacy 默认收藏 and a
            // populated 我喜欢. An exact liked-name alias must outrank a generic
            // default flag; never let response order select the empty legacy list.
            (playlists.firstOrNull { it.name.trim() == "我喜欢的音乐" }
                ?: playlists.firstOrNull { it.name.trim() == "我喜欢" }
                ?: playlists.firstOrNull { it.isDefault }
                ?: playlists.firstOrNull { "喜欢" in it.name.trim() }
                ?: playlists.firstOrNull { it.name.trim() == "默认收藏" }
                ?: playlists.firstOrNull { it.type == 1 })?.listId
        }

    fun playlistRows(raw: JSONObject): JSONArray = container(raw, "list", "lists", "info")
        ?: throw LibraryReadException(LibraryReadException.Stage.RESPONSE_SHAPE)

    data class PlaylistReadIdentity(val listId: String, val collectionId: String?)

    fun favoritePlaylistIdentity(raw: JSONObject): PlaylistReadIdentity? {
        val listId = favoritePlaylistId(raw) ?: return null
        val entry = playlistEntries(raw).first { it.listId == listId }
        return PlaylistReadIdentity(entry.listId, entry.collectionId)
    }

    fun total(raw: JSONObject): Int? {
        val data = raw.optJSONObject("data") ?: raw
        return when (val value = data.opt("total") ?: data.opt("count") ?: data.opt("total_count")) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private data class PlaylistEntry(
        val listId: String,
        val name: String,
        val img: String,
        val type: Int,
        val source: Int,
        val listCreateListId: String,
        val creatorUserId: String?,
        val isDefault: Boolean,
        val collectionId: String?,
    ) {
        // Match Echo's signed-in classification. Missing creator metadata is
        // NOT proof of ownership, even when the upstream type is absent/zero.
        // Only legacy callers without an account retain type-based behavior.
        fun isOwned(currentUserId: String?): Boolean =
            if (currentUserId != null) creatorUserId?.trim() == currentUserId.trim()
            else type == 0

        fun toPlaylistCard(): ContentCard = ContentCard(
            id = listId,
            detailId = collectionId?.let { "playlist:library_gid_$it" } ?: "playlist:library_$listId",
            title = name.ifBlank { "未命名歌单" },
            kind = ContentKind.PLAYLIST,
            artworkUrl = img,
        )
    }

    private fun playlistEntries(raw: JSONObject): List<PlaylistEntry> {
        val array = playlistRows(raw)
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index)
                    ?: throw LibraryReadException(LibraryReadException.Stage.ROW_ID)
                // Personal cloudlist ids must never fall back to a public collection gid.
                val listId = firstString(item, "listid", "list_id", "specialid", "id")
                    ?: throw LibraryReadException(LibraryReadException.Stage.ROW_ID)
                add(
                    PlaylistEntry(
                        listId = listId,
                        name = firstString(item, "listname", "name", "specialname", "title").orEmpty(),
                        img = image(firstString(item, "img", "pic", "cover", "imgurl").orEmpty()),
                        type = item.optInt("type", 0),
                        source = item.optInt("source", 0),
                        listCreateListId = firstString(item, "list_create_listid", "list_create_gid").orEmpty(),
                        creatorUserId = firstString(item, "list_create_userid"),
                        isDefault = item.optInt("is_def") in setOf(1, 2) || item.optInt("is_default") == 1,
                        collectionId = sequenceOf("list_create_gid", "global_collection_id")
                            .mapNotNull { key -> firstString(item, key)?.trim()?.takeIf { it != "0" && it != "-1" } }.firstOrNull(),
                    ),
                )
            }
        }
    }

    private fun container(raw: JSONObject, vararg keys: String): JSONArray? {
        val data = raw.optJSONObject("data") ?: raw
        keys.forEach { key -> data.optJSONArray(key)?.let { return it } }
        keys.forEach { key -> raw.optJSONArray(key)?.let { return it } }
        return null
    }

    private fun firstString(raw: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = raw.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value is Number) return value.toString()
        }
        return null
    }

    private fun image(url: String): String =
        url.replace("{size}", "400")
            .let { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }
}
