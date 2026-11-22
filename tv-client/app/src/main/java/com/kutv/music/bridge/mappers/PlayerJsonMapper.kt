package com.kutv.music.bridge.mappers

import androidx.media3.common.Player
import com.kutv.music.application.playback.PlayerSnapshot
import com.kutv.music.application.playback.PlayerCommandError
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import com.kutv.music.web.ArtworkUrlPolicy
import org.json.JSONArray
import org.json.JSONObject

class PlayerJsonMapper {
    fun snapshot(snapshot: PlayerSnapshot): JSONObject {
        val queue = projectedQueue(snapshot)
        return JSONObject()
        .put("currentTrack", snapshot.currentTrack?.let(::track) ?: JSONObject.NULL)
        .put("isPlaying", snapshot.isPlaying)
        .apply { if (snapshot.buffering) put("buffering", true) }
        .put("positionMs", snapshot.positionMs)
        .put("durationMs", snapshot.durationMs)
        .put("queue", tracks(queue.tracks))
        .put("queueIndex", queue.index)
        .put("repeatMode", repeatMode(snapshot.repeatMode))
        .put("quality", snapshot.quality)
        .put("lyrics", lyricLines(snapshot.lyrics))
        .put("errorRevision", snapshot.errorRevision)
        .put(
            "error",
            snapshot.error?.let(::error) ?: JSONObject.NULL,
        )
    }

    fun error(value: PlayerCommandError): JSONObject = JSONObject()
        .put("code", value.code)
        .put("message", value.message.take(MAX_TEXT))
        .put("retryable", value.retryable)
        .put("errorId", value.errorId)

    fun position(snapshot: PlayerSnapshot): JSONObject = JSONObject()
        .put("positionMs", snapshot.positionMs)
        .put("durationMs", snapshot.durationMs)

    fun queue(snapshot: PlayerSnapshot): JSONObject {
        val queue = projectedQueue(snapshot)
        return JSONObject()
            .put("queue", tracks(queue.tracks))
            .put("queueIndex", queue.index)
    }

    fun lyrics(snapshot: PlayerSnapshot): JSONObject = JSONObject()
        .put("lyrics", lyricLines(snapshot.lyrics))

    private fun tracks(values: List<Track>): JSONArray = JSONArray().also { array ->
        values.forEach { array.put(track(it)) }
    }

    private fun projectedQueue(snapshot: PlayerSnapshot): ProjectedQueue {
        if (snapshot.queue.size <= MAX_QUEUE_ITEMS) return ProjectedQueue(snapshot.queue, snapshot.queueIndex)
        val centeredStart = snapshot.queueIndex - MAX_QUEUE_ITEMS / 2
        val start = centeredStart.coerceIn(0, snapshot.queue.size - MAX_QUEUE_ITEMS)
        return ProjectedQueue(
            tracks = snapshot.queue.subList(start, start + MAX_QUEUE_ITEMS),
            index = snapshot.queueIndex.takeIf { it in snapshot.queue.indices }?.minus(start) ?: -1,
        )
    }

    private fun track(value: Track): JSONObject = JSONObject()
        .put("id", BridgeTrackIds.publicId(value.id))
        .put("title", value.title.take(MAX_TEXT))
        .put("artists", JSONArray(value.artists.take(MAX_ARTISTS).map { it.take(MAX_SHORT_TEXT) }))
        .put("album", value.album.take(MAX_TEXT))
        .put("durationMs", value.durationMs)
        .put("qualities", JSONArray(value.qualities.take(MAX_QUALITIES).map { it.take(MAX_SHORT_TEXT) }))
        .apply {
            ArtworkUrlPolicy.normalize(value.artworkUri)?.let { put("artworkUrl", it) }
        }

    private fun lyricLines(values: List<TimedLyricLine>): JSONArray = JSONArray().also { array ->
        values.take(MAX_LYRIC_LINES).forEach { line ->
            array.put(
                JSONObject()
                    .put("startMs", line.startMs)
                    .put("endMs", line.endMs)
                    .put("text", line.text.take(MAX_LYRIC_TEXT)),
            )
        }
    }

    private fun repeatMode(value: Int): String = when (value) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    private companion object {
        const val MAX_QUEUE_ITEMS = 200
        const val MAX_LYRIC_LINES = 500
        const val MAX_ARTISTS = 12
        const val MAX_QUALITIES = 16
        const val MAX_TEXT = 256
        const val MAX_SHORT_TEXT = 64
        const val MAX_LYRIC_TEXT = 256
    }

    private data class ProjectedQueue(val tracks: List<Track>, val index: Int)

}
