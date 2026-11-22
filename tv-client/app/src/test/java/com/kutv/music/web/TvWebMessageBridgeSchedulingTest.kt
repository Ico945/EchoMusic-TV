package com.kutv.music.web

import com.kutv.music.bridge.BridgeRouter
import com.kutv.music.bridge.handlers.VideoBridgeHandler
import com.kutv.music.video.MvPlaybackController
import com.kutv.music.video.MvPlayer
import com.kutv.music.video.MvRouteGate
import com.kutv.music.video.MvStreamResolver
import com.kutv.music.video.PlaybackOwner
import com.kutv.music.video.PlaybackOwnership
import com.kutv.music.video.ResolvedMvStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class TvWebMessageBridgeSchedulingTest {
    @Test
    fun `close crosses the production bridge mutex while a non cooperative open is pending`() = runBlocking {
        val resolverStarted = CountDownLatch(1)
        val releaseResolver = CountDownLatch(1)
        val player = SchedulingPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                resolverStarted.countDown()
                check(releaseResolver.await(5, TimeUnit.SECONDS))
                Result.success(ResolvedMvStream(
                    "https://media.example.invalid/late.mp4?synthetic=placeholder",
                    "video/mp4",
                    quality,
                ))
            },
            player = player,
            ownership = ownership,
        )
        val gate = MvRouteGate(controller::close).apply {
            observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3Apending")
        }
        val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val router = BridgeRouter(VideoBridgeHandler({ controller }, gate, handlerScope).handlers())
        val productionMutex = Mutex()

        val opening = async(Dispatchers.Default) {
            TvWebMessageBridge.dispatch(router, request("open", "video.open", JSONObject()
                .put("mvId", "mv:pending").put("quality", "hd")), productionMutex)
        }
        try {
            assertTrue("resolver must enter its blocking prefix", resolverStarted.await(1, TimeUnit.SECONDS))
            val openResponse = withTimeout(250L) { opening.await() }
            val closeResponse = withTimeout(250L) {
                TvWebMessageBridge.dispatch(router, request("close", "video.close", JSONObject()), productionMutex)
            }

            val open = JSONObject(openResponse)
            val close = JSONObject(closeResponse)
            assertEquals("open", open.getString("id"))
            assertTrue(open.getJSONObject("payload").getBoolean("pending"))
            assertEquals("close", close.getString("id"))
            assertTrue(close.getJSONObject("payload").getBoolean("accepted"))
        } finally {
            releaseResolver.countDown()
        }
        repeat(8) { yield() }

        assertEquals(0, player.setMediaItemCalls)
        assertFalse(player.isPlaying)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
    }

    @Test
    fun `resolver admission stays bounded across one hundred bridge reload attempts`() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val resolverStarts = AtomicInteger()
        val dispatcher = CountingResolverDispatcher()
        val player = SchedulingPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                when (resolverStarts.incrementAndGet()) {
                    1 -> {
                        firstStarted.countDown()
                        check(releaseFirst.await(5, TimeUnit.SECONDS))
                    }
                    2 -> {
                        secondStarted.countDown()
                        check(releaseSecond.await(5, TimeUnit.SECONDS))
                    }
                    else -> error("unexpected resolver body")
                }
                Result.success(ResolvedMvStream(
                    "https://media.example.invalid/late.mp4?synthetic=placeholder",
                    "video/mp4",
                    quality,
                ))
            },
            player = player,
            ownership = ownership,
            resolverDispatcher = dispatcher,
            stateDispatcher = Dispatchers.Unconfined,
        )
        val mvUrl = "https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3Abounded"
        val gate = MvRouteGate(controller::close).apply { observe(mvUrl) }
        val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val router = BridgeRouter(VideoBridgeHandler({ controller }, gate, handlerScope).handlers())
        val productionMutex = Mutex()

        try {
            val firstOpen = withTimeout(250L) {
                TvWebMessageBridge.dispatch(
                    router,
                    request("initial", "video.open", JSONObject().put("mvId", "mv:bounded").put("quality", "hd")),
                    productionMutex,
                )
            }
            assertTrue(JSONObject(firstOpen).getJSONObject("payload").getBoolean("pending"))
            assertTrue("first resolver must occupy the only slot", firstStarted.await(1, TimeUnit.SECONDS))

            repeat(100) { attempt ->
                val closeResponse = withTimeout(250L) {
                    TvWebMessageBridge.dispatch(
                        router,
                        request("close-$attempt", "video.close", JSONObject()),
                        productionMutex,
                    )
                }
                assertTrue(JSONObject(closeResponse).getJSONObject("payload").getBoolean("accepted"))
                gate.observe(null)
                gate.observe(mvUrl)

                val openResponse = withTimeout(250L) {
                    TvWebMessageBridge.dispatch(
                        router,
                        request(
                            "reload-$attempt",
                            "video.open",
                            JSONObject().put("mvId", "mv:bounded-$attempt").put("quality", "hd"),
                        ),
                        productionMutex,
                    )
                }
                val payload = JSONObject(openResponse).getJSONObject("payload")
                assertFalse("attempt $attempt must not queue resolver work", payload.getBoolean("accepted"))
                assertEquals("MV_RESOLVER_BUSY", payload.getString("errorCode"))
                assertEquals(setOf("accepted", "errorCode"), payload.keys().asSequence().toSet())
                assertFalse(openResponse.contains("http", ignoreCase = true))
                assertFalse(openResponse.contains("token", ignoreCase = true))
                assertFalse(openResponse.contains("hash", ignoreCase = true))
            }

            assertEquals(1, resolverStarts.get())
            assertEquals("no resolver runnable may be submitted behind the blocked job", 1, dispatcher.submitted.get())
            assertEquals(0, player.setMediaItemCalls)
            assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)

            releaseFirst.countDown()
            val nextOpen = withTimeout(1_000L) {
                while (true) {
                    val response = TvWebMessageBridge.dispatch(
                        router,
                        request("after-release", "video.open", JSONObject().put("mvId", "mv:next").put("quality", "hd")),
                        productionMutex,
                    )
                    val payload = JSONObject(response).getJSONObject("payload")
                    if (payload.getBoolean("accepted")) return@withTimeout response
                    assertEquals("MV_RESOLVER_BUSY", payload.getString("errorCode"))
                    yield()
                }
                error("unreachable")
            }
            assertTrue(JSONObject(nextOpen).getJSONObject("payload").getBoolean("pending"))
            assertTrue("a completed slot must admit the next resolver", secondStarted.await(1, TimeUnit.SECONDS))
            assertEquals(2, resolverStarts.get())
            assertEquals(2, dispatcher.submitted.get())

            repeat(8) { yield() }
            val whileSecondRuns = withTimeout(250L) {
                TvWebMessageBridge.dispatch(
                    router,
                    request("while-second-runs", "video.setQuality", JSONObject().put("quality", "fhd")),
                    productionMutex,
                )
            }
            val busyPayload = JSONObject(whileSecondRuns).getJSONObject("payload")
            assertFalse("an old completion must not clear the new resolver slot", busyPayload.getBoolean("accepted"))
            assertEquals("MV_RESOLVER_BUSY", busyPayload.getString("errorCode"))
            assertEquals(2, resolverStarts.get())
            assertEquals(2, dispatcher.submitted.get())
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            handlerScope.cancel()
            dispatcher.close()
        }
    }

    private fun request(id: String, method: String, payload: JSONObject): String = JSONObject()
        .put("version", 1)
        .put("type", "request")
        .put("id", id)
        .put("method", method)
        .put("payload", payload)
        .toString()

    private class SchedulingPlayer : MvPlayer {
        override val currentMediaId: String? = null
        override val durationMs = 0L
        override val currentPositionMs = 0L
        override var isPlaying = false
        var setMediaItemCalls = 0
        override fun setMediaItem(mediaId: String, uri: String, mimeType: String) { setMediaItemCalls += 1 }
        override fun prepare() = Unit
        override fun play() { isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun seekTo(positionMs: Long) = Unit
        override fun stop() { isPlaying = false }
        override fun clearMediaItems() = Unit
        override fun setPlaybackErrorListener(listener: (() -> Unit)?) = Unit
        override fun release() = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class CountingResolverDispatcher : CoroutineDispatcher(), AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val submitted = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            submitted.incrementAndGet()
            executor.execute(block)
        }

        override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
            require(parallelism == 1)
            return this
        }

        override fun close() {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
