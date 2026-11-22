package com.kutv.music.video

import com.kutv.music.bridge.BridgeRouter
import com.kutv.music.bridge.handlers.VideoBridgeHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MvRouteGateTest {
    @Test
    fun `route changes expose MV activity for position polling suppression`() {
        val activity = mutableListOf<Boolean>()
        val gate = MvRouteGate(closeNativeMv = {}, onMvRouteChanged = activity::add)

        gate.observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/home")
        gate.observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3A42")
        gate.observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3A42")
        gate.observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/home")

        assertEquals(listOf(true, false), activity)
    }

    @Test
    fun `payload cannot forge MV route but an observed MV hash permits open`() = runBlocking {
        val player = GatePlayer()
        val controller = controller(player)
        val gate = MvRouteGate(controller::close)
        val router = BridgeRouter(VideoBridgeHandler({ controller }, gate, CoroutineScope(coroutineContext)).handlers())

        val outside = request(router, JSONObject().put("mvId", "mv:42").put("quality", "hd"))
        val forged = request(
            router,
            JSONObject().put("mvId", "mv:42").put("quality", "hd").put("route", "mv-detail"),
        )
        gate.observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3A42?quality=hd")
        val observed = request(router, JSONObject().put("mvId", "mv:42").put("quality", "hd"))
        withTimeout(1_000L) {
            while (player.currentMediaId == null) delay(5L)
        }

        assertFalse(outside.getJSONObject("payload").getBoolean("accepted"))
        assertEquals("MV_ROUTE_REQUIRED", outside.getJSONObject("payload").getString("errorCode"))
        assertFalse(forged.getBoolean("ok"))
        assertTrue(observed.getJSONObject("payload").getBoolean("accepted"))
        assertTrue(observed.getJSONObject("payload").getBoolean("pending"))
        assertEquals("mv:42", player.currentMediaId)
    }

    @Test
    fun `leaving the observed MV route synchronously closes native playback`() = runBlocking {
        val visibility = mutableListOf<Boolean>()
        val player = GatePlayer()
        val ownership = PlaybackOwnership()
        val controller = MvPlaybackController(
            resolver = MvStreamResolver { _, quality ->
                Result.success(ResolvedMvStream("https://media.example.invalid/synthetic.mp4", "video/mp4", quality))
            },
            player = player,
            ownership = ownership,
            onVisibilityChanged = visibility::add,
        )
        val gate = MvRouteGate(controller::close)
        gate.observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3A42")
        controller.open("mv:42", "hd")
        assertTrue(controller.isOpen)

        gate.observe("https://appassets.androidplatform.net/assets/tv-ui/tv.html#/home")

        assertFalse(gate.allowsMvOpen)
        assertFalse(controller.isOpen)
        assertEquals(null, player.currentMediaId)
        assertEquals(false, visibility.last())
        assertEquals(PlaybackOwner.MUSIC_SERVICE, ownership.value)
    }

    @Test
    fun `route authority rejects user info explicit ports and non exact raw authority`() {
        listOf(
            "https://viewer@appassets.androidplatform.net/assets/tv-ui/tv.html#/mv/mv%3A42",
            "https://appassets.androidplatform.net:443/assets/tv-ui/tv.html#/mv/mv%3A42",
            "https://appassets.androidplatform.net./assets/tv-ui/tv.html#/mv/mv%3A42",
        ).forEach { url ->
            val gate = MvRouteGate {}

            gate.observe(url)

            assertFalse(url, gate.allowsMvOpen)
        }
    }

    private fun controller(player: GatePlayer) = MvPlaybackController(
        resolver = MvStreamResolver { _, quality ->
            Result.success(ResolvedMvStream("https://media.example.invalid/synthetic.mp4", "video/mp4", quality))
        },
        player = player,
        ownership = PlaybackOwnership(),
    )

    private suspend fun request(router: BridgeRouter, payload: JSONObject): JSONObject = JSONObject(
        router.handle(
            JSONObject()
                .put("version", 1)
                .put("type", "request")
                .put("id", "route-gate")
                .put("method", "video.open")
                .put("payload", payload)
                .toString(),
        ),
    )

    private class GatePlayer : MvPlayer {
        @Volatile
        override var currentMediaId: String? = null
        override val durationMs = 0L
        override val currentPositionMs = 0L
        override var isPlaying = false
        override fun setMediaItem(mediaId: String, uri: String, mimeType: String) { currentMediaId = mediaId }
        override fun prepare() = Unit
        override fun play() { isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun seekTo(positionMs: Long) = Unit
        override fun stop() { isPlaying = false }
        override fun clearMediaItems() { currentMediaId = null }
        override fun setPlaybackErrorListener(listener: (() -> Unit)?) = Unit
        override fun release() = Unit
    }
}
