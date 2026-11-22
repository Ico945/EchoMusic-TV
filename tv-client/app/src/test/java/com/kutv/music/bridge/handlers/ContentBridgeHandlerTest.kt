package com.kutv.music.bridge.handlers

import com.kutv.music.application.content.ShareProjection
import com.kutv.music.bridge.BridgeErrors
import com.kutv.music.bridge.mappers.BridgeTrackIds
import com.kutv.music.core.model.AlbumDetail
import com.kutv.music.core.model.ArtistDetail
import com.kutv.music.core.model.Comment
import com.kutv.music.core.model.CommentTarget
import com.kutv.music.core.model.CommentTargetKind
import com.kutv.music.core.model.ContentCard
import com.kutv.music.core.model.ContentDetail
import com.kutv.music.core.model.ContentKind
import com.kutv.music.core.model.ExploreContent
import com.kutv.music.core.model.ExploreSection
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
import com.kutv.music.data.TvContentRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBridgeHandlerTest {
    @Test fun `artist children add playable tracks while retaining cards and opaque ids`() = runBlocking {
        val track = Track("native-artist-song", "Artist song", listOf("Artist"), "", "", 120000, listOf("128"),
            com.kutv.music.core.model.PlaybackSource.Kugou("native-artist-song", "private-hash"))
        val repo = object : FakeRepository() {
            override suspend fun artistTracks(id: String, cursor: String?): Result<PagedResult<Track>> {
                assertEquals("9", id); assertEquals("2", cursor)
                return Result.success(PagedResult(listOf(track), "3", 65))
            }
        }
        val result = DetailBridgeHandler(repo).handlers().getValue("content.detailChildren")
            .handle(JSONObject("""{"kind":"artist","id":"artist:9","tab":"songs","cursor":"2"}"""))
        val card = result.getJSONArray("items").getJSONObject(0)
        val song = result.getJSONArray("tracks").getJSONObject(0)
        assertEquals("song", card.getString("kind"))
        assertEquals(card.getString("id"), song.getString("id"))
        assertEquals(track.id, BridgeTrackIds.nativeId(song.getString("id")))
        assertEquals(120000, song.getInt("durationMs"))
        assertEquals("3", result.getString("nextCursor")); assertEquals(65, result.getInt("total"))
        assertTrue(!result.toString().contains("private-hash"))
    }
    @Test fun `all playlist inventory exposes bounded counters without private metadata`() = runBlocking {
        val repo = object : FakeRepository() {
            override suspend fun myPlaylists(cursor: String?) = Result.success(PagedResult(
                listOf(ContentCard("1", "Unnamed", ContentKind.PLAYLIST, detailId = "playlist:library_1")),
                librarySync = com.kutv.music.core.model.LibrarySyncInfo(1, 2, 1, 0, 1, 1, 1),
            ))
        }
        val handler = LibraryBridgeHandler(repo).handlers().getValue("library.playlists")
        val result = handler.handle(JSONObject())
        assertEquals(1, result.getJSONArray("items").length())
        val sync = result.getJSONObject("librarySync")
        assertEquals(setOf("page", "received", "playlists", "created", "collected", "albums", "missingCreator"), sync.keys().asSequence().toSet())
        assertEquals(2, sync.getInt("received"))
        assertTrue("library.playlists" in LibraryBridgeHandler.METHODS)
        assertTrue(runCatching { handler.handle(JSONObject("""{"userid":"another-account"}""")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun `library parse stage does not expose raw data in the error envelope`() {
        val error = ContentBridgeSupport.contentError(com.kutv.music.direct.content.LibraryReadException(
            com.kutv.music.direct.content.LibraryReadException.Stage.RESPONSE_SHAPE), "library:playlists")
        val envelope = BridgeErrors.fromException("safe-request", error)
        assertEquals("LIBRARY_RESPONSE_SHAPE", envelope.getJSONObject("error").getString("code"))
    }

    @Test
    fun `explore adds public failed section ids without replacing existing arrays`() = runBlocking {
        val repo = object : FakeRepository() {
            override suspend fun explore() = Result.success(ExploreContent(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                failedSections = setOf(ExploreSection.NEW_SONGS, ExploreSection.RANKINGS),
            ))
        }
        val result = BrowseBridgeHandler(repo).handlers().getValue("content.explore").handle(JSONObject())
        assertEquals("[\"newSongs\",\"rankings\"]", result.getJSONArray("failedSections").toString())
        listOf("newSongs", "newAlbums", "newPlaylists", "artists", "rankings").forEach {
            assertEquals(0, result.getJSONArray(it).length())
        }
    }

    @Test
    fun `search returns cursor without changing category semantics`() = runBlocking {
        val repo = FakeRepository(searchPage = PagedResult(listOf(songCard()), nextCursor = "2", total = 30))
        val handlers = BrowseBridgeHandler(repo).handlers()
        val result = handlers.getValue("content.search")
            .handle(JSONObject("""{"query":"若把你","category":"song"}"""))

        assertEquals("song", result.getString("category"))
        assertTrue(result.getJSONArray("items").length() > 0)
        assertEquals("2", result.getString("nextCursor"))
    }

    @Test
    fun `search rejects an unsupported category`() {
        runBlocking {
            val handlers = BrowseBridgeHandler(FakeRepository()).handlers()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    handlers.getValue("content.search").handle(JSONObject("""{"query":"x","category":"user"}"""))
                }
            }
        }
    }

    @Test
    fun `a failed comment page keeps the detail scope in the error envelope`() = runBlocking {
        val repo = FailingRepository(scope = "comments:playlist:1")
        val handlers = DetailBridgeHandler(repo).handlers()
        val error = runCatching {
            handlers.getValue("content.comments").handle(JSONObject("""{"kind":"playlist","id":"playlist:1"}"""))
        }.exceptionOrNull()

        val envelope = BridgeErrors.fromException("req-9", error!!)
        assertEquals("req-9", envelope.getString("id"))
        assertTrue(envelope.getJSONObject("error").getString("scope").startsWith("comments"))
        assertTrue(envelope.getJSONObject("error").getBoolean("retryable"))
    }

    @Test
    fun `library favorite validates the request id and returns the normalized state`() {
        runBlocking {
            val repo = FakeRepository()
            val handlers = LibraryBridgeHandler(repo).handlers()
            val result = handlers.getValue("library.favorite").handle(
                JSONObject("""{"kind":"song","id":"tv_opaque","favorite":true,"requestId":"request-123456"}"""),
            )
            assertEquals("song", result.getString("kind"))
            assertTrue(result.getBoolean("favorite"))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    handlers.getValue("library.favorite").handle(
                        JSONObject("""{"kind":"song","id":"tv_opaque","favorite":true,"requestId":"bad"}"""),
                    )
                }
            }
        }
    }

    @Test
    fun `share resolve forwards the link and returns the typed target`() {
        runBlocking {
            val repo = FakeRepository()
            val handlers = ShareBridgeHandler(repo).handlers()
            val result = handlers.getValue("share.resolve")
                .handle(JSONObject("""{"link":"https://www.kugou.com/song/#hash=abc"}"""))
            assertEquals("song", result.getString("kind"))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { handlers.getValue("share.resolve").handle(JSONObject("""{"link":""}""")) }
            }
        }
    }

    @Test
    fun `share create resolves an opaque TV song id before rendering the QR`() = runBlocking {
        BridgeTrackIds.clear()
        val publicId = BridgeTrackIds.publicId("album-audio-123")
        val repo = FakeRepository()
        val result = ShareBridgeHandler(repo).handlers().getValue("share.create").handle(
            JSONObject().put("kind", "song").put("id", publicId),
        )

        assertEquals(ShareTarget(ContentKind.SONG, "album-audio-123"), repo.lastShareTarget)
        assertEquals("data:image/png;base64,TEST", result.getString("qrImageDataUrl"))
    }

    @Test
    fun `library section uses the fixed camelCase section keys`() = runBlocking {
        val repo = FakeRepository(sectionPage = PagedResult(listOf(playlistCard()), nextCursor = null, total = 1))
        val handlers = LibraryBridgeHandler(repo).handlers()
        val result = handlers.getValue("library.section")
            .handle(JSONObject("""{"section":"createdPlaylists"}"""))
        assertEquals("createdPlaylists", result.getString("section"))
        assertEquals("playlist:1", result.getJSONArray("items").getJSONObject(0).getString("id"))
    }

    @Test fun `song favorite round trips opaque identity for both operations`() = runBlocking {
        val publicId = BridgeTrackIds.publicId("99881122")
        var nativeTarget: MutationTarget? = null
        val repo = object : FakeRepository() {
            override suspend fun favoriteWithRequestId(requestId: String, target: MutationTarget, favorite: Boolean): Result<MutationTarget> {
                nativeTarget = target
                return Result.success(target)
            }
        }
        for (favorite in listOf(true, false)) {
            val result = LibraryBridgeHandler(repo).handlers().getValue("library.favorite").handle(
                JSONObject().put("kind", "song").put("id", publicId).put("favorite", favorite).put("requestId", "request-r50-$favorite"))
            assertEquals(MutationTarget(ContentKind.SONG, "99881122"), nativeTarget)
            assertEquals(publicId, result.getString("id"))
            assertEquals(favorite, result.getBoolean("favorite"))
            assertTrue(!result.toString().contains("99881122"))
        }
    }

    private fun songCard() = ContentCard("tv_song_1", "若把你", ContentKind.SONG)
    private fun playlistCard() = ContentCard("playlist:1", "我的歌单", ContentKind.PLAYLIST)

    private open class FakeRepository(
        private val searchPage: PagedResult<ContentCard> = PagedResult(emptyList()),
        private val sectionPage: PagedResult<ContentCard> = PagedResult(emptyList()),
    ) : TvContentRepository {
        var lastShareTarget: ShareTarget? = null
        override suspend fun explore(): Result<ExploreContent> =
            Result.success(ExploreContent(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        override suspend fun dailyRecommendations(): Result<List<Track>> = Result.success(emptyList())
        override suspend fun privateFm(): Result<List<Track>> = Result.success(emptyList())
        override suspend fun discardPrivateFm(trackId: String): Result<Unit> = Result.success(Unit)
        override suspend fun hotSearch(): Result<List<String>> = Result.success(emptyList())
        override suspend fun rankings(): Result<List<RankingSummary>> = Result.success(emptyList())
        override suspend fun ranking(id: String, cursor: String?): Result<PagedResult<Track>> = Result.success(PagedResult(emptyList()))
        override suspend fun search(query: String, category: SearchCategory, cursor: String?): Result<PagedResult<ContentCard>> = Result.success(searchPage)
        override suspend fun detail(kind: ContentKind, id: String): Result<ContentDetail> = Result.failure(UnsupportedOperationException())
        override suspend fun playlistTracks(id: String, cursor: String?): Result<PagedResult<Track>> = Result.success(PagedResult(emptyList()))
        override suspend fun albumTracks(id: String, cursor: String?): Result<PagedResult<Track>> = Result.success(PagedResult(emptyList()))
        override suspend fun artistChildren(id: String, tab: String, cursor: String?): Result<PagedResult<ContentCard>> = Result.success(PagedResult(emptyList()))
        override suspend fun comments(target: CommentTarget, cursor: String?): Result<PagedResult<Comment>> = Result.success(PagedResult(emptyList()))
        override suspend fun library(): Result<LibrarySummary> = Result.success(LibrarySummary(emptyMap(), emptyList()))
        override suspend fun librarySection(section: LibrarySection, cursor: String?): Result<PagedResult<ContentCard>> = Result.success(sectionPage)
        override suspend fun favorite(target: MutationTarget, favorite: Boolean): Result<MutationTarget> = Result.success(target)
        override suspend fun favoriteWithRequestId(requestId: String, target: MutationTarget, favorite: Boolean): Result<MutationTarget> = Result.success(target)
        override suspend fun addToPlaylist(mutation: PlaylistMutation): Result<PlaylistMutation> = Result.success(mutation)
        override suspend fun deleteCloudItem(trackId: String): Result<Unit> = Result.success(Unit)
        override suspend fun profile(userId: String): Result<UserProfile> = Result.success(UserProfile(userId, "用户"))
        override suspend fun userPlaylists(userId: String, cursor: String?): Result<PagedResult<ContentCard>> = Result.success(PagedResult(emptyList()))
        override suspend fun myPlaylists(cursor: String?): Result<PagedResult<ContentCard>> = Result.success(PagedResult(emptyList()))
        override suspend fun resolveShare(input: String): Result<ShareTarget> = Result.success(ShareTarget(ContentKind.SONG, "abc"))
        override suspend fun shareProjection(target: ShareTarget): Result<ShareProjection> {
            lastShareTarget = target
            return Result.success(ShareProjection("https://www.kugou.com/mixsong/${target.id}", "data:image/png;base64,TEST", false))
        }
    }

    private class FailingRepository(private val scope: String) : FakeRepository() {
        override suspend fun comments(target: CommentTarget, cursor: String?): Result<PagedResult<Comment>> =
            Result.failure(IllegalStateException("network"))
    }
}
