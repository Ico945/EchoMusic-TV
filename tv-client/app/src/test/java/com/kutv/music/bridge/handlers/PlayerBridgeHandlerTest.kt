package com.kutv.music.bridge.handlers

import com.kutv.music.application.playback.FakeLyricsRepository
import com.kutv.music.application.playback.FakePlayerPort
import com.kutv.music.application.playback.FakeQueueCommands
import com.kutv.music.application.playback.PlaybackControllerFacade
import com.kutv.music.application.playback.PlaybackErrorStore
import com.kutv.music.application.playback.MonotonicErrorIdentity
import com.kutv.music.application.playback.PlayerCommandErrors
import com.kutv.music.bridge.BridgeRouter
import com.kutv.music.bridge.mappers.BridgeTrackIds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBridgeHandlerTest {
    @Test fun `absolute TV seek validates payload and rejects a stale track`() = runBlocking {
        val fixture = fixture(this)
        try {
            fixture.player.emitTrack("t1", "TV seek", 180_000)
            fun payload(id: String = "t1", position: Any = 0) = JSONObject()
                .put("trackId", BridgeTrackIds.publicId(id)).put("positionMs", position)
            for (invalid in listOf(payload(position = -1), payload(position = 1.5), payload(position = "100"), payload().put("extra", true))) {
                assertEquals("BRIDGE_PAYLOAD_INVALID", request(fixture.router, "player.seekTo", invalid).getJSONObject("error").getString("code"))
            }
            assertTrue(fixture.player.seekPositions.isEmpty())
            assertFalse(request(fixture.router, "player.seekTo", payload("t2", 10_000)).getJSONObject("payload").getBoolean("accepted"))
            assertTrue(request(fixture.router, "player.seekTo", payload(position = 90_000)).getJSONObject("payload").getBoolean("accepted"))
            assertTrue(request(fixture.router, "player.seekTo", payload(position = 999_999)).getJSONObject("payload").getBoolean("accepted"))
            assertEquals(listOf(90_000L, 180_000L), fixture.player.seekPositions)
        } finally { fixture.close() }
    }

    @Test fun `absolute TV seek validates before facade readiness`() = runBlocking {
        val router = BridgeRouter(PlayerBridgeHandler { null }.handlers())
        val payload = JSONObject().put("trackId", BridgeTrackIds.publicId("t1")).put("positionMs", 0)
        assertFalse(request(router, "player.seekTo", payload).getJSONObject("payload").getBoolean("accepted"))
        payload.put("positionMs", -1)
        assertEquals("BRIDGE_PAYLOAD_INVALID", request(router, "player.seekTo", payload).getJSONObject("error").getString("code"))
    }
    @Test
    fun `seek rejects a delta outside exact ten second contract`() = runBlocking {
        val fixture = fixture(this)
        val response = request(fixture.router, "player.seekBy", JSONObject().put("deltaMs", 25_000))

        assertFalse(response.getBoolean("ok"))
        assertEquals("BRIDGE_PAYLOAD_INVALID", response.getJSONObject("error").getString("code"))
        assertTrue(fixture.player.seekPositions.isEmpty())
        fixture.close()
    }

    @Test
    fun `play queue accepts domain ids without returning media urls`() = runBlocking {
        val fixture = fixture(this)
        val firstId = BridgeTrackIds.publicId("t1")
        val secondId = BridgeTrackIds.publicId("t2")
        val response = request(
            fixture.router,
            "player.playQueue",
            JSONObject().put("trackIds", org.json.JSONArray(listOf(firstId, secondId))).put("startIndex", 1),
        )

        assertTrue(response.getBoolean("ok"))
        assertTrue(response.getJSONObject("payload").getBoolean("accepted"))
        assertFalse(response.toString().contains("http", ignoreCase = true))
        assertEquals(
            listOf(FakeQueueCommands.QueueRequest(listOf("t1", "t2"), 1)),
            fixture.queue.playQueueRequests,
        )
        fixture.close()
    }

    @Test
    fun `command payloads reject unknown fields and blank ids`() = runBlocking {
        val fixture = fixture(this)

        val unknown = request(
            fixture.router,
            "player.toggle",
            JSONObject().put("unexpected", true),
        )
        val blank = request(
            fixture.router,
            "player.playNext",
            JSONObject().put("trackId", " "),
        )

        assertEquals("BRIDGE_PAYLOAD_INVALID", unknown.getJSONObject("error").getString("code"))
        assertEquals("BRIDGE_PAYLOAD_INVALID", blank.getJSONObject("error").getString("code"))
        fixture.close()
    }

    @Test
    fun `payload validation is stable while facade is unavailable`() = runBlocking {
        val router = BridgeRouter(PlayerBridgeHandler { null }.handlers())

        val invalid = request(router, "player.seekBy", JSONObject().put("deltaMs", 25_000))
        val valid = request(router, "player.toggle", JSONObject())

        assertEquals("BRIDGE_PAYLOAD_INVALID", invalid.getJSONObject("error").getString("code"))
        assertTrue(valid.getBoolean("ok"))
        assertFalse(valid.getJSONObject("payload").getBoolean("accepted"))
    }

    @Test
    fun `retry current is a registered payload free Android command`() = runBlocking {
        val fixture = fixture(this)

        val response = request(fixture.router, "player.retryCurrent", JSONObject())

        assertTrue(response.getBoolean("ok"))
        assertTrue(response.getJSONObject("payload").getBoolean("accepted"))
        assertEquals(1, fixture.queue.retryCurrentCalls)
        fixture.close()
    }

    @Test
    fun `clear error requires the current identity and returns a monotonic native revision`() = runBlocking {
        val store = PlaybackErrorStore(MonotonicErrorIdentity())
        val error = store.report(PlayerCommandErrors.failed())
        val facade = PlaybackControllerFacade(
            FakePlayerPort(), FakeQueueCommands(), FakeLyricsRepository(), this, errorStore = store,
        )
        val router = BridgeRouter(PlayerBridgeHandler { facade }.handlers())

        val cleared = request(router, "player.clearError", JSONObject().put("errorId", error.errorId))
        val newer = store.report(PlayerCommandErrors.invalid())
        val stale = request(router, "player.clearError", JSONObject().put("errorId", error.errorId))

        assertEquals(
            setOf("accepted", "errorRevision"),
            cleared.getJSONObject("payload").keys().asSequence().toSet(),
        )
        assertTrue(cleared.getJSONObject("payload").getBoolean("accepted"))
        assertTrue(cleared.getJSONObject("payload").getLong("errorRevision") > error.errorId)
        assertFalse(stale.getJSONObject("payload").getBoolean("accepted"))
        assertEquals(newer.errorId, stale.getJSONObject("payload").getLong("errorRevision"))
        assertEquals(newer, store.state.value.error)
        facade.close()
    }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val player = FakePlayerPort()
        val queue = FakeQueueCommands()
        val facade = PlaybackControllerFacade(player, queue, FakeLyricsRepository(), scope)
        val handler = PlayerBridgeHandler { facade }
        return Fixture(player, queue, facade, BridgeRouter(handler.handlers()))
    }

    private suspend fun request(router: BridgeRouter, method: String, payload: JSONObject): JSONObject =
        JSONObject(
            router.handle(
                JSONObject()
                    .put("version", 1)
                    .put("type", "request")
                    .put("id", "request-1")
                    .put("method", method)
                    .put("payload", payload)
                    .toString(),
            ),
        )

    private data class Fixture(
        val player: FakePlayerPort,
        val queue: FakeQueueCommands,
        val facade: PlaybackControllerFacade,
        val router: BridgeRouter,
    ) {
        fun close() = facade.close()
    }
}
