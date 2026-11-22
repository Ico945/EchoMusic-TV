package com.kutv.music.data.content

import com.kutv.music.core.model.Comment
import com.kutv.music.core.model.CommentTarget
import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentDetail
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.PagedResult
import com.kutv.music.core.model.Track
import com.kutv.music.direct.content.DetailMapper
import com.kutv.music.direct.content.KugouCommentApi
import com.kutv.music.direct.content.KugouDetailApi
import org.json.JSONObject

/**
 * Detail/comment domain facade. Every method is a [Result]-wrapped suspend call
 * that strips the kind prefix, dispatches to the direct API, and registers any
 * produced [Track]s through [rememberTracks] before returning.
 */
class KugouDetailDomain(
    private val api: KugouDetailApi,
    private val commentApi: KugouCommentApi,
    private val mapper: DetailMapper = DetailMapper,
    private val rememberTracks: (List<Track>) -> List<Track>,
    private val trackLookup: (String) -> Track? = { null },
) {
    suspend fun detail(kind: ContentKind, id: String): Result<ContentDetail> = runCatching {
        val bare = stripPrefix(kind, id).takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("blank id for $kind")
        when (kind) {
            ContentKind.PLAYLIST -> {
                val meta = api.playlistMeta(bare)
                val tracks = api.playlistTracks(bare, 1)
                mapper.playlist(bare, meta, tracks).also {
                    rememberTracks(it.tracks.items)
                }
            }
            ContentKind.ALBUM -> mapper.album(bare, api.albumMeta(bare))
            ContentKind.ARTIST -> mapper.artist(bare, api.artistMeta(bare))
            ContentKind.SONG -> {
                val detail = mapper.song(bare, api.songMeta(bare), JSONObject())
                // The numeric metadata endpoint supplies base information, not a hash.
                // Reuse only this exact previously registered source; never invent one.
                val track = detail.track ?: trackLookup(bare)?.takeIf { it.id == bare && detail.title.isNotBlank() }
                    ?.let { it.copy(title = detail.title, artworkUri = detail.artworkUrl.ifBlank { it.artworkUri }) }
                detail.copy(track = track, artworkUrl = detail.artworkUrl.ifBlank { track?.artworkUri.orEmpty() })
                    .also { track?.let { rememberTracks(listOf(it)) } }
            }
            ContentKind.MV -> mapper.mv(bare, api.mvMeta(bare), JSONObject())
            ContentKind.USER -> throw IllegalArgumentException("unsupported detail kind $kind")
        }
    }

    suspend fun playlistTracks(id: String, cursor: String?): Result<PagedResult<Track>> = runCatching {
        val page = parsePage(cursor)
        val result = mapper.trackPage(api.playlistTracks(id, page), page)
        rememberTracks(result.items)
        result
    }

    suspend fun albumTracks(id: String, cursor: String?): Result<PagedResult<Track>> = runCatching {
        val page = parsePage(cursor)
        val result = mapper.trackPage(api.albumTracks(id, page), page)
        rememberTracks(result.items)
        result
    }

    suspend fun artistTracks(id: String, cursor: String?): Result<PagedResult<Track>> = runCatching {
        val page = parsePage(cursor)
        val bare = stripPrefix(ContentKind.ARTIST, id).takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("blank artist id")
        val result = mapper.trackPage(api.artistSongs(bare, page), page)
        check(page != 1 || result.items.isNotEmpty() || (result.total ?: 0) == 0) {
            "Artist song response has no playable tracks"
        }
        rememberTracks(result.items)
        result
    }

    suspend fun artistChildren(id: String, tab: String, cursor: String?): Result<PagedResult<ContentCard>> =
        runCatching {
            val page = parsePage(cursor)
            val bare = stripPrefix(ContentKind.ARTIST, id).takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("blank artist id")
            val result = when (tab) {
                "songs" -> mapper.cardPage(api.artistSongs(bare, page), ContentKind.SONG, "", page)
                "albums" -> mapper.cardPage(api.artistAlbums(bare, page), ContentKind.ALBUM, "album:", page)
                "mv" -> mapper.cardPage(api.artistVideos(bare, page), ContentKind.MV, "mv:", page)
                else -> throw IllegalArgumentException("unsupported artist tab $tab")
            }
            check(page != 1 || result.items.isNotEmpty() || (result.total ?: 0) == 0) {
                "Artist child response has no usable items"
            }
            result
        }

    suspend fun comments(target: CommentTarget, cursor: String?): Result<PagedResult<Comment>> = runCatching {
        if (target.id.isBlank()) throw IllegalArgumentException("blank comment target id")
        mapper.commentPage(commentApi.comments(target, parsePage(cursor)))
    }

    private fun parsePage(cursor: String?): Int = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    private fun stripPrefix(kind: ContentKind, id: String): String {
        val prefix = when (kind) {
            ContentKind.SONG -> "song:"
            ContentKind.MV -> "mv:"
            ContentKind.PLAYLIST -> "playlist:"
            ContentKind.ALBUM -> "album:"
            ContentKind.ARTIST -> "artist:"
            ContentKind.USER -> "user:"
        }
        return if (id.startsWith(prefix)) id.removePrefix(prefix) else id
    }
}
