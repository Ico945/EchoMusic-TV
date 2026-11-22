package com.kutv.music.core.model

enum class ContentKind {
    SONG,
    PLAYLIST,
    ALBUM,
    ARTIST,
    MV,
    USER,
}

enum class SearchCategory {
    SONG,
    PLAYLIST,
    ALBUM,
    ARTIST,
    LYRIC,
    MV,
}

data class ContentCard(
    val id: String,
    val title: String,
    val kind: ContentKind,
    val subtitle: String = "",
    val artworkUrl: String = "",
    val badge: String? = null,
    val detailId: String? = null,
)

data class PagedResult<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val total: Int? = null,
    val librarySync: LibrarySyncInfo? = null,
)

data class MutationTarget(val kind: ContentKind, val id: String)

data class PlaylistMutation(val playlistId: String, val trackId: String)

data class ShareTarget(val kind: ContentKind, val id: String)

enum class LibrarySection {
    FAVORITE_SONGS,
    FAVORITE_ARTISTS,
    FAVORITE_USERS,
    FAVORITE_ALBUMS,
    FAVORITE_MVS,
    HISTORY,
    CLOUD,
    PURCHASED_SONGS,
    PURCHASED_ALBUMS,
    CREATED_PLAYLISTS,
    FAVORITE_PLAYLISTS,
}

data class UserProfile(
    val id: String,
    val nickname: String,
    val avatarUrl: String = "",
    val summary: String = "",
)

data class RankingSummary(
    val id: String,
    val title: String,
    val artworkUrl: String = "",
)

enum class ExploreSection(val wireName: String) {
    NEW_SONGS("newSongs"), NEW_ALBUMS("newAlbums"), NEW_PLAYLISTS("newPlaylists"), RANKINGS("rankings"), ARTISTS("artists"),
}

data class ExploreContent(
    val newSongs: List<ContentCard>,
    val newAlbums: List<ContentCard>,
    val newPlaylists: List<ContentCard>,
    val artists: List<ContentCard>,
    val rankings: List<RankingSummary>,
    val failedSections: Set<ExploreSection> = emptySet(),
)
