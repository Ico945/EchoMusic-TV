package com.kutv.music.data.content

import com.kutv.music.core.model.AlbumDetail
import com.kutv.music.core.model.ArtistDetail
import com.kutv.music.core.model.CommentTarget
import com.kutv.music.core.model.CommentTargetKind
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.MvDetail
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.PlaylistDetail
import com.kutv.music.core.model.SongDetail
import com.kutv.music.core.model.Track
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouResponse
import com.kutv.music.direct.content.KugouCommentApi
import com.kutv.music.direct.content.KugouDetailApi
import com.kutv.music.direct.content.KugouDetailFixtures
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDetailDomainTest {
    @Test fun `current public artist array schema maps playable fields and root pagination`() = runBlocking {
        // Sanitized field shape verified against the anonymous public endpoint.
        val body = JSONObject("""{"status":1,"error_code":0,"total":65,"data":[{"album_audio_id":76201,"audio_name":"Fixture song","author_name":"Fixture artist","hash":"fixture-hash","timelength":180000,"album_name":"Fixture album"}]}""")
        val executor = KugouRequestExecutor { KugouResponse(200, body) }
        val saved = mutableListOf<Track>()
        val domain = KugouDetailDomain(KugouDetailApi(executor) { "fixture" }, KugouCommentApi(executor), rememberTracks = { saved.addAll(it); it })
        val page = domain.artistTracks("9", "2").getOrThrow()
        assertEquals("3", page.nextCursor); assertEquals(65, page.total)
        assertEquals("Fixture song", saved.single().title)
        assertEquals(listOf("Fixture artist"), saved.single().artists)
        assertEquals(180000L, saved.single().durationMs)
        assertEquals("fixture-hash", (saved.single().source as PlaybackSource.Kugou).hash)
    }
    @Test fun `artist songs are playable registered tracks with cursor and stripped id`() = runBlocking {
        val first = domain.artistTracks("artist:9", null).getOrThrow()
        assertEquals("2", first.nextCursor)
        assertEquals(45, first.total)
        assertEquals(first.items, remembered)
        assertTrue(first.items.single().source is PlaybackSource.Kugou)
        assertEquals("9", (requests.last().body as JSONObject).getString("author_id"))
        domain.artistTracks("9", "2").getOrThrow()
        assertEquals(2, (requests.last().body as JSONObject).getInt("page"))
    }

    @Test fun `artist missing hashes with a positive total is retryable failure not an empty library`() = runBlocking {
        val executor = KugouRequestExecutor { KugouResponse(200, JSONObject("""{"data":{"total":2,"audios":[{"songname":"metadata only"}]}}""")) }
        val domain = KugouDetailDomain(KugouDetailApi(executor) { "fixture" }, KugouCommentApi(executor), rememberTracks = { error("must not register") })
        assertTrue(domain.artistTracks("9", null).isFailure)
    }
    @Test fun `base only metadata retains the exact registered playback source`() = runBlocking {
        val existing = com.kutv.music.direct.KugouTrackMapper.extractTracks(
            JSONObject("""{"data":[{"album_audio_id":76201,"hash":"fixture-hash","songname":"Old title","singername":"Artist","duration":210}]}""")) .single()
        val api = KugouRequestExecutor { KugouResponse(200, JSONObject("""{"data":[{"base":{"album_audio_id":76201,"songname":"Updated title"}}]}""")) }
        val saved = mutableListOf<Track>()
        val domain = KugouDetailDomain(KugouDetailApi(api) { "fixture" }, KugouCommentApi(api),
            rememberTracks = { saved.addAll(it); it }, trackLookup = { if (it == "76201") existing else null })
        val detail = domain.detail(ContentKind.SONG, "song:76201").getOrThrow() as SongDetail
        assertEquals("Updated title", detail.track?.title)
        assertEquals(existing.source, detail.track?.source)
        assertEquals(existing.durationMs, detail.track?.durationMs)
        assertEquals(listOf(detail.track), saved)
    }

    @Test fun `metadata without a known source stays unplayable and does not register a fabricated track`() = runBlocking {
        val api = KugouRequestExecutor { KugouResponse(200, JSONObject("""{"data":[{"base":{"album_audio_id":76201,"songname":"Metadata only"}}]}""")) }
        val domain = KugouDetailDomain(KugouDetailApi(api) { "fixture" }, KugouCommentApi(api), rememberTracks = { error("must not register") })
        val detail = domain.detail(ContentKind.SONG, "76201").getOrThrow() as SongDetail
        assertEquals("Metadata only", detail.title)
        org.junit.Assert.assertNull(detail.track)
    }

    @Test fun `a different song response is not combined with the requested source`() = runBlocking {
        val api = KugouRequestExecutor { KugouResponse(200, JSONObject("""{"data":[{"base":{"album_audio_id":76202,"songname":"Different song"}}]}""")) }
        val domain = KugouDetailDomain(KugouDetailApi(api) { "fixture" }, KugouCommentApi(api), rememberTracks = { error("must not register") })
        assertTrue(domain.detail(ContentKind.SONG, "76201").isFailure)
    }

    @Test fun `root artist album and video pages propagate requested cursor and canonical artist id`() = runBlocking {
        val calls = mutableListOf<KugouRequest>()
        val executor = KugouRequestExecutor { request ->
            calls += request
            KugouResponse(200, JSONObject("""{"total":65,"data":[{"video_id":5001,"album_id":3001,"album_audio_id":76201,"video_name":"Video","album_name":"Album"}]}"""))
        }
        val domain = KugouDetailDomain(KugouDetailApi(executor) { "fixture" }, KugouCommentApi(executor), rememberTracks = { it })
        assertEquals("album:3001", domain.artistChildren("artist:9", "albums", "2").getOrThrow().items.single().id)
        assertEquals("9", (calls.last().body as JSONObject).getString("author_id"))
        assertEquals(2, (calls.last().body as JSONObject).getInt("page"))
        val videos = domain.artistChildren("artist:9", "mv", "2").getOrThrow()
        assertEquals("mv:5001", videos.items.single().id)
        assertEquals("3", videos.nextCursor)
        assertEquals("9", calls.last().query["author_id"])
        assertTrue(domain.artistChildren("artist:", "mv", null).isFailure)
    }

    @Test fun `positive total but unmappable artist cards fail instead of pretending the library is empty`() = runBlocking {
        val executor = KugouRequestExecutor { KugouResponse(200, JSONObject("""{"total":2,"data":[{"album_audio_id":76201,"name":"Not a video id"}]}""")) }
        val domain = KugouDetailDomain(KugouDetailApi(executor) { "fixture" }, KugouCommentApi(executor), rememberTracks = { it })
        assertTrue(domain.artistChildren("9", "mv", null).isFailure)
    }

    private val requests = mutableListOf<KugouRequest>()
    private val remembered = mutableListOf<Track>()

    private val executor = KugouRequestExecutor { request ->
        requests += request
        KugouResponse(statusCode = 200, body = bodyFor(request.path))
    }

    private val domain = KugouDetailDomain(
        api = KugouDetailApi(executor) { "9876543210" },
        commentApi = KugouCommentApi(executor),
        rememberTracks = { tracks -> remembered += tracks; tracks },
    )

    @Test
    fun `playlist detail fetches meta and tracks and registers them`() = runBlocking {
        val detail = domain.detail(ContentKind.PLAYLIST, "playlist:9001").getOrThrow() as PlaylistDetail
        assertEquals("夜晚歌单", detail.title)
        assertEquals(1, detail.tracks.items.size)
        assertEquals(1, remembered.size)
        assertEquals("/v3/get_list_info", requests[0].path)
        assertEquals("/pubsongs/v2/get_other_list_file_nofilt", requests[1].path)
    }

    @Test
    fun `song detail numeric id routes to kmr audio and registers track`() = runBlocking {
        val detail = domain.detail(ContentKind.SONG, "song:76201").getOrThrow() as SongDetail
        assertEquals("76201", detail.track?.id)
        assertEquals(1, remembered.size)
        assertEquals("/kmr/v2/audio", requests.last().path)
    }

    @Test
    fun `song detail hash id routes to kmr audio hash endpoint`() = runBlocking {
        val detail = domain.detail(ContentKind.SONG, "song:f0e0f1e2d3c4b5a69788796a5b4c3d2e").getOrThrow() as SongDetail
        assertEquals("/v1/audio/audio", requests.last().path)
        val source = detail.track?.source as PlaybackSource.Kugou
        assertEquals("f0e0f1e2d3c4b5a69788796a5b4c3d2e", source.hash)
    }

    @Test
    fun `album and artist and mv details dispatch per kind`() = runBlocking {
        val album = domain.detail(ContentKind.ALBUM, "album:3001").getOrThrow() as AlbumDetail
        assertEquals("山野", album.title)
        val artist = domain.detail(ContentKind.ARTIST, "artist:9").getOrThrow() as ArtistDetail
        assertEquals("刘瑾睿", artist.title)
        val mv = domain.detail(ContentKind.MV, "mv:5001").getOrThrow() as MvDetail
        assertEquals("若把你 MV", mv.title)
    }

    @Test
    fun `playlist tracks paginate via cursor and register`() = runBlocking {
        val page = domain.playlistTracks("9001", "2").getOrThrow()
        assertEquals(1, page.items.size)
        assertEquals(1, remembered.size)
        assertEquals(30, requests.last().query["begin_idx"])
    }

    @Test
    fun `album tracks register too`() = runBlocking {
        val page = domain.albumTracks("3001", null).getOrThrow()
        assertEquals(1, page.items.size)
        assertEquals(1, remembered.size)
    }

    @Test
    fun `artist children dispatch songs albums mv`() = runBlocking {
        val songs = domain.artistChildren("9", "songs", null).getOrThrow()
        assertEquals(ContentKind.SONG, songs.items.first().kind)
        assertEquals("/kmr/v1/audio_group/author", requests.last().path)

        val albums = domain.artistChildren("9", "albums", null).getOrThrow()
        assertEquals(ContentKind.ALBUM, albums.items.first().kind)
        assertEquals("/kmr/v1/author/albums", requests.last().path)

        val mv = domain.artistChildren("9", "mv", null).getOrThrow()
        assertEquals(ContentKind.MV, mv.items.first().kind)
        assertEquals("/kmr/v1/author/videos", requests.last().path)
    }

    @Test
    fun `artist children rejects unknown tab`() = runBlocking {
        assertTrue(domain.artistChildren("9", "bogus", null).isFailure)
    }

    @Test
    fun `comments dispatch per target`() = runBlocking {
        val song = domain.comments(CommentTarget(CommentTargetKind.SONG, "76201"), null).getOrThrow()
        assertEquals("c1", song.items.first().id)
        val playlist = domain.comments(CommentTarget(CommentTargetKind.PLAYLIST, "9001"), null).getOrThrow()
        assertEquals("c1", playlist.items.first().id)
        val album = domain.comments(CommentTarget(CommentTargetKind.ALBUM, "3001"), null).getOrThrow()
        assertEquals("c1", album.items.first().id)
    }

    @Test
    fun `comments reject blank target id`() = runBlocking {
        assertTrue(domain.comments(CommentTarget(CommentTargetKind.SONG, ""), null).isFailure)
    }

    @Test
    fun `blank id fails fast`() = runBlocking {
        assertTrue(domain.detail(ContentKind.SONG, "").isFailure)
        assertTrue(domain.detail(ContentKind.SONG, "song:").isFailure)
    }

    @Test
    fun `unsupported kind fails`() = runBlocking {
        assertTrue(domain.detail(ContentKind.USER, "user:1").isFailure)
    }

    private fun bodyFor(path: String): JSONObject = when (path) {
        "/v3/get_list_info" -> KugouDetailFixtures.get("playlistMeta")
        "/pubsongs/v2/get_other_list_file_nofilt" -> KugouDetailFixtures.get("playlistTracks")
        "/kmr/v2/albums" -> KugouDetailFixtures.get("albumMeta")
        "/v1/album_audio/lite" -> KugouDetailFixtures.get("albumTracks")
        "/kmr/v3/author" -> KugouDetailFixtures.get("artistMeta")
        "/kmr/v1/audio_group/author" -> KugouDetailFixtures.get("artistSongs")
        "/kmr/v1/author/albums" -> KugouDetailFixtures.get("artistAlbums")
        "/kmr/v1/author/videos" -> KugouDetailFixtures.get("artistVideos")
        "/kmr/v2/audio" -> KugouDetailFixtures.get("songMetaKrm")
        "/v1/audio/audio" -> KugouDetailFixtures.get("songMetaHash")
        "/v1/video" -> KugouDetailFixtures.get("mvMeta")
        "/mcomment/v1/cmtlist" -> KugouDetailFixtures.get("commentsSong")
        "/m.comment.service/v1/cmtlist" -> KugouDetailFixtures.get("commentsPlaylist")
        else -> JSONObject("""{"status":1,"error_code":0,"data":{}}""")
    }
}
