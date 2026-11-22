package com.kutv.music.data

import com.kutv.music.core.model.AlbumDetail
import com.kutv.music.core.model.ArtistDetail
import com.kutv.music.core.model.Comment
import com.kutv.music.core.model.CommentTarget
import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentDetail
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ExploreContent
import com.kutv.music.core.model.LibrarySection
import com.kutv.music.core.model.LibrarySummary
import com.kutv.music.core.model.MutationTarget
import com.kutv.music.core.model.MvDetail
import com.kutv.music.core.model.PagedResult
import com.kutv.music.core.model.PlaylistDetail
import com.kutv.music.core.model.PlaylistMutation
import com.kutv.music.core.model.RankingSummary
import com.kutv.music.core.model.SearchCategory
import com.kutv.music.core.model.SongDetail
import com.kutv.music.core.model.Track
import com.kutv.music.core.model.UserProfile
import com.kutv.music.data.content.KugouBrowseDomain
import com.kutv.music.data.content.KugouDetailDomain
import com.kutv.music.data.content.KugouLibraryDomain

class KugouTvContentRepository(
    private val browseDomain: KugouBrowseDomain,
    private val detailDomain: KugouDetailDomain,
    private val libraryDomain: KugouLibraryDomain,
) : TvContentRepository {
    override suspend fun explore(): Result<ExploreContent> = browseDomain.explore()

    override suspend fun dailyRecommendations(): Result<List<Track>> = browseDomain.dailyRecommendations()

    override suspend fun privateFm(): Result<List<Track>> = browseDomain.privateFm()

    override suspend fun discardPrivateFm(trackId: String): Result<Unit> = browseDomain.discardPrivateFm(trackId)

    override suspend fun hotSearch(): Result<List<String>> = browseDomain.hotSearch()

    override suspend fun rankings(): Result<List<RankingSummary>> = browseDomain.rankings()

    override suspend fun ranking(id: String, cursor: String?): Result<PagedResult<Track>> =
        browseDomain.ranking(id, cursor)

    override suspend fun search(query: String, category: SearchCategory, cursor: String?): Result<PagedResult<ContentCard>> =
        browseDomain.search(query, category, cursor)

    override suspend fun detail(kind: ContentKind, id: String): Result<ContentDetail> =
        if (kind == ContentKind.PLAYLIST && isPersonalPlaylist(id)) libraryDomain.personalPlaylist(id)
        else detailDomain.detail(kind, id)

    override suspend fun playlistTracks(id: String, cursor: String?): Result<PagedResult<Track>> =
        if (isPersonalPlaylist(id)) libraryDomain.personalPlaylistTracks(id, cursor)
        else detailDomain.playlistTracks(id, cursor)

    private fun isPersonalPlaylist(id: String): Boolean = id.removePrefix("playlist:").startsWith("library_")

    override suspend fun albumTracks(id: String, cursor: String?): Result<PagedResult<Track>> =
        detailDomain.albumTracks(id, cursor)

    override suspend fun artistChildren(id: String, tab: String, cursor: String?): Result<PagedResult<ContentCard>> =
        detailDomain.artistChildren(id, tab, cursor)

    override suspend fun artistTracks(id: String, cursor: String?): Result<PagedResult<Track>> =
        detailDomain.artistTracks(id, cursor)

    override suspend fun comments(target: CommentTarget, cursor: String?): Result<PagedResult<Comment>> =
        detailDomain.comments(target, cursor)

    override suspend fun library(): Result<LibrarySummary> = libraryDomain.library()

    override suspend fun librarySection(section: LibrarySection, cursor: String?): Result<PagedResult<ContentCard>> =
        libraryDomain.librarySection(section, cursor)

    override suspend fun favorite(target: MutationTarget, favorite: Boolean): Result<MutationTarget> =
        libraryDomain.favorite(requestIdFor(target, favorite), target, favorite)

    override suspend fun addToPlaylist(mutation: PlaylistMutation): Result<PlaylistMutation> =
        libraryDomain.addToPlaylist(mutation)

    override suspend fun deleteCloudItem(trackId: String): Result<Unit> = libraryDomain.deleteCloudItem(trackId)

    override suspend fun profile(userId: String): Result<UserProfile> = libraryDomain.profile(userId)

    override suspend fun userPlaylists(userId: String, cursor: String?): Result<PagedResult<ContentCard>> =
        libraryDomain.userPlaylists(userId, cursor)

    override suspend fun myPlaylists(cursor: String?): Result<PagedResult<ContentCard>> =
        libraryDomain.myPlaylists(cursor)

    override suspend fun favoriteWithRequestId(
        requestId: String,
        target: MutationTarget,
        favorite: Boolean,
    ): Result<MutationTarget> = libraryDomain.favorite(requestId, target, favorite)

    override suspend fun resolveShare(input: String): Result<com.kutv.music.core.model.ShareTarget> =
        libraryDomain.resolveShare(input)

    override suspend fun shareProjection(target: com.kutv.music.core.model.ShareTarget): Result<com.kutv.music.application.content.ShareProjection> =
        libraryDomain.shareProjection(target)

    private fun requestIdFor(target: MutationTarget, favorite: Boolean): String =
        "fav-${target.kind.name.lowercase()}-${target.id.hashCode().let { it and 0x7fffffff }}-${if (favorite) 1 else 0}"
}
