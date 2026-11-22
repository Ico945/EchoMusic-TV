package com.kutv.music.video

import com.kutv.music.bridge.BridgeRouter
import com.kutv.music.bridge.handlers.VideoBridgeHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MvPlaybackControllerTest {
    @Test fun `background pauses MV preserving media and position and return does not autoplay`() = runBlocking {
        val player = FakeMvPlayer()
        val controller = MvPlaybackController(
            MvStreamResolver { _, quality -> Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality)) },
            player, PlaybackOwnership(),
        )
        controller.open("mv:42", "hd")
        player.currentPositionMs = 9_000L
        controller.setForeground(false)
        assertTrue(controller.isOpen)
        assertFalse(player.isPlaying)
        assertEquals(9_000L, controller.snapshot().getLong("positionMs"))
        assertFalse(controller.toggle().getBoolean("accepted"))
        controller.setForeground(true)
        assertFalse(player.isPlaying)
        controller.toggle()
        assertTrue(player.isPlaying)
        assertEquals("mv:42", player.currentMediaId)
    }

    @Test fun `MV resolution completed in background cannot autoplay`() = runBlocking {
        val resolution = CompletableDeferred<Result<ResolvedMvStream>>()
        val started = CompletableDeferred<Unit>()
        val player = FakeMvPlayer()
        val controller = MvPlaybackController(
            MvStreamResolver { _, _ -> started.complete(Unit); resolution.await() }, player, PlaybackOwnership(),
        )
        val opening = async { controller.open("mv:pending", "hd") }
        started.await()
        controller.setForeground(false)
        resolution.complete(Result.success(ResolvedMvStream(secretUrl, "video/mp4", "hd")))
        assertTrue(opening.await().getBoolean("accepted"))
        assertTrue(controller.isOpen)
        assertFalse(player.isPlaying)
        assertEquals(0, player.playCalls)
    }

    @Test fun `fallback quality and available tiers come from resolved stream not requested tier`() = runBlocking {
        val controller = MvPlaybackController(
            MvStreamResolver { _, _ -> Result.success(ResolvedMvStream(
                "https://webfs.kugou.com/test.mp4", "video/mp4", "sd", listOf("sd", "ld"))) },
            FakeMvPlayer(), PlaybackOwnership(),
        )
        assertEquals("sd", controller.open("mv:123", "hd").getString("quality"))
        assertEquals("sd", controller.snapshot().getString("quality"))
        assertEquals("[\"sd\",\"ld\"]", controller.snapshot().getJSONArray("availableQualities").toString())
        assertEquals("sd", controller.setQuality("fhd").getString("quality"))
        controller.close()
        assertFalse(controller.snapshot().has("availableQualities"))
    }

    private val secretUrl = "https://media.example.invalid/video.mp4?hash=SECRET_HASH&token=SECRET_TOKEN"

    @Test
    fun `open resolves and plays without returning the stream url`() = runBlocking {
        val events = mutableListOf<String>()
        val player = FakeMvPlayer(events)
        val ownership = PlaybackOwnership { events += "pause-music"; true }
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { mvId, quality ->
                events += "resolve:$mvId:$quality"
                Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality))
            },
            player = player,
            ownership = ownership,
        )

        val result = controller.open(mvId = "mv:42", quality = "hd")

        assertTrue(result.getBoolean("accepted"))
        assertFalse(result.toString().contains("http", ignoreCase = true))
        assertFalse(result.toString().contains("SECRET", ignoreCase = true))
        assertEquals("mv:42", player.currentMediaId)
        assertTrue(player.isPlaying)
        assertEquals(PlaybackOwner.MV, ownership.value)
        assertEquals(listOf("resolve:mv:42:hd", "pause-music", "set:mv:42", "prepare", "play"), events)
    }

    @Test
    fun `resolver failure stays sanitized and leaves music ownership untouched`() = runBlocking {
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, _ -> Result.failure(IllegalStateException(secretUrl)) },
            player = player,
            ownership = ownership,
        )

        val result = controller.open("mv:unavailable", "hd")

        assertFalse(result.getBoolean("accepted"))
        assertEquals("MV_RESOLVE_FAILED", result.getString("errorCode"))
        assertFalse(result.toString().contains("http", ignoreCase = true))
        assertFalse(result.toString().contains("SECRET", ignoreCase = true))
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
        assertEquals(null, player.currentMediaId)
    }

    @Test
    fun `close invalidates a pending open before it can claim or touch the player`() = runBlocking {
        val resolution = CompletableDeferred<Result<ResolvedMvStream>>()
        val resolverStarted = CompletableDeferred<Unit>()
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, _ ->
                resolverStarted.complete(Unit)
                resolution.await()
            },
            player = player,
            ownership = ownership,
        )

        val opening = async { controller.open("mv:pending", "hd") }
        resolverStarted.await()
        controller.close()
        resolution.complete(Result.success(ResolvedMvStream(secretUrl, "video/mp4", "hd")))

        val result = opening.await()
        assertFalse(result.getBoolean("accepted"))
        assertEquals("MV_OPERATION_SUPERSEDED", result.getString("errorCode"))
        assertEquals(null, player.currentMediaId)
        assertEquals(0, player.prepareCalls)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
    }

    @Test
    fun `close cancels the saved cooperative resolver operation`() = runBlocking {
        val resolverStarted = CompletableDeferred<Unit>()
        val resolverCancelled = CompletableDeferred<Unit>()
        val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, _ ->
                resolverStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    resolverCancelled.complete(Unit)
                }
            },
            player = FakeMvPlayer(),
            ownership = PlaybackOwnership(),
        )

        controller.requestOpen(operationScope, "mv:cooperative", "hd")
        resolverStarted.await()
        controller.close()

        withTimeout(500L) { resolverCancelled.await() }
        operationScope.cancel()
    }

    @Test
    fun `pending open makes quality busy without cancelling open and quality recovers after completion`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val openResolution = CompletableDeferred<Result<ResolvedMvStream>>()
        val qualityStarted = CompletableDeferred<Unit>()
        val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                if (quality == "hd") {
                    firstStarted.complete(Unit)
                    try {
                        openResolution.await()
                    } finally {
                        if (!openResolution.isCompleted) firstCancelled.complete(Unit)
                    }
                } else {
                    qualityStarted.complete(Unit)
                    Result.success(ResolvedMvStream("$secretUrl&quality=$quality", "video/mp4", quality))
                }
            },
            player = FakeMvPlayer(),
            ownership = PlaybackOwnership(),
        )

        controller.requestOpen(operationScope, "mv:first", "hd")
        firstStarted.await()
        val busy = withTimeout(250L) { controller.requestSetQuality(operationScope, "fhd") }

        assertFalse(busy.getBoolean("accepted"))
        assertEquals("MV_RESOLVER_BUSY", busy.getString("errorCode"))
        assertFalse(firstCancelled.isCompleted)
        assertFalse(qualityStarted.isCompleted)
        assertEquals("open", controller.snapshot().getString("operation"))

        openResolution.complete(Result.success(ResolvedMvStream(secretUrl, "video/mp4", "hd")))
        withTimeout(1_000L) {
            while (!controller.isOpen || controller.snapshot().opt("operation") != JSONObject.NULL) yield()
        }

        val quality = controller.requestSetQuality(operationScope, "fhd")
        assertTrue(quality.getBoolean("accepted"))
        assertTrue(quality.getBoolean("pending"))
        withTimeout(500L) { qualityStarted.await() }
        controller.close()
        operationScope.cancel()
    }

    @Test
    fun `recreated controller cannot start while old non cooperative resolver owns process slot`() = runBlocking {
        val admission = MvResolverAdmission()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newStarts = AtomicInteger()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldController = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                oldStarted.countDown()
                while (true) {
                    try {
                        if (releaseOld.await(20, TimeUnit.MILLISECONDS)) break
                    } catch (_: InterruptedException) {
                        // Deliberately non-cooperative: the process slot must remain occupied.
                    }
                }
                Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality))
            },
            player = FakeMvPlayer(),
            ownership = PlaybackOwnership(),
            admission = admission,
            resolverDispatcher = Dispatchers.IO,
        )
        val newPlayer = FakeMvPlayer()
        val newController = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                newStarts.incrementAndGet()
                Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality))
            },
            player = newPlayer,
            ownership = PlaybackOwnership(),
            admission = admission,
        )

        oldController.requestOpen(oldScope, "mv:old", "hd")
        assertTrue(oldStarted.await(1, TimeUnit.SECONDS))
        oldController.destroy()
        val busy = withTimeout(250L) { newController.requestOpen(newScope, "mv:new", "hd") }
        assertEquals("MV_RESOLVER_BUSY", busy.getString("errorCode"))
        assertEquals(0, newStarts.get())
        assertTrue(admission.isBusy)

        releaseOld.countDown()
        withTimeout(1_000L) { while (admission.isBusy) yield() }
        assertTrue(newController.requestOpen(newScope, "mv:new", "hd").getBoolean("pending"))
        withTimeout(1_000L) { while (!newController.isOpen) yield() }
        assertEquals(1, newStarts.get())
        assertEquals("mv:new", newPlayer.currentMediaId)

        newController.destroy()
        oldScope.cancel()
        newScope.cancel()
    }

    @Test
    fun `destroy cancels the saved cooperative resolver operation`() = runBlocking {
        val resolverStarted = CompletableDeferred<Unit>()
        val resolverCancelled = CompletableDeferred<Unit>()
        val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, _ ->
                resolverStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    resolverCancelled.complete(Unit)
                }
            },
            player = FakeMvPlayer(),
            ownership = PlaybackOwnership(),
        )

        controller.requestOpen(operationScope, "mv:destroy", "hd")
        resolverStarted.await()
        controller.destroy()

        withTimeout(500L) { resolverCancelled.await() }
        operationScope.cancel()
    }

    @Test
    fun `quality request returns pending before a blocking resolver prefix completes`() = runBlocking {
        val qualityStarted = CountDownLatch(1)
        val releaseQuality = CountDownLatch(1)
        val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                if (quality == "fhd") {
                    qualityStarted.countDown()
                    check(releaseQuality.await(5, TimeUnit.SECONDS))
                }
                Result.success(ResolvedMvStream("$secretUrl&quality=$quality", "video/mp4", quality))
            },
            player = FakeMvPlayer(),
            ownership = PlaybackOwnership(),
        )
        assertTrue(controller.open("mv:42", "hd").getBoolean("accepted"))

        val switching = async(Dispatchers.Default) {
            controller.requestSetQuality(operationScope, "fhd")
        }
        try {
            assertTrue(qualityStarted.await(1, TimeUnit.SECONDS))
            val response = withTimeout(250L) { switching.await() }
            assertTrue(response.getBoolean("accepted"))
            assertTrue(response.getBoolean("pending"))
        } finally {
            controller.close()
            releaseQuality.countDown()
            operationScope.cancel()
        }
    }

    @Test
    fun `repeated reload and open keeps one resolver active and late result has no side effects`() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val starts = AtomicInteger()
        val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                val nowActive = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, nowActive) }
                val sequence = starts.incrementAndGet()
                try {
                    if (sequence == 1) {
                        firstStarted.countDown()
                        check(releaseFirst.await(5, TimeUnit.SECONDS))
                    }
                    Result.success(ResolvedMvStream("$secretUrl&quality=$quality", "video/mp4", quality))
                } finally {
                    active.decrementAndGet()
                }
            },
            player = player,
            ownership = ownership,
        )
        val gate = MvRouteGate(controller::close)

        repeat(6) { index ->
            gate.observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3A$index")
            controller.requestOpen(operationScope, "mv:$index", "hd")
            if (index == 0) assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            gate.observe(null)
        }
        Thread.sleep(150L)

        assertEquals(1, maximumActive.get())
        assertEquals(1, starts.get())
        releaseFirst.countDown()
        withTimeout(1_000L) {
            while (active.get() != 0) yield()
        }
        assertEquals(0, player.setMediaItemCalls)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
        controller.destroy()
        operationScope.cancel()
    }

    @Test
    fun `close invalidates a pending quality change without replacing the active stream`() = runBlocking {
        val qualityResolution = CompletableDeferred<Result<ResolvedMvStream>>()
        val qualityStarted = CompletableDeferred<Unit>()
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                if (quality == "hd") Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality)) else {
                    qualityStarted.complete(Unit)
                    qualityResolution.await()
                }
            },
            player = player,
            ownership = ownership,
        )
        controller.open("mv:42", "hd")

        val switching = async { controller.setQuality("fhd") }
        qualityStarted.await()
        controller.close()
        qualityResolution.complete(Result.success(ResolvedMvStream("$secretUrl&quality=fhd", "video/mp4", "fhd")))

        val result = switching.await()
        assertEquals("MV_OPERATION_SUPERSEDED", result.getString("errorCode"))
        assertEquals(1, player.setMediaItemCalls)
        assertEquals(null, player.currentMediaId)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
    }

    @Test
    fun `second direct open is busy without starting another resolver and recovers after release`() = runBlocking {
        val resolutions = mapOf(
            "mv:A" to CompletableDeferred<Result<ResolvedMvStream>>(),
            "mv:B" to CompletableDeferred(),
        )
        val starts = mapOf("mv:A" to CompletableDeferred<Unit>(), "mv:B" to CompletableDeferred())
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { id, quality ->
                starts.getValue(id).complete(Unit)
                resolutions.getValue(id).await()
            },
            player = player,
            ownership = ownership,
        )

        val openingA = async { controller.open("mv:A", "hd") }
        starts.getValue("mv:A").await()
        val busy = withTimeout(250L) { controller.open("mv:B", "hd") }
        assertEquals("MV_RESOLVER_BUSY", busy.getString("errorCode"))
        assertFalse(starts.getValue("mv:B").isCompleted)

        resolutions.getValue("mv:A").complete(Result.success(ResolvedMvStream("$secretUrl&A", "video/mp4", "hd")))
        assertTrue(openingA.await().getBoolean("accepted"))
        assertEquals("mv:A", player.currentMediaId)

        resolutions.getValue("mv:B").complete(Result.success(ResolvedMvStream("$secretUrl&B", "video/mp4", "hd")))
        assertTrue(controller.open("mv:B", "hd").getBoolean("accepted"))
        assertTrue(starts.getValue("mv:B").isCompleted)
        assertEquals("mv:B", player.currentMediaId)
        assertEquals(2, player.setMediaItemCalls)
        assertEquals(PlaybackOwner.MV, ownership.value)
    }

    @Test
    fun `destroy invalidates a non cooperative resolver before every player side effect`() = runBlocking {
        val resolution = CompletableDeferred<Result<ResolvedMvStream>>()
        val started = CompletableDeferred<Unit>()
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, _ -> started.complete(Unit); resolution.await() },
            player = player,
            ownership = ownership,
        )

        val opening = async { controller.open("mv:late", "hd") }
        started.await()
        controller.destroy()
        resolution.complete(Result.success(ResolvedMvStream(secretUrl, "video/mp4", "hd")))

        assertEquals("MV_OPERATION_SUPERSEDED", opening.await().getString("errorCode"))
        assertEquals(0, player.setMediaItemCalls)
        assertEquals(0, player.prepareCalls)
        assertEquals(0, player.playCalls)
        assertEquals(1, player.releaseCalls)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
    }

    @Test
    fun `open rejects when music controller cannot confirm pause before MV claim`() = runBlocking {
        val events = mutableListOf<String>()
        val player = FakeMvPlayer(events)
        val ownership = PlaybackOwnership { events += "pause-failed"; false }
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality -> Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality)) },
            player = player,
            ownership = ownership,
        )

        val result = controller.open("mv:42", "hd")

        assertFalse(result.getBoolean("accepted"))
        assertEquals("MV_MUSIC_NOT_READY", result.getString("errorCode"))
        assertEquals(listOf("pause-failed"), events)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
        assertEquals(0, player.setMediaItemCalls)
    }

    @Test
    fun `duplicate open and close are idempotent and return ownership`() = runBlocking {
        var resolutions = 0
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                resolutions += 1
                Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality))
            },
            player = player,
            ownership = ownership,
        )

        assertTrue(controller.open("mv:42", "hd").getBoolean("accepted"))
        assertTrue(controller.open("mv:42", "hd").getBoolean("accepted"))
        controller.close()
        controller.close()

        assertEquals(1, resolutions)
        assertEquals(1, player.stopCalls)
        assertEquals(1, player.clearCalls)
        assertFalse(player.isPlaying)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
    }

    @Test
    fun `quality switch preserves playback position and never exposes the resolved url`() = runBlocking {
        val player = FakeMvPlayer().apply {
            currentPositionMs = 47_000L
            durationMs = 180_000L
        }
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                Result.success(ResolvedMvStream("$secretUrl&quality=$quality", "video/mp4", quality))
            },
            player = player,
            ownership = ownership,
        )
        controller.open("mv:42", "hd")
        player.currentPositionMs = 47_000L

        val result = controller.setQuality("fhd")
        val snapshot = controller.snapshot()

        assertTrue(result.getBoolean("accepted"))
        assertEquals("fhd", result.getString("quality"))
        assertEquals(47_000L, player.currentPositionMs)
        assertTrue(player.isPlaying)
        assertEquals(
            setOf("durationMs", "positionMs", "playing", "quality", "errorCode", "open", "operation"),
            snapshot.keys().asSequence().toSet(),
        )
        assertFalse((result.toString() + snapshot.toString()).contains("http", ignoreCase = true))
        assertFalse((result.toString() + snapshot.toString()).contains("SECRET", ignoreCase = true))
    }

    @Test
    fun `failed quality switch keeps the current stream and sanitized error state`() = runBlocking {
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                if (quality == "hd") Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality))
                else Result.failure(IllegalArgumentException("$secretUrl&quality=$quality"))
            },
            player = player,
            ownership = ownership,
        )
        controller.open("mv:42", "hd")

        val result = controller.setQuality("fhd")

        assertFalse(result.getBoolean("accepted"))
        assertEquals("MV_RESOLVE_FAILED", result.getString("errorCode"))
        assertEquals("hd", controller.snapshot().getString("quality"))
        assertEquals("mv:42", player.currentMediaId)
        assertTrue(player.isPlaying)
        assertFalse(result.toString().contains("http", ignoreCase = true))
    }

    @Test
    fun `every synchronous quality playback failure closes hides and releases ownership`() = runBlocking {
        listOf("set", "prepare", "seek", "play").forEach { failingAction ->
            val visibility = mutableListOf<Boolean>()
            val player = FakeMvPlayer()
            val ownership = PlaybackOwnership()
            val controller = MvPlaybackController(
                resolver = MvStreamResolver { _, quality ->
                    Result.success(ResolvedMvStream("$secretUrl&quality=$quality", "video/mp4", quality))
                },
                player = player,
                ownership = ownership,
                onVisibilityChanged = visibility::add,
            )
            assertTrue(controller.open("mv:42", "hd").getBoolean("accepted"))
            player.throwOnAction = failingAction

            val result = controller.setQuality("fhd")
            val snapshot = controller.snapshot()

            assertFalse("$failingAction must reject", result.getBoolean("accepted"))
            assertEquals("MV_PLAYBACK_FAILED", result.getString("errorCode"))
            assertFalse("$failingAction must close playback", controller.isOpen)
            assertEquals("$failingAction must clear media", null, player.currentMediaId)
            assertFalse("$failingAction must stop playback", player.isPlaying)
            assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
            assertEquals(false, visibility.last())
            assertEquals("MV_PLAYBACK_FAILED", snapshot.getString("errorCode"))
            assertFalse((result.toString() + snapshot.toString()).contains("http", ignoreCase = true))
            controller.destroy()
        }
    }

    @Test
    fun `destroy releases video once and returns audio ownership`() = runBlocking {
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality -> Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality)) },
            player = player,
            ownership = ownership,
        )
        controller.open("mv:42", "hd")

        controller.destroy()
        controller.destroy()

        assertEquals(1, player.releaseCalls)
        assertFalse(player.isPlaying)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
    }

    @Test
    fun `asynchronous player error is sanitized and closes playback before returning ownership`() = runBlocking {
        val player = FakeMvPlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality -> Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality)) },
            player = player,
            ownership = ownership,
        )
        assertTrue(controller.open("mv:42", "hd").getBoolean("accepted"))

        player.emitPlaybackError()
        val snapshot = controller.snapshot()

        assertFalse(controller.isOpen)
        assertFalse(player.isPlaying)
        assertEquals(1, player.stopCalls)
        assertEquals(1, player.clearCalls)
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
        assertEquals("MV_PLAYBACK_FAILED", snapshot.getString("errorCode"))
        assertFalse(snapshot.toString().contains("http", ignoreCase = true))
        assertFalse(snapshot.toString().contains("SECRET", ignoreCase = true))
    }

    @Test
    fun `destroy detaches the asynchronous player error listener`() {
        val player = FakeMvPlayer()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality -> Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality)) },
            player = player,
            ownership = PlaybackOwnership(),
        )

        controller.destroy()

        assertEquals(1, player.listenerDetachCalls)
        assertFalse(player.hasPlaybackErrorListener)
    }

    @Test
    fun `video bridge json contains commands and sanitized snapshots but no url hash or token`() = runBlocking {
        val player = FakeMvPlayer().apply {
            durationMs = 180_000L
            currentPositionMs = 12_000L
        }
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality -> Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality)) },
            player = player,
            ownership = PlaybackOwnership(),
        )
        val gate = MvRouteGate(controller::close).apply {
            observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3A42")
        }
        val router = BridgeRouter(VideoBridgeHandler({ controller }, gate, CoroutineScope(coroutineContext)).handlers())

        val open = request(
            router,
            "video.open",
            JSONObject().put("mvId", "mv:42").put("quality", "hd"),
        )
        repeat(4) { yield() }
        val snapshot = request(router, "video.snapshot", JSONObject())
        val toggle = request(router, "video.toggle", JSONObject())
        val seek = request(router, "video.seekBy", JSONObject().put("deltaMs", 10_000))
        val combined = listOf(open, snapshot, toggle, seek).joinToString()

        assertTrue(open.getJSONObject("payload").getBoolean("accepted"))
        assertEquals(180_000L, snapshot.getJSONObject("payload").getLong("durationMs"))
        assertFalse(combined.contains("http", ignoreCase = true))
        assertFalse(combined.contains("SECRET", ignoreCase = true))
        assertFalse(combined.contains("token", ignoreCase = true))
        assertFalse(combined.contains("hash", ignoreCase = true))
    }

    @Test
    fun `video bridge rejects open outside the verified MV route and keeps exactly six commands`() = runBlocking {
        val player = FakeMvPlayer()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality -> Result.success(ResolvedMvStream(secretUrl, "video/mp4", quality)) },
            player = player,
            ownership = PlaybackOwnership(),
        )
        val gate = MvRouteGate(controller::close)
        val router = BridgeRouter(VideoBridgeHandler({ controller }, gate, CoroutineScope(coroutineContext)).handlers())

        val response = request(
            router,
            "video.open",
            JSONObject().put("mvId", "mv:42").put("quality", "hd").put("route", "mv-detail"),
        )

        assertFalse(response.getBoolean("ok"))
        assertFalse(controller.isOpen)
        assertEquals(0, player.setMediaItemCalls)
        assertEquals(
            setOf("video.open", "video.toggle", "video.seekBy", "video.setQuality", "video.close", "video.snapshot"),
            VideoBridgeHandler.METHODS,
        )
    }

    private suspend fun request(router: BridgeRouter, method: String, payload: JSONObject): JSONObject = JSONObject(
        router.handle(
            JSONObject()
                .put("version", 1)
                .put("type", "request")
                .put("id", "task-6")
                .put("method", method)
                .put("payload", payload)
                .toString(),
        ),
    )

    private class FakeMvPlayer(private val events: MutableList<String> = mutableListOf()) : MvPlayer {
        var throwOnAction: String? = null
        override var currentMediaId: String? = null
            private set
        override var durationMs: Long = 0L
        override var currentPositionMs: Long = 0L
        override var isPlaying: Boolean = false
            private set
        var stopCalls = 0
            private set
        var clearCalls = 0
            private set
        var releaseCalls = 0
            private set
        var setMediaItemCalls = 0
            private set
        var prepareCalls = 0
            private set
        var playCalls = 0
            private set
        private var playbackErrorListener: (() -> Unit)? = null
        var listenerDetachCalls = 0
            private set
        val hasPlaybackErrorListener: Boolean get() = playbackErrorListener != null

        override fun setPlaybackErrorListener(listener: (() -> Unit)?) {
            if (listener == null && playbackErrorListener != null) listenerDetachCalls += 1
            playbackErrorListener = listener
        }

        fun emitPlaybackError() {
            playbackErrorListener?.invoke()
        }

        override fun setMediaItem(mediaId: String, uri: String, mimeType: String) {
            setMediaItemCalls += 1
            currentMediaId = mediaId
            events += "set:$mediaId"
            if (throwOnAction == "set") error("synthetic set failure")
        }

        override fun prepare() {
            prepareCalls += 1
            events += "prepare"
            if (throwOnAction == "prepare") error("synthetic prepare failure")
        }

        override fun play() {
            playCalls += 1
            isPlaying = true
            events += "play"
            if (throwOnAction == "play") error("synthetic play failure")
        }

        override fun pause() {
            isPlaying = false
            events += "pause"
        }

        override fun seekTo(positionMs: Long) {
            currentPositionMs = positionMs
            events += "seek:$positionMs"
            if (throwOnAction == "seek") error("synthetic seek failure")
        }

        override fun stop() {
            stopCalls += 1
            isPlaying = false
            events += "stop"
        }

        override fun clearMediaItems() {
            clearCalls += 1
            currentMediaId = null
            events += "clear"
        }

        override fun release() {
            releaseCalls += 1
            isPlaying = false
            events += "release"
        }
    }
}
