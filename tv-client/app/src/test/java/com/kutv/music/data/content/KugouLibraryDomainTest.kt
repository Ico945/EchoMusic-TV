package com.kutv.music.data.content

import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.LibrarySection
import com.kutv.music.core.model.MutationTarget
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.PlaylistMutation
import com.kutv.music.core.model.Track
import com.kutv.music.application.content.SharePresentationUseCase
import com.kutv.music.application.auth.QrImageRenderer
import com.kutv.music.data.DirectLoginRequiredException
import com.kutv.music.direct.KugouCredentials
import com.kutv.music.direct.KugouDeviceIdentity
import com.kutv.music.direct.KugouRawRequestExecutor
import com.kutv.music.direct.KugouRawResponse
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouResponse
import com.kutv.music.direct.content.KugouLibraryApi
import com.kutv.music.direct.content.LibraryMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouLibraryDomainTest {
    @Test fun `all playlists bypasses ownership filtering and exposes only sync counts`() = runBlocking {
        val raw = JSONObject("""{"status":1,"data":{"info":[
            {"listid":1,"name":"Owned","list_create_userid":42},
            {"listid":2,"name":"Collected","list_create_userid":99},
            {"listid":3,"name":"Missing creator"},
            {"listid":4,"name":"Album","source":2}
        ]}}""")
        val domain = stack(playlistResponse = { raw }).domain
        val page = domain.myPlaylists(null).getOrThrow()
        assertEquals(listOf("1", "2", "3"), page.items.map { it.id })
        assertEquals(4, page.librarySync!!.received)
        assertEquals(1, page.librarySync!!.created)
        assertEquals(2, page.librarySync!!.collected)
        assertEquals(1, page.librarySync!!.albums)
        assertEquals(1, page.librarySync!!.missingCreator)
        assertTrue(stack(credentials = null).domain.myPlaylists(null).exceptionOrNull() is DirectLoginRequiredException)
    }

    @Test fun `liked reads and selected card use gid while mutation identity stays listid`() = runBlocking {
        val raw = JSONObject("""{"data":{"info":[{"listid":300,"name":"我喜欢的音乐","list_create_gid":"gidXYZ","global_collection_id":"otherGid","type":1}]}}""")
        val stack = stack(playlistResponse = { raw })
        assertEquals(2, stack.domain.librarySection(LibrarySection.FAVORITE_SONGS, null).getOrThrow().items.size)
        assertEquals(null, stack.json.callCounts["/v4/get_list_all_file"])
        assertEquals(1, stack.json.callCounts["/pubsongs/v2/get_other_list_file_nofilt"])
        val card = LibraryMapper.playlistCards(raw).single()
        assertEquals("300", card.id)
        assertEquals("playlist:library_gid_gidXYZ", card.detailId)
        assertEquals(2, stack.domain.personalPlaylistTracks(card.detailId!!, null).getOrThrow().items.size)
        assertEquals(2, stack.json.callCounts["/pubsongs/v2/get_other_list_file_nofilt"])
        assertEquals(null, stack.json.callCounts["/v4/get_list_all_file"])
    }
    @Test fun `liked playlist is found after the first mixed page`() = runBlocking {
        val stack = stack(playlistResponse = { request ->
            val page = (request.body as JSONObject).getInt("page")
            JSONObject().put("data", JSONObject().put("info", JSONArray().apply {
                if (page == 1) repeat(30) { put(JSONObject().put("listid", it + 1).put("name", "Created $it").put("type", 0)) }
                else put(JSONObject().put("listid", 300).put("name", "我喜欢的音乐").put("type", 1))
            }))
        })
        assertEquals(2, stack.domain.librarySection(LibrarySection.FAVORITE_SONGS, null).getOrThrow().items.size)
        assertEquals(2, stack.json.callCounts["/v7/get_all_list"])
    }

    @Test fun `unrecognized nonempty library is an actionable failure not zero likes`() = runBlocking {
        val domain = stack(playlistResponse = { JSONObject("""{"data":{"info":[{"listid":1,"name":"Other","type":0}]}}""") }).domain
        assertTrue(domain.librarySection(LibrarySection.FAVORITE_SONGS, null).exceptionOrNull() is LikedPlaylistNotFoundException)
    }

    @Test fun `nonempty unplayable personal response is not reported as an empty playlist`() = runBlocking {
        val broken = JSONObject("""{"data":{"info":[{"album_audio_id":9,"songname":"Unexpected schema"}]}}""")
        val api = KugouLibraryApi(
            KugouRequestExecutor { KugouResponse(200, broken) }, RouterRawExecutor(fixtures),
            { KugouCredentials("42", "synthetic-token") }, { device },
        )
        val domain = KugouLibraryDomain(api, rememberTracks = { it }, trackLookup = { null },
            sharePresentation = SharePresentationUseCase(QrImageRenderer { "data:image/png;base64,TEST" }))
        assertTrue(domain.personalPlaylistTracks("library_300", null).isFailure)
    }

    @Test fun `renamed default and alternate playlist fields match Echo`() = runBlocking {
        val raw = JSONObject("""{"data":{"info":[{"specialid":300,"specialname":"Renamed","type":1,"imgurl":"https://img.kugou.com/{size}/cover.jpg"}]}}""")
        assertEquals("300", LibraryMapper.favoritePlaylistId(raw))
        assertEquals("Renamed", LibraryMapper.playlistCards(raw).single().title)
        assertTrue(LibraryMapper.playlistCards(raw).single().artworkUrl.contains("/400/"))
        assertEquals(2, stack(playlistResponse = { raw }).domain.librarySection(LibrarySection.FAVORITE_SONGS, null).getOrThrow().items.size)
    }

    @Test fun `repeating upstream pages fail without scanning forever`() = runBlocking {
        val stack = stack(playlistResponse = { JSONObject().put("info", JSONArray().apply {
            repeat(30) { put(JSONObject().put("listid", it + 1).put("name", "Created $it").put("type", 0)) }
        }) })
        assertTrue(stack.domain.librarySection(LibrarySection.FAVORITE_SONGS, null).isFailure)
        assertEquals(2, stack.json.callCounts["/v7/get_all_list"])
    }
    private val device = KugouDeviceIdentity(
        guid = "a".repeat(32),
        mid = "b".repeat(32),
        uuid = "uuid",
        dfid = "dfid",
    )
    private val fixtures = JSONObject(
        checkNotNull(javaClass.getResource("/kugou/library-fixtures.json")).readText(),
    )

    private class RouterExecutor(
        private val fixtures: JSONObject,
        private val playlistResponse: ((KugouRequest) -> JSONObject)? = null,
        private val tracksResponse: ((KugouRequest) -> JSONObject)? = null,
    ) : KugouRequestExecutor {
        val callCounts = mutableMapOf<String, Int>()
        val requests = mutableListOf<KugouRequest>()

        override suspend fun execute(request: KugouRequest): KugouResponse {
            requests += request
            callCounts[request.path] = (callCounts[request.path] ?: 0) + 1
            val body = when (request.path) {
                "/v7/get_all_list" -> playlistResponse?.invoke(request) ?: fixtures.getJSONObject("user_playlist")
                "/v4/get_list_all_file", "/pubsongs/v2/get_other_list_file_nofilt" -> tracksResponse?.invoke(request) ?: fixtures.getJSONObject("playlist_tracks")
                "/playhistory/v1/get_songs" -> fixtures.getJSONObject("history")
                "/openapi/copyright/v1/audio/get_goods" -> fixtures.getJSONObject("purchased_songs")
                "/openapi/v1/copyright/get_album_goods" -> fixtures.getJSONObject("purchased_albums")
                "/v4/follow_list" -> fixtures.getJSONObject("follow_artists")
                "/collectservice/v2/collect_list_mixvideo" -> fixtures.getJSONObject("collect_videos")
                "/v3/get_my_info" -> fixtures.getJSONObject("profile")
                else -> JSONObject("""{"status":1,"error_code":0,"data":{}}""")
            }
            return KugouResponse(200, body)
        }
    }

    private class RouterRawExecutor(
        private val fixtures: JSONObject,
    ) : KugouRawRequestExecutor {
        val callCounts = mutableMapOf<String, Int>()

        override suspend fun executeRaw(request: KugouRequest): KugouRawResponse {
            callCounts[request.path] = (callCounts[request.path] ?: 0) + 1
            val body = if (request.path == "/v1/get_list") {
                fixtures.getJSONObject("cloud_list")
            } else {
                JSONObject("""{"status":1,"error_code":0,"data":{}}""")
            }
            val text = body.toString()
            return KugouRawResponse(200, text, bodyBytes = text.toByteArray())
        }
    }

    private data class Stack(
        val domain: KugouLibraryDomain,
        val json: RouterExecutor,
        val raw: RouterRawExecutor,
    )

    private fun stack(
        credentials: KugouCredentials? = KugouCredentials("42", "synthetic-token"),
        tracks: Map<String, Track> = emptyMap(),
        playlistResponse: ((KugouRequest) -> JSONObject)? = null,
        tracksResponse: ((KugouRequest) -> JSONObject)? = null,
    ): Stack {
        val json = RouterExecutor(fixtures, playlistResponse, tracksResponse)
        val raw = RouterRawExecutor(fixtures)
        val api = KugouLibraryApi(
            executor = json,
            rawExecutor = raw,
            credentialsProvider = { credentials },
            deviceProvider = { device },
            clockSeconds = { 1_700_000_000L },
        )
        val domain = KugouLibraryDomain(
            api = api,
            rememberTracks = { it },
            trackLookup = { id -> tracks[id] },
            sharePresentation = SharePresentationUseCase(QrImageRenderer { "data:image/png;base64,TEST" }),
        )
        return Stack(domain, json, raw)
    }

    private fun kugouTrack(id: String, hash: String) = Track(
        id = id,
        title = "收藏歌曲一",
        artists = listOf("歌手甲"),
        album = "专辑一",
        artworkUri = "",
        durationMs = 201_000,
        qualities = listOf("128"),
        source = PlaybackSource.Kugou(trackId = id, hash = hash, albumId = "11"),
    )

    @Test
    fun `real creator metadata takes precedence over playlist type`() = runBlocking {
        val raw = JSONObject("""{"data":{"info":[
            {"listid":1,"name":"我喜欢的音乐","type":1,"source":1,"list_create_userid":42},
            {"listid":2,"name":"Collected","type":0,"source":1,"list_create_userid":99},
            {"listid":3,"name":"Album","type":0,"source":2,"list_create_userid":99}
        ]}}""")
        val domain = stack(playlistResponse = { raw }).domain
        assertEquals(listOf("1"), domain.librarySection(LibrarySection.CREATED_PLAYLISTS, null).getOrThrow().items.map { it.id })
        assertEquals(listOf("2"), domain.librarySection(LibrarySection.FAVORITE_PLAYLISTS, null).getOrThrow().items.map { it.id })
        assertEquals(1, domain.librarySection(LibrarySection.FAVORITE_ALBUMS, null).getOrThrow().items.size)
        assertEquals("1", LibraryMapper.favoritePlaylistId(raw))
    }

    @Test
    fun `personal playlist uses the authenticated cloudlist track endpoint`() = runBlocking {
        val stack = stack()
        val detail = stack.domain.personalPlaylist("library_300").getOrThrow()
        assertEquals("library_300", detail.id)
        assertEquals(2, detail.tracks.items.size)
        assertEquals(1, stack.json.callCounts["/v4/get_list_all_file"])
        assertEquals(null, stack.json.callCounts["/v7/get_all_list"])
        assertTrue(stack.domain.personalPlaylist("library_bad").isFailure)
        assertTrue(stack(credentials = null).domain.personalPlaylist("library_300").exceptionOrNull() is DirectLoginRequiredException)
    }

    @Test
    fun `personal navigation id is separate from the mutation list id`() = runBlocking {
        val favorite = stack().domain.librarySection(LibrarySection.FAVORITE_PLAYLISTS, null).getOrThrow().items.single()
        assertEquals("300", favorite.id)
        assertEquals("playlist:library_300", favorite.detailId)
    }

    @Test
    fun `full mixed playlist page retains cursor even without any favorites`() = runBlocking {
        val raw = JSONObject().put("data", JSONObject().put("info", JSONArray().apply {
            repeat(30) { put(JSONObject().put("listid", it + 1).put("listname", "Created $it").put("type", 0).put("list_create_userid", 42)) }
        }).put("total", 31))
        val domain = stack(playlistResponse = { raw }).domain
        val favorites = domain.librarySection(LibrarySection.FAVORITE_PLAYLISTS, null).getOrThrow()
        assertTrue(favorites.items.isEmpty())
        assertEquals("2", favorites.nextCursor)
        assertEquals(null, favorites.total) // mixed total must not be called a favorite count
    }

    @Test
    fun `playlist page failures are not successful empty libraries`() = runBlocking {
        val domain = stack(playlistResponse = { throw IllegalStateException("offline") }).domain
        assertTrue(domain.librarySection(LibrarySection.FAVORITE_PLAYLISTS, null).isFailure)
        assertTrue(stack(credentials = null).domain.librarySection(LibrarySection.CREATED_PLAYLISTS, null).exceptionOrNull() is DirectLoginRequiredException)
        val malformed = stack(playlistResponse = { JSONObject().put("data", JSONObject()) }).domain
        assertTrue(malformed.librarySection(LibrarySection.FAVORITE_PLAYLISTS, null).isFailure)
    }

    @Test
    fun `playlist cancellation is propagated`() = runBlocking {
        val domain = stack(playlistResponse = { throw CancellationException("cancel") }).domain
        var cancelled = false
        try { domain.librarySection(LibrarySection.FAVORITE_PLAYLISTS, null) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test
    fun `mixed page cursor also applies to all playlist listings`() = runBlocking {
        val raw = JSONObject().put("data", JSONObject().put("info", JSONArray().apply {
            repeat(30) { put(JSONObject().put("listid", it + 1).put("type", 1).put("source", 2)) }
        }))
        val domain = stack(playlistResponse = { raw }).domain
        assertEquals("2", domain.myPlaylists(null).getOrThrow().nextCursor)
        assertEquals("2", domain.userPlaylists("42", null).getOrThrow().nextCursor)
    }

    @Test
    fun `favorite with same request id executes only once`() = runBlocking {
        val stack = stack(tracks = mapOf("111" to kugouTrack("111", "hash111")))
        val target = MutationTarget(ContentKind.SONG, "111")

        val first = stack.domain.favorite("req-1", target, true)
        val second = stack.domain.favorite("req-1", target, true)

        assertTrue(first.isSuccess)
        assertEquals(first.getOrNull(), second.getOrNull())
        assertEquals(1, stack.json.callCounts["/cloudlist.service/v6/add_song"])
    }

    @Test fun `unfavorite scans later private pages and deletes fileid not global song id`() = runBlocking {
        val stack = stack(tracks = mapOf("111" to kugouTrack("111", "target-hash")), tracksResponse = { request ->
            val page = (request.body as JSONObject).getInt("page")
            JSONObject().put("data", JSONObject().put("info", JSONArray().apply {
                if (page == 1) repeat(100) { put(JSONObject().put("fileid", it + 1).put("album_audio_id", it + 900).put("hash", "other-$it")) }
                else put(JSONObject().put("fileid", 9876).put("album_audio_id", 111).put("hash", "target-hash"))
            }))
        })
        assertTrue(stack.domain.favorite("unlike-r50", MutationTarget(ContentKind.SONG, "111"), false).isSuccess)
        assertEquals(2, stack.json.callCounts["/v4/get_list_all_file"])
        val body = stack.json.requests.single { it.path == "/v4/delete_songs" }.body as JSONObject
        assertEquals(9876L, body.getJSONArray("data").getJSONObject(0).getLong("fileid"))
    }

    @Test fun `hash-only song can be unfavorited by case insensitive row hash`() = runBlocking {
        val stack = stack(tracks = mapOf("hash-only" to kugouTrack("hash-only", "ABC")), tracksResponse = {
            JSONObject("""{"info":[{"fileid":999,"album_audio_id":111,"hash":"abc"}]}""")
        })
        assertTrue(stack.domain.favorite("hash-unlike", MutationTarget(ContentKind.SONG, "hash-only"), false).isSuccess)
        assertEquals(1, stack.json.callCounts["/v4/delete_songs"])
    }

    @Test fun `missing or invalid file identity never issues a delete`() = runBlocking {
        for (row in listOf("""{"album_audio_id":111,"hash":"abc"}""", """{"fileid":0,"album_audio_id":111,"hash":"abc"}""", """{"fileid":111,"album_audio_id":222,"hash":"other"}""")) {
            val stack = stack(tracks = mapOf("111" to kugouTrack("111", "abc")), tracksResponse = {
                JSONObject().put("info", JSONArray().put(JSONObject(row)))
            })
            assertTrue(stack.domain.favorite("invalid-unlike", MutationTarget(ContentKind.SONG, "111"), false).isFailure)
            assertEquals(null, stack.json.callCounts["/v4/delete_songs"])
        }
    }

    @Test fun `Echo file aliases work without confusing audio id with album audio id`() = runBlocking {
        for (row in listOf("""{"album_audio_id":111,"hash":"abc","audio_id":888}""", """{"base":{"album_audio_id":111,"file_id":888},"audio_info":{"hash":"abc"}}""")) {
            val stack = stack(tracks = mapOf("111" to kugouTrack("111", "abc")), tracksResponse = { JSONObject().put("info", JSONArray().put(JSONObject(row))) })
            assertTrue(stack.domain.favorite("alias-unlike", MutationTarget(ContentKind.SONG, "111"), false).isSuccess)
            val body = stack.json.requests.single { it.path == "/v4/delete_songs" }.body as JSONObject
            assertEquals(888L, body.getJSONArray("data").getJSONObject(0).getLong("fileid"))
        }
        val stack = stack(tracks = mapOf("111" to kugouTrack("111", "abc")), tracksResponse = { JSONObject("""{"info":[{"audio_id":111,"hash":"different","fileid":888}]}""") })
        assertTrue(stack.domain.favorite("wrong-audio", MutationTarget(ContentKind.SONG, "111"), false).isFailure)
        assertEquals(null, stack.json.callCounts["/v4/delete_songs"])
    }

    @Test fun `repeating song pages terminate without a delete`() = runBlocking {
        val stack = stack(tracks = mapOf("111" to kugouTrack("111", "abc")), tracksResponse = {
            JSONObject().put("info", JSONArray().apply { repeat(100) { put(JSONObject().put("fileid", it + 1).put("hash", "other-$it")) } })
        })
        assertTrue(stack.domain.favorite("repeat-unlike", MutationTarget(ContentKind.SONG, "111"), false).isFailure)
        assertEquals(2, stack.json.callCounts["/v4/get_list_all_file"])
        assertEquals(null, stack.json.callCounts["/v4/delete_songs"])
    }

    @Test
    fun `delete cloud item requires membership in current cloud page`() = runBlocking {
        val stack = stack()
        assertTrue(stack.domain.librarySection(LibrarySection.CLOUD, null).isSuccess)

        assertTrue(stack.domain.deleteCloudItem("cloud:901").isSuccess)
        assertEquals(1, stack.raw.callCounts["/v1/del_files"])

        val missing = stack.domain.deleteCloudItem("cloud:999")
        assertTrue(missing.isFailure)
        assertTrue(missing.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `favorite mv and user are unsupported`() = runBlocking {
        val stack = stack()

        val mv = stack.domain.favorite("r1", MutationTarget(ContentKind.MV, "x"), true)
        assertTrue(mv.isFailure)
        assertTrue(mv.exceptionOrNull() is UnsupportedOperationException)

        val user = stack.domain.favorite("r2", MutationTarget(ContentKind.USER, "x"), true)
        assertTrue(user.isFailure)
        assertTrue(user.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun `mutations require login`() = runBlocking {
        val stack = stack(
            credentials = null,
            tracks = mapOf("111" to kugouTrack("111", "hash111")),
        )

        val favorite = stack.domain.favorite("r1", MutationTarget(ContentKind.SONG, "111"), true)
        assertTrue(favorite.isFailure)
        assertTrue(favorite.exceptionOrNull() is DirectLoginRequiredException)

        val add = stack.domain.addToPlaylist(PlaylistMutation("100", "111"))
        assertTrue(add.isFailure)
        assertTrue(add.exceptionOrNull() is DirectLoginRequiredException)
    }

    @Test
    fun `library returns per section counts and featured favorite songs`() = runBlocking {
        val stack = stack()
        val summary = stack.domain.library().getOrThrow()

        assertEquals(2, summary.counts[LibrarySection.FAVORITE_SONGS])
        assertEquals(1, summary.counts[LibrarySection.HISTORY])
        assertEquals(2, summary.counts[LibrarySection.CLOUD])
        assertEquals(0, summary.counts[LibrarySection.FAVORITE_USERS])
        assertEquals(2, summary.featured.size)
    }

    @Test
    fun `library section maps a page of cards`() = runBlocking {
        val stack = stack()
        val page = stack.domain.librarySection(LibrarySection.CLOUD, null).getOrThrow()

        assertEquals(2, page.items.size)
        assertEquals("cloud:901", page.items[0].id)
    }
}
