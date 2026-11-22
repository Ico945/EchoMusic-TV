package com.kutv.music

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.kutv.music.core.model.ContentKind
import com.kutv.music.direct.KugouCrypto
import com.kutv.music.direct.content.BrowseMapper
import com.kutv.music.direct.content.KugouBrowseApi
import com.kutv.music.direct.content.KugouDetailApi
import com.kutv.music.direct.content.DetailMapper
import com.kutv.music.direct.KugouTrackMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in provider diagnosis. Only registers public track metadata in memory; never changes auth or queue.
 * Never emits provider values, identifiers or URLs. */
class KugouBrowseShapeDiagnosticTest {
    @Test fun describeFirstPublicPlaylistAndAlbumDetails() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvDetailShapeDiagnosis") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AppGraph.initialize(instrumentation.targetContext)
        val mid = { KugouCrypto.deriveMid(AppGraph.deviceStore.getOrCreate().mid) }
        val browseApi = KugouBrowseApi(AppGraph.httpClient, mid)
        val detailApi = KugouDetailApi(
            executor = AppGraph.httpClient,
            credentialsProvider = AppGraph.credentialStore::loadCredentials,
            midProvider = mid,
        )
        val cases: List<Triple<String, ContentKind, suspend () -> Pair<String, JSONObject>>> = listOf(
            Triple("playlist", ContentKind.PLAYLIST) {
                val browse = browseApi.newPlaylists(1)
                BrowseMapper.cardPage(browse, ContentKind.PLAYLIST, "").items.first().id to browse
            },
            Triple("album", ContentKind.ALBUM) {
                val browse = browseApi.newAlbums(1)
                BrowseMapper.cardPage(browse, ContentKind.ALBUM, "").items.first().id to browse
            },
        )
        for ((label, kind, call) in cases) {
            val summary = JSONObject().put("kind", label)
            try {
                val browseResult = withTimeoutOrNull(15_000L) { call() }
                summary.put("browseOk", browseResult != null)
                if (browseResult != null) {
                    val (id, browse) = browseResult
                    summary.put("browseShape", shape(browse, 0))
                    val meta = runCatching {
                        when (kind) {
                            ContentKind.PLAYLIST -> detailApi.playlistMeta(id)
                            ContentKind.ALBUM -> detailApi.albumMeta(id)
                            else -> error("unsupported diagnosis kind")
                        }
                    }.getOrNull()
                    summary.put("metaOk", meta != null)
                    val tracks = runCatching {
                        when (kind) {
                            ContentKind.PLAYLIST -> detailApi.playlistTracks(id, 1)
                            ContentKind.ALBUM -> detailApi.albumTracks(id, 1)
                            else -> error("unsupported diagnosis kind")
                        }
                    }.getOrNull()
                    summary.put("tracksOk", tracks != null)
                    if (meta != null) summary.put("metaShape", shape(meta, 0))
                    if (tracks != null) {
                    val page = DetailMapper.trackPage(tracks)
                    summary.put("trackShape", shape(tracks, 0))
                        .put("trackCount", page.items.size).put("trackTotalKnown", page.total != null)
                    }
                    if (meta != null && tracks != null) {
                    val detail = when (kind) {
                        ContentKind.PLAYLIST -> DetailMapper.playlist("fixture", meta, tracks)
                        ContentKind.ALBUM -> DetailMapper.album("fixture", meta)
                        else -> error("unsupported diagnosis kind")
                    }
                    summary.put("mappedTitle", detail.title.isNotBlank()).put("mappedArtwork", detail.artworkUrl.isNotBlank())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { summary.put("requestOk", false) }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "DETAIL_SHAPE $summary\n") })
        }
    }

    @Test fun describeFirstPublicSongDetail() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvSongShapeDiagnosis") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AppGraph.initialize(instrumentation.targetContext)
        val mid = { KugouCrypto.deriveMid(AppGraph.deviceStore.getOrCreate().mid) }
        val summary = JSONObject()
        try {
            val completed = withTimeoutOrNull(20_000L) {
                val track = KugouTrackMapper.extractTracks(KugouBrowseApi(AppGraph.httpClient, mid).newSongs(1)).firstOrNull()
                summary.put("sourceTrackFound", track != null)
                if (track != null) {
                    val body = KugouDetailApi(executor = AppGraph.httpClient, midProvider = mid).songMeta(track.id)
                    val mapped = DetailMapper.song(track.id, body, JSONObject())
                    summary.put("requestOk", true).put("shape", shape(body, 0))
                        .put("mappedTrack", mapped.track != null).put("nonblankTitle", mapped.title.isNotBlank())
                        .put("sameTrackId", mapped.track?.id == track.id)
                    AppGraph.directApi.rememberTracks(listOf(track))
                    val domain = com.kutv.music.data.content.KugouDetailDomain(
                        KugouDetailApi(executor = AppGraph.httpClient, midProvider = mid), com.kutv.music.direct.content.KugouCommentApi(AppGraph.httpClient),
                        rememberTracks = AppGraph.directApi::rememberTracks, trackLookup = AppGraph.directApi::track)
                    val detail = domain.detail(ContentKind.SONG, track.id).getOrThrow() as com.kutv.music.core.model.SongDetail
                    summary.put("domainTrack", detail.track != null).put("domainSameId", detail.track?.id == track.id)
                        .put("domainSameSource", detail.track?.source == track.source)
                }
                true
            }
            summary.put("withinDeadline", completed == true)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { summary.put("requestOk", false) }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SONG_SHAPE $summary\n") })
    }

    @Test fun describeKnownBrowseContainers() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kutvBrowseShapeDiagnosis") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AppGraph.initialize(instrumentation.targetContext)
        val api = KugouBrowseApi(AppGraph.httpClient) { KugouCrypto.deriveMid(AppGraph.deviceStore.getOrCreate().mid) }
        val calls: List<Pair<String, suspend () -> JSONObject>> = listOf(
            "newSongs" to { api.newSongs(1) }, "newAlbums" to { api.newAlbums(1) },
            "newPlaylists" to { api.newPlaylists(1) }, "rankings" to { api.rankings() },
        )
        for ((section, call) in calls) {
            val result = try {
                withTimeoutOrNull(12_000L) { Result.success(call()) } ?: Result.failure(Exception("deadline"))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { Result.failure(error) }
            val body = result.getOrNull()
            val summary = JSONObject().put("section", section).put("requestOk", body != null)
            if (body != null) {
                summary.put("status", body.optInt("status", -1)).put("errorCode", body.optInt("error_code", -1))
                    .put("shape", shape(body, 0))
                val kind = when (section) {
                    "newSongs" -> ContentKind.SONG
                    "newAlbums" -> ContentKind.ALBUM
                    else -> ContentKind.PLAYLIST
                }
                summary.put("mappedCount", if (section == "rankings") BrowseMapper.rankings(body).size
                    else BrowseMapper.cardPage(body, kind, "").items.size)
                if (section == "newSongs") {
                    val tracks = BrowseMapper.cardsFromTracks(body)
                    summary.put("trackCount", tracks.size).put("tracksWithDuration", tracks.count { it.durationMs > 0 })
                    val first = body.optJSONArray("data")?.optJSONObject(0)
                    summary.put("firstDurationMatchesTimelength", first != null && first.optLong("timelength") > 0 &&
                        tracks.firstOrNull()?.durationMs == first.optLong("timelength"))
                }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "BROWSE_SHAPE $summary\n") })
        }
    }

    private fun shape(value: Any?, depth: Int): Any = when {
        depth > 5 -> "depth-limit"
        value is JSONObject -> JSONObject().apply {
            // Fixed schema names only: dynamic object keys could contain account IDs.
            val keys = listOf("data", "info", "songs", "song_list", "songlist", "list", "lists", "items",
                "special_list", "chn", "eur", "jpn", "kor", "1", "2", "3", "4", "audio_info", "base",
                "hash", "album_audio_id", "mixsongid", "song_id", "songname", "filename", "name", "title",
                "album_id", "album_name", "specialid", "special_id", "listid", "global_collection_id", "gid",
                "list_create_gid", "singername", "author_name", "userinfo", "song_count", "music_count", "total",
                "flexible_cover", "sizable_cover", "imgurl", "image_url",
                "sizable_cover", "img", "trans_param", "union_cover", "audio_name", "entity_id", "authors",
                "duration", "duration_128", "timelength", "time_length", "album_info", "hash_128", "files", "file")
            keys.filter(value::has).forEach { put(it, shape(value.opt(it), depth + 1)) }
            put("fieldCount", value.length())
        }
        value is JSONArray -> JSONObject().put("length", value.length()).put("first", shape(value.opt(0), depth + 1))
        value is Number -> "number"
        value is String -> "string"
        value is Boolean -> "boolean"
        else -> "null"
    }
}
