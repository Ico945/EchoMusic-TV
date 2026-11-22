package com.kutv.music.data.content

import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ExploreContent
import com.kutv.music.core.model.ExploreSection
import com.kutv.music.core.model.PagedResult
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.RankingSummary
import com.kutv.music.core.model.SearchCategory
import com.kutv.music.core.model.Track
import com.kutv.music.direct.content.BrowseMapper
import com.kutv.music.direct.content.KugouBrowseApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/**
 * Aggregates raw browse responses into the [com.kutv.music.data.TvContentRepository]
 * browse surface. Explore isolates provider/mapper failures per section,
 * bounds their latency, and propagates coroutine cancellation. Other methods
 * expose their existing Result-based repository contract.
 */
class KugouBrowseDomain(
    private val api: KugouBrowseApi,
    private val mapper: BrowseMapper = BrowseMapper,
    private val rememberTracks: (List<Track>) -> List<Track>,
    private val trackLookup: (String) -> Track?,
) {
    suspend fun explore(): Result<ExploreContent> = capture {
        coroutineScope {
            val songsDeferred = async { section {
                val json = api.newSongs(1)
                val cards = mapper.cardPage(json, ContentKind.SONG, "").items
                rememberTracks(mapper.cardsFromTracks(json))
                cards
            } }
            val albumsDeferred = async { section { mapper.cardPage(api.newAlbums(1), ContentKind.ALBUM, "album:").items } }
            val playlistsDeferred = async { section { mapper.cardPage(api.newPlaylists(1), ContentKind.PLAYLIST, "playlist:").items } }
            val rankingsDeferred = async { section { mapper.rankings(api.rankings()) } }
            val artistsDeferred = async { section { mapper.cardPage(api.artists(), ContentKind.ARTIST, "artist:").items } }
            val songs = songsDeferred.await()
            val albums = albumsDeferred.await()
            val playlists = playlistsDeferred.await()
            val rankings = rankingsDeferred.await()
            val artists = artistsDeferred.await()
            ExploreContent(
                newSongs = songs.getOrDefault(emptyList()),
                newAlbums = albums.getOrDefault(emptyList()),
                newPlaylists = playlists.getOrDefault(emptyList()),
                artists = artists.getOrDefault(emptyList()),
                rankings = rankings.getOrDefault(emptyList()),
                failedSections = buildSet {
                    if (songs.isFailure) add(ExploreSection.NEW_SONGS)
                    if (albums.isFailure) add(ExploreSection.NEW_ALBUMS)
                    if (playlists.isFailure) add(ExploreSection.NEW_PLAYLISTS)
                    if (rankings.isFailure) add(ExploreSection.RANKINGS)
                    if (artists.isFailure) add(ExploreSection.ARTISTS)
                },
            )
        }
    }

    private suspend fun <T> section(block: suspend () -> T): Result<T> =
        withTimeoutOrNull(8_000L) { capture(block) }
            ?: Result.failure(TimeoutException("Explore section timed out"))

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun dailyRecommendations(): Result<List<Track>> = runCatching {
        rememberTracks(mapper.cardsFromTracks(api.dailyRecommendations()))
    }

    suspend fun privateFm(): Result<List<Track>> = runCatching {
        rememberTracks(mapper.cardsFromTracks(api.privateFm(action = "play")))
    }

    suspend fun discardPrivateFm(trackId: String): Result<Unit> = runCatching {
        val track = trackLookup(trackId) ?: throw IllegalArgumentException("Unknown track id")
        val hash = (track.source as? PlaybackSource.Kugou)?.hash?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Track is not a Kugou stream")
        api.privateFm(action = "discard", hash = hash, songId = track.id)
        Unit
    }

    suspend fun hotSearch(): Result<List<String>> = runCatching {
        mapper.hotWords(api.hotSearch())
    }

    suspend fun rankings(): Result<List<RankingSummary>> = runCatching {
        mapper.rankings(api.rankings())
    }

    suspend fun ranking(id: String, cursor: String?): Result<PagedResult<Track>> = runCatching {
        val page = parseCursor(cursor)
        val json = api.rankingTracks(id, page)
        val result = mapper.trackPage(json, requestedPage = page)
        // Non-empty upstream data must not silently become a successful empty
        // chart if the provider changes its song schema again.
        check(result.items.isNotEmpty() || (result.total ?: 0) == 0) { "Ranking tracks could not be mapped" }
        rememberTracks(result.items)
        result
    }

    suspend fun search(query: String, category: SearchCategory, cursor: String?): Result<PagedResult<ContentCard>> =
        runCatching {
            val page = parseCursor(cursor)
            val json = api.search(query, category, page)
            val kind = category.toContentKind()
            if (kind == ContentKind.SONG) {
                rememberTracks(mapper.cardsFromTracks(json))
            }
            mapper.cardPage(json, kind, category.toIdPrefix())
        }

    private fun parseCursor(cursor: String?): Int =
        (cursor ?: "1").toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid cursor")

    private fun SearchCategory.toContentKind(): ContentKind = when (this) {
        SearchCategory.SONG, SearchCategory.LYRIC -> ContentKind.SONG
        SearchCategory.PLAYLIST -> ContentKind.PLAYLIST
        SearchCategory.ALBUM -> ContentKind.ALBUM
        SearchCategory.ARTIST -> ContentKind.ARTIST
        SearchCategory.MV -> ContentKind.MV
    }

    private fun SearchCategory.toIdPrefix(): String = when (toContentKind()) {
        ContentKind.SONG -> ""
        ContentKind.PLAYLIST -> "playlist:"
        ContentKind.ALBUM -> "album:"
        ContentKind.ARTIST -> "artist:"
        ContentKind.MV -> "mv:"
        ContentKind.USER -> "user:"
    }
}
