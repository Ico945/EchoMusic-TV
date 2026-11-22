package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.direct.KugouPlaybackGateway
import com.kutv.music.direct.KugouPrivilege
import com.kutv.music.direct.KugouStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouDirectPlaybackResolverTest {
    @Test fun `auto source reads the saved preference but explicit quality wins`() = runBlocking {
        val api = FakeGateway()
        var preference = "320"
        val resolver = KugouDirectPlaybackResolver(api, preferredQuality = { preference })
        assertEquals("320", resolver.resolve(remoteTrack("auto")).quality)
        preference = "128"
        assertEquals("128", resolver.resolve(remoteTrack("auto")).quality)
        assertEquals("320", resolver.resolve(remoteTrack("320")).quality)
        assertEquals(listOf("320", "128", "320"), api.requestedQualities)
    }
    @Test fun `only server granted recognized qualities are exposed`() = runBlocking {
        val api = FakeGateway(KugouPrivilege(true, false, listOf("128", "unknown")))
        val resolved = KugouDirectPlaybackResolver(api).resolve(remoteTrack("128"))
        assertEquals(listOf("128"), resolved.track.qualities)
    }
    @Test fun `cancelling an in flight request stops the quality loop`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val requested = mutableListOf<String>()
        val gateway = object : KugouPlaybackGateway {
            override suspend fun getPrivilege(hash: String) = KugouPrivilege(true, false, listOf("flac", "320", "128"))
            override suspend fun getSongUrl(hash: String, quality: String): KugouStream {
                requested += quality
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val pending = launch { KugouDirectPlaybackResolver(gateway).resolve(remoteTrack("flac")) }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(listOf("flac"), requested)
    }

    @Test fun `cancellation propagates without requesting lower qualities`() = runBlocking {
        val cancellation = CancellationException("user selected another track")
        val requested = mutableListOf<String>()
        val gateway = object : KugouPlaybackGateway {
            override suspend fun getPrivilege(hash: String) = KugouPrivilege(true, false, listOf("flac", "320", "128"))
            override suspend fun getSongUrl(hash: String, quality: String): KugouStream {
                requested += quality
                throw cancellation
            }
        }
        val failure = runCatching { KugouDirectPlaybackResolver(gateway).resolve(remoteTrack("flac")) }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertEquals(listOf("flac"), requested)
    }

    @Test fun `recoverable stream failure still tries lower granted quality`() = runBlocking {
        val requested = mutableListOf<String>()
        val gateway = object : KugouPlaybackGateway {
            override suspend fun getPrivilege(hash: String) = KugouPrivilege(true, false, listOf("flac", "128"))
            override suspend fun getSongUrl(hash: String, quality: String): KugouStream {
                requested += quality
                if (quality == "flac") throw java.io.IOException("temporary failure")
                return KugouStream("https://webfs.kugou.com/song.mp3", "audio/mpeg", quality)
            }
        }
        val result = KugouDirectPlaybackResolver(gateway).resolve(remoteTrack("flac"))
        assertEquals("128", result.quality)
        assertEquals(listOf("128", "flac"), result.track.qualities)
        assertEquals(listOf("flac", "128"), requested)
    }

    @Test
    fun `chooses highest granted validated quality`() = runBlocking {
        val api = FakeGateway()
        val resolver = KugouDirectPlaybackResolver(api)

        val resolved = resolver.resolve(remoteTrack("flac"))

        assertEquals("320", resolved.quality)
        assertEquals(listOf("128", "320", "flac"), resolved.track.qualities)
        assertEquals("Track", resolved.track.title)
        assertEquals("https://webfs.kugou.com/song.mp3", resolved.streamUrl)
        assertEquals(listOf("flac", "320"), api.requestedQualities)
        assertEquals(listOf("FLAC_HASH", "320_HASH"), api.requestedHashes)
    }

    @Test
    fun `unplayable privilege fails closed`() = runBlocking {
        val api = FakeGateway(privilege = KugouPrivilege(false, false, emptyList()))
        val error = runCatching {
            KugouDirectPlaybackResolver(api).resolve(remoteTrack("128"))
        }.exceptionOrNull()

        assertTrue(error is PlaybackResolutionException)
        assertTrue(api.requestedQualities.isEmpty())
    }

    @Test
    fun `local source bypasses KuGou`() = runBlocking {
        val api = FakeGateway()
        val local = QueueTrack(
            id = "local",
            title = "Local",
            artists = emptyList(),
            artworkUrl = "",
            source = PlaybackSource.Local("android.resource://com.kutv.music/raw/track_1"),
        )

        val resolved = KugouDirectPlaybackResolver(api).resolve(local)

        assertEquals("LOCAL", resolved.quality)
        assertTrue(api.requestedQualities.isEmpty())
    }

    private fun remoteTrack(preferred: String) = QueueTrack(
        id = "track-1",
        title = "Track",
        artists = listOf("Artist"),
        artworkUrl = "",
        source = PlaybackSource.Kugou("track-1", "HASH", preferredQuality = preferred),
    )

    private class FakeGateway(
        private val privilege: KugouPrivilege = KugouPrivilege(
            true,
            false,
            listOf("128", "320", "flac"),
            mapOf("128" to "128_HASH", "320" to "320_HASH", "flac" to "FLAC_HASH"),
        ),
    ) : KugouPlaybackGateway {
        val requestedQualities = mutableListOf<String>()
        val requestedHashes = mutableListOf<String>()
        override suspend fun getPrivilege(hash: String) = privilege
        override suspend fun getSongUrl(hash: String, quality: String): KugouStream {
            requestedQualities += quality
            requestedHashes += hash
            return if (quality == "flac") {
                KugouStream("https://evil.example/song.flac", "audio/flac", "flac")
            } else {
                KugouStream("https://webfs.kugou.com/song.mp3", "audio/mpeg", quality)
            }
        }
    }
}
