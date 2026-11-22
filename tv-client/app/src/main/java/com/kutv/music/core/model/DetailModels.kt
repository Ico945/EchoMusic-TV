package com.kutv.music.core.model

sealed interface ContentDetail {
    val id: String
    val title: String
    val kind: ContentKind
    val artworkUrl: String
    val tabs: List<String>
}

data class PlaylistDetail(
    override val id: String,
    override val title: String,
    override val artworkUrl: String = "",
    val creator: UserProfile? = null,
    val playCount: Long = 0,
    val tags: List<String> = emptyList(),
    val introduction: String = "",
    val trackCount: Int? = null,
    val tracks: PagedResult<Track> = PagedResult(emptyList()),
) : ContentDetail {
    override val kind: ContentKind = ContentKind.PLAYLIST
    override val tabs: List<String> = listOf("songs", "comments", "introduction")
}

data class AlbumDetail(
    override val id: String,
    override val title: String,
    override val artworkUrl: String = "",
    val artist: String = "",
    val publishDate: String = "",
    val introduction: String = "",
    val tracks: PagedResult<Track> = PagedResult(emptyList()),
) : ContentDetail {
    override val kind: ContentKind = ContentKind.ALBUM
    override val tabs: List<String> = listOf("songs", "comments", "introduction")
}

data class ArtistDetail(
    override val id: String,
    override val title: String,
    override val artworkUrl: String = "",
    val summary: String = "",
    val hotTracks: List<Track> = emptyList(),
    val children: PagedResult<ContentCard> = PagedResult(emptyList()),
) : ContentDetail {
    override val kind: ContentKind = ContentKind.ARTIST
    override val tabs: List<String> = listOf("songs", "albums", "mv")
}

data class SongDetail(
    override val id: String,
    override val title: String,
    override val artworkUrl: String = "",
    val track: Track? = null,
    val related: PagedResult<ContentCard> = PagedResult(emptyList()),
) : ContentDetail {
    override val kind: ContentKind = ContentKind.SONG
    override val tabs: List<String> = listOf("info", "comments")
}

data class MvDetail(
    override val id: String,
    override val title: String,
    override val artworkUrl: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val playCount: Long = 0,
    val introduction: String = "",
    val related: PagedResult<ContentCard> = PagedResult(emptyList()),
) : ContentDetail {
    override val kind: ContentKind = ContentKind.MV
    override val tabs: List<String> = listOf("info", "comments")
}

enum class CommentTargetKind { SONG, PLAYLIST, ALBUM }

data class CommentTarget(val kind: CommentTargetKind, val id: String)

data class Comment(
    val id: String,
    val user: UserProfile,
    val content: String,
    val timeEpochMs: Long,
    val likeCount: Long,
    val replies: PagedResult<Comment>? = null,
)
