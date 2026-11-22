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
import com.kutv.music.core.model.ShareTarget
import com.kutv.music.core.model.SongDetail
import com.kutv.music.core.model.Track
import com.kutv.music.core.model.UserProfile
import com.kutv.music.application.content.ShareProjection

interface TvContentRepository {
    suspend fun explore(): Result<ExploreContent>
    suspend fun dailyRecommendations(): Result<List<Track>>
    suspend fun privateFm(): Result<List<Track>>
    suspend fun discardPrivateFm(trackId: String): Result<Unit>
    suspend fun hotSearch(): Result<List<String>>
    suspend fun rankings(): Result<List<RankingSummary>>
    suspend fun ranking(id: String, cursor: String?): Result<PagedResult<Track>>
    suspend fun search(query: String, category: SearchCategory, cursor: String?): Result<PagedResult<ContentCard>>
    suspend fun detail(kind: ContentKind, id: String): Result<ContentDetail>
    suspend fun playlistTracks(id: String, cursor: String?): Result<PagedResult<Track>>
    suspend fun albumTracks(id: String, cursor: String?): Result<PagedResult<Track>>
    suspend fun artistChildren(id: String, tab: String, cursor: String?): Result<PagedResult<ContentCard>>
    suspend fun artistTracks(id: String, cursor: String?): Result<PagedResult<Track>> =
        Result.failure(UnsupportedOperationException("Artist tracks are unavailable"))
    suspend fun comments(target: CommentTarget, cursor: String?): Result<PagedResult<Comment>>
    suspend fun library(): Result<LibrarySummary>
    suspend fun librarySection(section: LibrarySection, cursor: String?): Result<PagedResult<ContentCard>>
    suspend fun favorite(target: MutationTarget, favorite: Boolean): Result<MutationTarget>
    suspend fun favoriteWithRequestId(requestId: String, target: MutationTarget, favorite: Boolean): Result<MutationTarget>
    suspend fun addToPlaylist(mutation: PlaylistMutation): Result<PlaylistMutation>
    suspend fun deleteCloudItem(trackId: String): Result<Unit>
    suspend fun profile(userId: String): Result<UserProfile>
    suspend fun userPlaylists(userId: String, cursor: String?): Result<PagedResult<ContentCard>>
    suspend fun myPlaylists(cursor: String?): Result<PagedResult<ContentCard>>
    suspend fun resolveShare(input: String): Result<ShareTarget>
    suspend fun shareProjection(target: ShareTarget): Result<ShareProjection>
}
