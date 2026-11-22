package com.kutv.music.bridge.mappers

import com.kutv.music.core.model.AlbumDetail
import com.kutv.music.core.model.ArtistDetail
import com.kutv.music.core.model.Comment
import com.kutv.music.core.model.CommentTarget
import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentDetail
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.LibrarySection
import com.kutv.music.core.model.LibrarySummary
import com.kutv.music.core.model.MutationTarget
import com.kutv.music.core.model.MvDetail
import com.kutv.music.core.model.PagedResult
import com.kutv.music.core.model.PlaylistDetail
import com.kutv.music.core.model.RankingSummary
import com.kutv.music.core.model.ShareTarget
import com.kutv.music.core.model.SongDetail
import com.kutv.music.core.model.Track
import com.kutv.music.core.model.UserProfile
import com.kutv.music.web.ArtworkUrlPolicy
import org.json.JSONArray
import org.json.JSONObject

object ContentJsonMapper {
    fun page(page: PagedResult<ContentCard>): JSONObject = JSONObject()
        .put("items", JSONArray().apply { page.items.forEach { put(card(it)) } })
        .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
        .put("total", page.total ?: JSONObject.NULL)

    fun card(card: ContentCard): JSONObject = JSONObject()
        // Song cards carry the bare native track id and become opaque bridge ids;
        // prefixed ids (cloud:… and every non-song kind) stay stable for routing.
        .put("id", if (card.kind == ContentKind.SONG && ':' !in card.id) BridgeTrackIds.publicId(card.id) else card.id)
        .put("title", card.title)
        .put("kind", card.kind.name.lowercase())
        .put("subtitle", card.subtitle)
        .put("artwork", ArtworkUrlPolicy.normalize(card.artworkUrl) ?: "")
        .apply { card.badge?.let { put("badge", it) } }
        .apply { card.detailId?.let { put("detailId", it) } }

    fun detail(detail: ContentDetail): JSONObject = when (detail) {
        is PlaylistDetail -> JSONObject()
            .put("id", detail.id)
            .put("title", detail.title)
            .put("kind", "playlist")
            .put("artwork", ArtworkUrlPolicy.normalize(detail.artworkUrl) ?: "")
            .put("creator", detail.creator?.let(::profile))
            .put("playCount", detail.playCount)
            .put("tags", JSONArray(detail.tags))
            .put("introduction", detail.introduction)
            .put("trackCount", detail.trackCount ?: JSONObject.NULL)
            .put("tabs", JSONArray(detail.tabs))
            .put("tracks", trackPage(detail.tracks))
        is AlbumDetail -> JSONObject()
            .put("id", detail.id)
            .put("title", detail.title)
            .put("kind", "album")
            .put("artwork", ArtworkUrlPolicy.normalize(detail.artworkUrl) ?: "")
            .put("artist", detail.artist)
            .put("publishDate", detail.publishDate)
            .put("introduction", detail.introduction)
            .put("tabs", JSONArray(detail.tabs))
            .put("tracks", trackPage(detail.tracks))
        is ArtistDetail -> JSONObject()
            .put("id", detail.id)
            .put("title", detail.title)
            .put("kind", "artist")
            .put("artwork", ArtworkUrlPolicy.normalize(detail.artworkUrl) ?: "")
            .put("summary", detail.summary)
            .put("tabs", JSONArray(detail.tabs))
            .put("hotTracks", JSONArray().apply { detail.hotTracks.forEach { put(track(it)) } })
            .put("children", page(detail.children))
        is SongDetail -> JSONObject()
            .put("id", detail.id)
            .put("title", detail.title)
            .put("kind", "song")
            .put("artwork", ArtworkUrlPolicy.normalize(detail.artworkUrl) ?: "")
            .put("tabs", JSONArray(detail.tabs))
            .put("track", detail.track?.let(::track))
            .put("related", page(detail.related))
        is MvDetail -> JSONObject()
            .put("id", detail.id)
            .put("title", detail.title)
            .put("kind", "mv")
            .put("artwork", ArtworkUrlPolicy.normalize(detail.artworkUrl) ?: "")
            .put("artist", detail.artist)
            .put("durationMs", detail.durationMs)
            .put("playCount", detail.playCount)
            .put("introduction", detail.introduction)
            .put("tabs", JSONArray(detail.tabs))
            .put("related", page(detail.related))
    }

