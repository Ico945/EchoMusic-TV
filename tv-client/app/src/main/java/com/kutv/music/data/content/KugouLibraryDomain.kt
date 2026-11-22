package com.kutv.music.data.content

import com.kutv.music.application.content.SharePresentationUseCase
import com.kutv.music.application.content.ShareProjection
import com.kutv.music.application.content.ShareResolveUseCase
import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.LibrarySection
import com.kutv.music.core.model.LibrarySummary
import com.kutv.music.core.model.MutationTarget
import com.kutv.music.core.model.PagedResult
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.PlaylistMutation
import com.kutv.music.core.model.PlaylistDetail
import com.kutv.music.core.model.ShareTarget
import com.kutv.music.core.model.Track
import com.kutv.music.core.model.UserProfile
import com.kutv.music.direct.KugouNetworkErrorKind
import com.kutv.music.direct.KugouNetworkException
import com.kutv.music.direct.KugouTrackMapper
import com.kutv.music.direct.content.KugouLibraryApi
import com.kutv.music.direct.content.LibraryMapper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Aggregates the KuGou library/profile/share domain over [KugouLibraryApi]. Login state is
 * enforced by the API (which throws [com.kutv.music.data.DirectLoginRequiredException]); this
 * class maps those failures through [Result] unchanged.
 */
