package com.kutv.music.data

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Track
import com.kutv.music.direct.KugouContentGateway
import com.kutv.music.direct.KugouHomeFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDirectMusicRepositoryTest {
    private val first = remote("1")
    private val second = remote("2")

    @Test
    fun `anonymous home and search use direct content`() = kotlinx.coroutines.runBlocking {
        val repository = KugouDirectMusicRepository(FakeGateway())

        val home = repository.home().getOrThrow()
        assertEquals(first.id, home.hero.id)
        assertEquals("酷狗新歌", home.shelves.first().title)
        assertEquals(listOf(first, second), repository.search("music").getOrThrow())
    }

    @Test
    fun `blank search does not call upstream`() = kotlinx.coroutines.runBlocking {
        val gateway = FakeGateway()
        val repository = KugouDirectMusicRepository(gateway)

        assertTrue(repository.search("  ").getOrThrow().isEmpty())
        assertEquals(0, gateway.searchCalls)
    }

    @Test
    fun `library preserves login required error`() = kotlinx.coroutines.runBlocking {
        val repository = KugouDirectMusicRepository(FakeGateway(loginRequired = true))
        val error = repository.library().exceptionOrNull()
        assertTrue(error is DirectLoginRequiredException)
    }

    @Test
    fun `home preserves upstream failure instead of falling back to experience content`() = kotlinx.coroutines.runBlocking {
        val repository = KugouDirectMusicRepository(FakeGateway(homeFailure = true))

        val result = repository.home()

        assertTrue(result.isFailure)
        assertEquals("upstream unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `bootstrap home does not expose experience content`() = kotlinx.coroutines.runBlocking {
        val repository = KugouDirectMusicRepository(FakeGateway())

        assertNull(repository.bootstrapHome())
    }

    private inner class FakeGateway(
        private val loginRequired: Boolean = false,
        private val homeFailure: Boolean = false,
    ) : KugouContentGateway {
        var searchCalls = 0
        override suspend fun home(): KugouHomeFeed {
            if (homeFailure) error("upstream unavailable")
            return KugouHomeFeed(first, listOf(first, second), listOf(second), emptyList())
        }
        override suspend fun newReleases() = listOf(first, second)
        override suspend fun library(): List<Track> {
            if (loginRequired) throw DirectLoginRequiredException()
            return listOf(second)
        }
        override suspend fun search(query: String): List<Track> {
            searchCalls++
            return listOf(first, second)
        }
        override suspend fun lyrics(trackId: String) = emptyList<com.kutv.music.core.model.TimedLyricLine>()
    }

    private fun remote(id: String) = Track(
        id = id,
        title = "Track $id",
        artists = listOf("Artist"),
        album = "Album",
        artworkUri = "https://imge.kugou.com/$id.jpg",
        durationMs = 1_000,
        qualities = listOf("128"),
        source = PlaybackSource.Kugou(id, "HASH$id"),
    )
}