    fun comment(comment: Comment): JSONObject = JSONObject()
        .put("id", comment.id)
        .put("user", profile(comment.user))
        .put("content", comment.content)
        .put("time", comment.timeEpochMs)
        .put("likeCount", comment.likeCount)
        .put("replies", comment.replies?.let(::commentPage) ?: JSONObject.NULL)

    fun commentPage(page: PagedResult<Comment>): JSONObject = JSONObject()
        .put("items", JSONArray().apply { page.items.forEach { put(comment(it)) } })
        .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
        .put("total", page.total ?: JSONObject.NULL)

    fun librarySummary(summary: LibrarySummary): JSONObject = JSONObject()
        .put(
            "counts",
            JSONObject().apply {
                summary.counts.forEach { (section, count) -> put(sectionKey(section), count) }
            },
        )
        .put("featured", JSONArray().apply { summary.featured.forEach { put(card(it)) } })

    fun ranking(ranking: RankingSummary): JSONObject = JSONObject()
        .put("id", ranking.id)
        .put("title", ranking.title)
        .put("artwork", ArtworkUrlPolicy.normalize(ranking.artworkUrl) ?: "")

    fun shareTarget(target: ShareTarget): JSONObject = JSONObject()
        .put("kind", target.kind.name.lowercase())
        .put("id", target.id)

    fun favoriteState(target: MutationTarget, favorite: Boolean): JSONObject = JSONObject()
        .put("kind", target.kind.name.lowercase())
        .put("id", target.id)
        .put("favorite", favorite)

    fun profile(profile: UserProfile): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("nickname", profile.nickname)
        .put("avatar", ArtworkUrlPolicy.normalize(profile.avatarUrl) ?: "")
        .put("summary", profile.summary)

    fun trackPage(page: PagedResult<Track>): JSONObject = JSONObject()
        .put("items", JSONArray().apply { page.items.forEach { put(track(it)) } })
        .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
        .put("total", page.total ?: JSONObject.NULL)

    fun track(track: Track): JSONObject = JSONObject()
        .put("id", BridgeTrackIds.publicId(track.id))
        .put("title", track.title)
        .put("artist", track.artists.joinToString(" / "))
        .put("album", track.album)
        .put("artwork", ArtworkUrlPolicy.normalize(track.artworkUri) ?: "")
        .put("durationMs", track.durationMs)
        .put("qualities", JSONArray(track.qualities))
        .apply { track.mvId?.let { put("mvId", it) } }

    fun sectionKey(section: LibrarySection): String = when (section) {
        LibrarySection.FAVORITE_SONGS -> "favoriteSongs"
        LibrarySection.FAVORITE_ARTISTS -> "favoriteArtists"
        LibrarySection.FAVORITE_USERS -> "favoriteUsers"
        LibrarySection.FAVORITE_ALBUMS -> "favoriteAlbums"
        LibrarySection.FAVORITE_MVS -> "favoriteMvs"
        LibrarySection.HISTORY -> "history"
        LibrarySection.CLOUD -> "cloud"
        LibrarySection.PURCHASED_SONGS -> "purchasedSongs"
        LibrarySection.PURCHASED_ALBUMS -> "purchasedAlbums"
        LibrarySection.CREATED_PLAYLISTS -> "createdPlaylists"
        LibrarySection.FAVORITE_PLAYLISTS -> "favoritePlaylists"
    }

    fun commentTarget(target: CommentTarget): JSONObject = JSONObject()
        .put("kind", target.kind.name.lowercase())
        .put("id", target.id)
}
