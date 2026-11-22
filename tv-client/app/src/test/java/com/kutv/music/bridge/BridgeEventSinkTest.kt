package com.kutv.music.bridge

import com.kutv.music.application.playback.PlayerSnapshot
import com.kutv.music.bridge.mappers.PlayerJsonMapper
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeEventSinkTest {
    @Test
    fun `player snapshots coalesce position queue and lyric changes`() {
        val messages = mutableListOf<JSONObject>()
        val sink = BridgeEventSink({ messages += JSONObject(it) }, PlayerJsonMapper())
        val trackA = track("a")
        val initial = PlayerSnapshot(currentTrack = trackA, queue = listOf(trackA), queueIndex = 0, durationMs = 100_000)

        sink.emitPlayerSnapshot(initial)
        sink.emitPlayerSnapshot(initial.copy(positionMs = 500))
        sink.emitPlayerSnapshot(initial.copy(positionMs = 500))
        sink.emitPlayerSnapshot(initial.copy(positionMs = 500, queue = listOf(trackA, track("b"))))
        sink.emitPlayerSnapshot(
            initial.copy(
                positionMs = 500,
                queue = listOf(trackA, track("b")),
                lyrics = listOf(TimedLyricLine(0, 1_000, "line")),
            ),
        )

        assertEquals(
            listOf("player.snapshot", "player.position", "queue.changed", "lyrics.changed"),
            messages.map { it.getString("event") },
        )
    }

    @Test
    fun `oversized event emits the exact monotonic app error contract without reflected data`() {
        val messages = mutableListOf<JSONObject>()
        var nextId = 40L
        val sink = BridgeEventSink(
            publish = { messages += JSONObject(it) },
            playerMapper = PlayerJsonMapper(),
            nextErrorId = { ++nextId },
        )

        assertFalse(sink.emit("oversized", JSONObject().put("value", "x".repeat(BridgeParser.MAX_MESSAGE_BYTES))))

        val payload = messages.single().getJSONObject("payload")
        assertEquals("app.error", messages.single().getString("event"))
        assertEquals(setOf("code", "message", "retryable", "errorId"), payload.keys().asSequence().toSet())
        assertEquals(41L, payload.getLong("errorId"))
        assertFalse(payload.getBoolean("retryable"))
        assertFalse(payload.toString().contains("x".repeat(64)))
    }

    @Test
    fun `MV route suppresses music position deltas but retains the latest full snapshot`() {
        val messages = mutableListOf<JSONObject>()
        val sink = BridgeEventSink({ messages += JSONObject(it) }, PlayerJsonMapper())
        val track = track("a")
        val initial = PlayerSnapshot(currentTrack = track, queue = listOf(track), queueIndex = 0)

        sink.emitPlayerSnapshot(initial)
        sink.setMusicPositionEventsEnabled(false)
        sink.emitPlayerSnapshot(initial.copy(positionMs = 500L))
        sink.setMusicPositionEventsEnabled(true)
        sink.emitPlayerSnapshot(initial.copy(positionMs = 1_000L))

        assertEquals(listOf("player.snapshot", "player.position"), messages.map { it.getString("event") })
        assertEquals(1_000L, messages.last().getJSONObject("payload").getLong("positionMs"))
        assertTrue(messages.none { it.getJSONObject("payload").optLong("positionMs", -1L) == 500L })
    }

    private fun track(id: String) = Track(
        id = id,
        title = "Track $id",
        artists = listOf("Artist $id"),
        album = "Album $id",
        artworkUri = "",
        durationMs = 100_000,
        qualities = listOf("128"),
        source = PlaybackSource.Kugou(id, "HASH_$id"),
    )
}