class KugouLibraryDomain(
    private val api: KugouLibraryApi,
    private val mapper: LibraryMapper = LibraryMapper,
    private val rememberTracks: (List<Track>) -> List<Track>,
    private val trackLookup: (String) -> Track?,
    private val sharePresentation: SharePresentationUseCase,
) {
    private val deduplicator = RequestDeduplicator(capacity = 128)
    private val recentCloudKvIds = ConcurrentHashMap<String, String>()
    private val shareResolver = ShareResolveUseCase()

    suspend fun library(): Result<LibrarySummary> = attempt {
        coroutineScope {
            val deferred = LibrarySection.entries.associateWith { section ->
                async { fetchSectionCards(section, page = 1) }
            }
            val counts = LibrarySection.entries.associateWith { section ->
                deferred.getValue(section).await().size
            }
            val featured = deferred.getValue(LibrarySection.FAVORITE_SONGS).await().take(5)
            LibrarySummary(counts = counts, featured = featured)
        }
    }

    suspend fun librarySection(section: LibrarySection, cursor: String?): Result<PagedResult<ContentCard>> = attempt {
        val page = cursorToPage(cursor)
        val raw = fetchRaw(section, page)
        if (section == LibrarySection.CLOUD && raw != null) refreshCloudKvIds(raw)
        rememberTracksFor(section, raw)
        val cards = mapper.sectionCards(section, raw, if (section in PLAYLIST_SECTIONS) api.currentUserId() else null)
        // get_all_list mixes created playlists, collected playlists and albums.
        // A filtered page can be empty while the upstream page is still full.
        val hasMore = raw != null && if (section in PLAYLIST_SECTIONS) {
            mapper.playlistPageSize(raw) >= PAGE_SIZE
        } else if (section == LibrarySection.FAVORITE_SONGS) {
            personalTrackRows(raw).length() >= PAGE_SIZE
        } else cards.size >= PAGE_SIZE
        PagedResult(
            items = cards,
            nextCursor = if (hasMore) (page + 1).toString() else null,
            total = if (section in PLAYLIST_SECTIONS) null else raw?.let(mapper::total),
            librarySync = if (section in PLAYLIST_SECTIONS && raw != null) mapper.playlistSyncInfo(raw, api.currentUserId(), page) else null,
        )
    }

    suspend fun favorite(requestId: String, target: MutationTarget, favorite: Boolean): Result<MutationTarget> =
        deduplicator.deduplicate(requestId) {
            attempt {
                when (target.kind) {
                    ContentKind.SONG -> favoriteSong(target.id, favorite)
                    ContentKind.PLAYLIST -> favoritePlaylist(target.id, favorite)
                    ContentKind.ALBUM -> favoriteAlbum(target.id, favorite)
                    ContentKind.ARTIST -> favoriteArtist(target.id, favorite)
                    ContentKind.MV -> throw UnsupportedOperationException("MV 收藏暂不可用")
                    ContentKind.USER -> throw UnsupportedOperationException("用户收藏暂不可用")
                }
                target
            }
        }

    suspend fun addToPlaylist(mutation: PlaylistMutation): Result<PlaylistMutation> = attempt {
        if (mutation.playlistId.isBlank()) throw IllegalArgumentException("歌单 id 不能为空")
        val track = trackLookup(mutation.trackId) ?: throw IllegalArgumentException("未找到歌曲")
        val source = track.source as? PlaybackSource.Kugou
            ?: throw IllegalArgumentException("仅支持酷狗歌曲")
        api.playlistTracksAdd(mutation.playlistId, track.title, source.hash, source.albumId, track.id)
        mutation
    }

    suspend fun deleteCloudItem(trackId: String): Result<Unit> = attempt {
        val kvId = recentCloudKvIds[trackId]
            ?: throw IllegalArgumentException("歌曲不在当前云盘列表")
        api.cloudDelete(kvId, trackId.removePrefix("cloud:"))
    }

    suspend fun profile(userId: String): Result<UserProfile> = attempt {
        val raw = api.profile(userId.toLongOrNull() ?: 0L)
        mapper.profile(raw, userId) ?: throw KugouNetworkException(KugouNetworkErrorKind.DATA)
    }

    suspend fun userPlaylists(userId: String, cursor: String?): Result<PagedResult<ContentCard>> = attempt {
        val page = cursorToPage(cursor)
        val raw = api.userPlaylists(userId.toLongOrNull(), page, PAGE_SIZE)
        val cards = mapper.playlistCards(raw)
        PagedResult(
            items = cards,
            nextCursor = if (mapper.playlistPageSize(raw) >= PAGE_SIZE) (page + 1).toString() else null,
            total = null,
        )
    }

    suspend fun myPlaylists(cursor: String?): Result<PagedResult<ContentCard>> = attempt {
        val page = cursorToPage(cursor)
        val raw = api.userPlaylists(null, page, PAGE_SIZE)
        val cards = mapper.playlistCards(raw)
        PagedResult(
            items = cards,
            nextCursor = if (mapper.playlistPageSize(raw) >= PAGE_SIZE) (page + 1).toString() else null,
            total = null,
            librarySync = mapper.playlistSyncInfo(raw, api.currentUserId(), page),
        )
    }

    suspend fun resolveShare(input: String): Result<ShareTarget> = shareResolver.resolve(input)

    /** User list ids belong to cloudlist, not the public global_collection_id API. */
    suspend fun personalPlaylistTracks(id: String, cursor: String?): Result<PagedResult<Track>> = attempt {
        val identity = id.removePrefix("playlist:").removePrefix("library_")
        val page = cursorToPage(cursor)
        val raw = if (identity.startsWith("gid_")) {
            val gid = identity.removePrefix("gid_")
            require(gid.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "invalid collection id" }
            api.playlistCollectionTracks(gid, page, PAGE_SIZE)
        } else {
            require(identity.matches(Regex("[0-9]+"))) { "invalid personal playlist id" }
            api.playlistTracks(identity, page, PAGE_SIZE)
        }
        val entries = personalTrackRows(raw)
        val tracks = rememberTracks(validatedPersonalTracks(raw))
        PagedResult(tracks, if (entries.length() >= PAGE_SIZE) (page + 1).toString() else null, mapper.total(raw))
    }

    suspend fun personalPlaylist(id: String): Result<PlaylistDetail> = attempt {
        val page = personalPlaylistTracks(id, null).getOrThrow()
        // The selected card supplies its display title in Vue. Do not fetch a
        // public playlist with a different id to manufacture account metadata.
        PlaylistDetail(id = id.removePrefix("playlist:"), title = "我的歌单", tracks = page, trackCount = page.total)
    }

    suspend fun shareProjection(target: ShareTarget): Result<ShareProjection> = sharePresentation.create(target)

    private suspend fun favoriteSong(trackId: String, favorite: Boolean) {
        val listId = resolveLikedPlaylist()?.listId ?: throw LikedPlaylistNotFoundException()
        val track = trackLookup(trackId) ?: throw IllegalArgumentException("未找到歌曲")
        val source = track.source as? PlaybackSource.Kugou
            ?: throw IllegalArgumentException("仅支持酷狗歌曲收藏")
        if (favorite) {
            api.playlistTracksAdd(listId, track.title, source.hash, source.albumId, track.id)
        } else {
            api.playlistTracksDel(listId, resolveLikedFileId(listId, source))
        }
    }

    /** fileid is a cloud-list row identity, NOT album_audio_id/mixsongid or a TV id. */
    private suspend fun resolveLikedFileId(listId: String, source: PlaybackSource.Kugou): String {
        return withTimeoutOrNull(6_000L) {
            val seenPages = mutableSetOf<String>()
            for (page in 1..50) {
                val rows = personalTrackRows(api.playlistTracks(listId, page, 100))
                if (rows.length() > 0 && !seenPages.add(rows.toString())) {
                    throw KugouNetworkException(KugouNetworkErrorKind.DATA)
                }
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val candidate = KugouTrackMapper.map(row)?.source as? PlaybackSource.Kugou ?: continue
                    val fields = listOfNotNull(row, row.optJSONObject("base"), row.optJSONObject("audio_info"))
                    val sameId = source.trackId.toLongOrNull()?.takeIf { it > 0 }?.let { targetId ->
                        fields.any { data -> listOf("album_audio_id", "mixsongid", "MixSongID").any { data.optString(it).toLongOrNull() == targetId } }
                    } == true
                    if (!sameId && !source.hash.equals(candidate.hash, ignoreCase = true)) continue
                    // Echo's song mapper also uses Audioid/audio_id for the file
                    // identity, but never substitute our cached album_audio_id.
                    val fileId = fields.asSequence().flatMap { data ->
                        sequenceOf("fileid", "file_id", "Audioid", "audio_id").map { data.optString(it).toLongOrNull() }
                    }.firstOrNull { it != null && it > 0 }
                        ?: throw KugouNetworkException(KugouNetworkErrorKind.DATA)
                    return@withTimeoutOrNull fileId.toString()
                }
                if (rows.length() < 100) break
            }
            // Never guess an id and risk removing another song.
            throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        } ?: throw KugouNetworkException(KugouNetworkErrorKind.TIMEOUT)
    }

    private suspend fun favoritePlaylist(playlistId: String, favorite: Boolean) {
        if (favorite) {
            api.addPlaylist(listCreateListId = playlistId, type = 0, source = 1)
        } else {
            api.deletePlaylist(playlistId)
        }
    }

    private suspend fun favoriteAlbum(albumId: String, favorite: Boolean) {
        if (favorite) {
            api.addPlaylist(listCreateListId = albumId, type = 1, source = 2)
        } else {
            api.deletePlaylist(albumId)
        }
    }

    private suspend fun favoriteArtist(artistId: String, favorite: Boolean) {
        if (favorite) api.artistFollow(artistId) else api.artistUnfollow(artistId)
    }

    private suspend fun fetchSectionCards(section: LibrarySection, page: Int): List<ContentCard> {
        val raw = fetchRaw(section, page)
        if (section == LibrarySection.CLOUD && raw != null) refreshCloudKvIds(raw)
        rememberTracksFor(section, raw)
        return mapper.sectionCards(section, raw, if (section in PLAYLIST_SECTIONS) api.currentUserId() else null)
    }

    private suspend fun fetchRaw(section: LibrarySection, page: Int): JSONObject? = when (section) {
        LibrarySection.FAVORITE_USERS -> null

        LibrarySection.FAVORITE_SONGS -> {
            val identity = resolveLikedPlaylist()
            if (identity == null) null else {
                val raw = if (identity.collectionId != null) api.playlistCollectionTracks(identity.collectionId, page, PAGE_SIZE)
                    else api.playlistTracks(identity.listId, page, PAGE_SIZE)
                raw.also(::validatedPersonalTracks)
            }
        }

        LibrarySection.FAVORITE_ARTISTS -> api.followArtists()

        LibrarySection.FAVORITE_ALBUMS,
        LibrarySection.CREATED_PLAYLISTS,
        LibrarySection.FAVORITE_PLAYLISTS -> api.userPlaylists(null, page, PAGE_SIZE)

        LibrarySection.FAVORITE_MVS -> api.collectVideos(page, PAGE_SIZE)
        LibrarySection.HISTORY -> api.history()
        LibrarySection.CLOUD -> api.cloudList(page, PAGE_SIZE)
        LibrarySection.PURCHASED_SONGS -> api.purchasedSongs(page, PAGE_SIZE)
        LibrarySection.PURCHASED_ALBUMS -> api.purchasedAlbums(page, PAGE_SIZE)
    }

    private fun rememberTracksFor(section: LibrarySection, raw: JSONObject?) {
        if (raw == null || section !in SONG_SECTIONS) return
        rememberTracks(KugouTrackMapper.extractTracks(raw))
    }

    private fun validatedPersonalTracks(raw: JSONObject): List<Track> {
        val rows = personalTrackRows(raw)
        val tracks = KugouTrackMapper.extractTracks(raw)
        if (rows.length() > 0 && tracks.isEmpty()) throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        return tracks
    }

    private fun personalTrackRows(raw: JSONObject): JSONArray {
        val data = raw.optJSONObject("data") ?: raw
        return data.optJSONArray("info") ?: data.optJSONArray("list") ?: data.optJSONArray("songs")
            ?: throw KugouNetworkException(KugouNetworkErrorKind.DATA)
    }

    /** Fetch all mixed pages before applying Echo's default-list priority. No credential cache. */
    private suspend fun resolveLikedPlaylist(): LibraryMapper.PlaylistReadIdentity? {
        val result = withTimeoutOrNull(6_000L) {
            val rows = JSONArray()
            val seenPages = mutableSetOf<List<String>>()
            for (page in 1..50) {
                val raw = api.userPlaylists(null, page, PAGE_SIZE)
                val entries = mapper.playlistRows(raw)
                val fingerprint = (0 until entries.length()).map { index ->
                    entries.optJSONObject(index)?.let { row ->
                        row.optString("listid", row.optString("list_id", row.optString("specialid", row.optString("id"))))
                    }.orEmpty()
                }
                if (entries.length() > 0 && !seenPages.add(fingerprint)) {
                    throw KugouNetworkException(KugouNetworkErrorKind.DATA)
                }
                repeat(entries.length()) { rows.put(entries.get(it)) }
                val combined = JSONObject().put("info", rows)
                // Exact default name cannot be outranked on a later page.
                if ((0 until rows.length()).any { rows.optJSONObject(it)?.let { row ->
                        row.optString("name", row.optString("listname")).trim() == "我喜欢的音乐" && row.optInt("source") != 2
                    } == true } || entries.length() < PAGE_SIZE) {
                    val id = mapper.favoritePlaylistIdentity(combined)
                    if (id == null && rows.length() > 0) throw LikedPlaylistNotFoundException()
                    return@withTimeoutOrNull Pair(true, id)
                }
            }
            throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        } ?: throw KugouNetworkException(KugouNetworkErrorKind.TIMEOUT)
        return result.second
    }

    private fun refreshCloudKvIds(raw: JSONObject) {
        val kvIds = mapper.cloudKvIds(raw)
        recentCloudKvIds.clear()
        recentCloudKvIds.putAll(kvIds)
    }

    private fun cursorToPage(cursor: String?): Int = cursor?.toIntOrNull()?.takeIf { it > 0 } ?: 1

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private class RequestDeduplicator(capacity: Int) {
        private val cache = object : LinkedHashMap<String, Result<MutationTarget>>(capacity, 0.75f, false) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Result<MutationTarget>>?,
            ): Boolean = size > capacity
        }

        suspend fun deduplicate(
            requestId: String,
            compute: suspend () -> Result<MutationTarget>,
        ): Result<MutationTarget> {
            cache[requestId]?.let { return it }
            val result = compute()
            cache[requestId] = result
            return result
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
        val PLAYLIST_SECTIONS = setOf(
            LibrarySection.CREATED_PLAYLISTS,
            LibrarySection.FAVORITE_PLAYLISTS,
            LibrarySection.FAVORITE_ALBUMS,
        )
        val SONG_SECTIONS = setOf(
            LibrarySection.FAVORITE_SONGS,
            LibrarySection.HISTORY,
            LibrarySection.CLOUD,
            LibrarySection.PURCHASED_SONGS,
        )
    }
}
