package com.kutv.music.bridge.mappers

import com.kutv.music.application.playback.PlayerCommandError
import com.kutv.music.application.playback.PlayerSnapshot
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import com.kutv.music.bridge.mappers.BridgeTrackIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerJsonMapperTest {
    @Test
    fun `snapshot omits playback source credentials and disallowed artwork urls`() {
        val mapper = PlayerJsonMapper()
        val snapshot = PlayerSnapshot(
            currentTrack = track("a", "https://imge.kugou.com/400/a.jpg"),
            queue = listOf(
                track("a", "https://imge.kugou.com/400/a.jpg"),
                track("b", "https://evil.example/private.jpg?token=secret"),
            ),
            queueIndex = 0,
            quality = "320",
            error = PlayerCommandError("PLAYBACK_FAILED", "播放失败", true, 1L),
            errorRevision = 1L,
        )

        val json = mapper.snapshot(snapshot)
        val encoded = json.toString()

        assertEquals("https://imge.kugou.com/400/a.jpg", json.getJSONObject("currentTrack").getString("artworkUrl"))
        assertFalse(json.getJSONArray("queue").getJSONObject(1).has("artworkUrl"))
        assertFalse(encoded.contains("HASH_"))
        assertFalse(encoded.contains("source", ignoreCase = true))
        assertFalse(encoded.contains("evil.example"))
        assertFalse(encoded.contains("token", ignoreCase = true))
        assertEquals(1L, json.getLong("errorRevision"))
        assertEquals(
            setOf("code", "message", "retryable", "errorId"),
            json.getJSONObject("error").keys().asSequence().toSet(),
        )
    }

    @Test
    fun `queue lyrics and position payloads contain only projection fields`() {
        val mapper = PlayerJsonMapper()
        val snapshot = PlayerSnapshot(
            queue = listOf(track("a", "")),
            queueIndex = 0,
            positionMs = 10_000,
            durationMs = 180_000,
            lyrics = listOf(TimedLyricLine(0, 1_000, "第一句")),
        )

        assertEquals(10_000L, mapper.position(snapshot).getLong("positionMs"))
        assertEquals(
            BridgeTrackIds.publicId("a"),
            mapper.queue(snapshot).getJSONArray("queue").getJSONObject(0).getString("id"),
        )
        assertEquals("第一句", mapper.lyrics(snapshot).getJSONArray("lyrics").getJSONObject(0).getString("text"))
        assertTrue(mapper.snapshot(snapshot).toString().length < 16_384)
    }

    private fun track(id: String, artwork: String) = Track(
        id = id,
        title = "Track $id",
        artists = listOf("Artist $id"),
        album = "Album $id",
        artworkUri = artwork,
        durationMs = 180_000,
        qualities = listOf("128", "320"),
        source = PlaybackSource.Kugou(id, "HASH_$id", preferredQuality = "320"),
    )
}
