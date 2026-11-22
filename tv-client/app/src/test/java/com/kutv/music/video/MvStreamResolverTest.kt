package com.kutv.music.video

import com.kutv.music.direct.KugouDeviceIdentity
import com.kutv.music.direct.KugouRequest
import com.kutv.music.direct.KugouRequestExecutor
import com.kutv.music.direct.KugouResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MvStreamResolverTest {
    @Test fun `missing HD falls back to available lower tier and reports real tiers`() = runBlocking {
        val requests = mutableListOf<KugouRequest>()
        val resolver = resolver(KugouRequestExecutor { request ->
            requests += request
            KugouResponse(200, if (request.path == "/v1/video")
                JSONObject("""{"data":[{"hd_hash":"","sd_hash":"cccccccccccccccccccccccccccccccc","ld_hash":"dddddddddddddddddddddddddddddddd"}]}""")
            else JSONObject("""{"data":{"source":{"downurl":"http://kgv.stream.tencentmusic.com/real-shape.mp4"}}}"""))
        })
        val stream = resolver.resolve("mv:16959045", "hd").getOrThrow()
        assertEquals("sd", stream.quality)
        assertEquals(listOf("sd", "ld"), stream.availableQualities)
        assertEquals("c".repeat(32), requests.last().query["hash"])
        assertEquals(2, requests.size)
    }

    @Test fun `only higher tier available still plays without blind URL retries`() = runBlocking {
        val requests = mutableListOf<KugouRequest>()
        val resolver = resolver(KugouRequestExecutor { request ->
            requests += request
            KugouResponse(200, if (request.path == "/v1/video")
                JSONObject("""{"data":[{"fhd_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}""")
            else JSONObject("""{"downurl":"https://webfs.kugou.com/fhd.mp4"}"""))
        })
        assertEquals("fhd", resolver.resolve("mv:123", "hd").getOrThrow().quality)
        assertEquals(2, requests.size)
    }

    @Test fun `no usable tier never calls stream endpoint`() = runBlocking {
        var calls = 0
        val resolver = resolver(KugouRequestExecutor {
            calls++
            KugouResponse(200, JSONObject("""{"data":[{"hd_hash":"","sd_hash":"not-a-hash"}]}"""))
        })
        assertTrue(resolver.resolve("mv:123", "hd").isFailure)
        assertEquals(1, calls)
    }

    @Test fun `official cleartext media URL follows existing scoped CDN policy`() = runBlocking {
        val result = resolveStreamResponse("""{"downurl":"http://mv.bssdl.kugou.com/test.mp4"}""")
        assertTrue(result.isSuccess)
        assertEquals("http://mv.bssdl.kugou.com/test.mp4", result.getOrThrow().url)
    }

    @Test fun `invalid primary URL does not mask an allowed backup`() = runBlocking {
        val result = resolveStreamResponse("""{"downurl":"https://evil.example/test.mp4","backupdownurl":["https://webfs.kugou.com/good.mp4"]}""")
        assertTrue(result.isSuccess)
        assertEquals("https://webfs.kugou.com/good.mp4", result.getOrThrow().url)
    }

    @Test fun `MV mapping never broadens the shared media policy`() = runBlocking {
        for (url in listOf("http://imge.kgimg.com/test.mp4", "https://kugou.com.evil.example/test.mp4",
            "https://user:password@webfs.kugou.com/test.mp4", "https://webfs.kugou.com:8443/test.mp4")) {
            assertTrue(resolveStreamResponse(JSONObject().put("downurl", url).toString()).isFailure)
        }
    }

    private suspend fun resolveStreamResponse(body: String): Result<com.kutv.music.video.ResolvedMvStream> =
        resolver(KugouRequestExecutor { request -> KugouResponse(200,
            if (request.path == "/v1/video") fixture.getJSONObject("detailResponse") else JSONObject(body))
        }).resolve("mv:123", "hd")

    @Test fun `flat production video detail selects hd hash from data record`() = runBlocking {
        val requests = mutableListOf<KugouRequest>()
        val resolver = resolver(KugouRequestExecutor { request ->
            requests += request
            KugouResponse(200, if (request.path == "/v1/video")
                JSONObject("""{"data":[{"hd_hash":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB","sd_hash":"cccccccccccccccccccccccccccccccc"}]}""")
            else JSONObject("""{"data":{"downurl":"https://webfs.kugou.com/actual-shape.mp4"}}"""))
        })
        assertTrue(resolver.resolve("mv:123", "hd").isSuccess)
        assertEquals("b".repeat(32), requests.last().query["hash"])
    }

    @Test fun `invalid first codec hash does not mask a valid alternate codec`() = runBlocking {
        val resolver = resolver(KugouRequestExecutor { request ->
            KugouResponse(200, if (request.path == "/v1/video")
                JSONObject("""{"data":{"info":[{"h264":{"hd_hash":"null"},"h265":{"hd_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}]}}""")
            else JSONObject("""{"downurl":"https://webfs.kugou.com/valid.mp4"}"""))
        })
        assertTrue(resolver.resolve("mv:123", "hd").isSuccess)
    }

    private val fixture = JSONObject(
        checkNotNull(javaClass.classLoader?.getResource("kugou/protocol-fixtures.json")).readText(),
    ).getJSONObject("mv")

    @Test
    fun `KuGou fixture selects the deterministic h264 quality hash for interface index`() = runBlocking {
        val requests = mutableListOf<KugouRequest>()
        val executor = KugouRequestExecutor { request ->
            requests += request
            val body = when (request.path) {
                "/v1/video" -> fixture.getJSONObject("detailResponse")
                "/v2/interface/index" -> fixture.getJSONObject("streamResponse")
                else -> error("unexpected fixture path")
            }
            KugouResponse(200, body)
        }
        val resolver = resolver(executor)

        val result = resolver.resolve("mv:${fixture.getString("videoId")}", fixture.getString("quality"))

        assertTrue(result.isSuccess)
        assertEquals(listOf("/v1/video", "/v2/interface/index"), requests.map(KugouRequest::path))
        assertEquals(fixture.getString("expectedHash"), requests[1].query["hash"])
        assertEquals(
            fixture.getString("videoId"),
            (requests[0].body as JSONObject).getJSONArray("data").getJSONObject(0).getString("video_id"),
        )
        assertEquals("video/mp4", result.getOrThrow().mimeType)
        assertEquals("hd", result.getOrThrow().quality)
    }

    @Test
    fun `malformed quality response maps to one fixed controller error code without leaking fixture data`() = runBlocking {
        val resolver = resolver(KugouRequestExecutor { KugouResponse(200, JSONObject("""{"data":{"info":[]}}""")) })
        val controller = MvPlaybackController(resolver, NoopMvPlayer(), PlaybackOwnership())

        val result = controller.open("mv:9001", "hd")

        assertFalse(result.getBoolean("accepted"))
        assertEquals("MV_RESOLVE_FAILED", result.getString("errorCode"))
        assertFalse(result.toString().contains("hash", ignoreCase = true))
        assertFalse(result.toString().contains("url", ignoreCase = true))
        assertFalse(result.toString().contains("token", ignoreCase = true))
    }

    private fun resolver(executor: KugouRequestExecutor) = KugouMvStreamResolver(
        executor = executor,
        deviceProvider = {
            KugouDeviceIdentity(
                guid = "1".repeat(32),
                mid = "2".repeat(32),
                uuid = "fixture-uuid",
                dfid = "fixture-dfid",
            )
        },
        credentialProvider = { null },
        clockSeconds = { 1_700_000_000L },
    )

    private class NoopMvPlayer : MvPlayer {
        override val currentMediaId: String? = null
        override val durationMs = 0L
        override val currentPositionMs = 0L
        override val isPlaying = false
        override fun setMediaItem(mediaId: String, uri: String, mimeType: String) = Unit
        override fun prepare() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun stop() = Unit
        override fun clearMediaItems() = Unit
        override fun setPlaybackErrorListener(listener: (() -> Unit)?) = Unit
        override fun release() = Unit
    }
}
