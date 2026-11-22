package com.kutv.music.bridge

import com.kutv.music.application.playback.PlayerSnapshot
import com.kutv.music.application.playback.PlaybackErrorRuntime
import com.kutv.music.application.playback.PlayerCommandError
import com.kutv.music.bridge.mappers.PlayerJsonMapper
import org.json.JSONObject

class BridgeEventSink(
    private val publish: (String) -> Unit,
    private val playerMapper: PlayerJsonMapper = PlayerJsonMapper(),
    private val nextErrorId: () -> Long = PlaybackErrorRuntime::nextErrorId,
) {
    private var lastPlayerSnapshot: PlayerSnapshot? = null
    private var musicPositionEventsEnabled = true

    fun emit(event: String, payload: JSONObject): Boolean {
        val encoded = BridgeEnvelope.event(event, payload).toString()
        return if (encoded.toByteArray(Charsets.UTF_8).size <= BridgeParser.MAX_MESSAGE_BYTES) {
            publish(encoded)
            true
        } else {
            publish(
                BridgeEnvelope.event(
                    "app.error",
                    playerMapper.error(
                        PlayerCommandError(
                            code = "BRIDGE_EVENT_TOO_LARGE",
                            message = "播放器数据过大，已安全截断",
                            retryable = false,
                            errorId = nextErrorId(),
                        ),
                    ),
                ).toString(),
            )
            false
        }
    }

    fun emitPlayerSnapshot(snapshot: PlayerSnapshot) {
        val previous = lastPlayerSnapshot
        if (previous == null) {
            if (emit("player.snapshot", playerMapper.snapshot(snapshot))) lastPlayerSnapshot = snapshot
            return
        }

        val queueChanged = previous.queue != snapshot.queue || previous.queueIndex != snapshot.queueIndex
        val lyricsChanged = previous.lyrics != snapshot.lyrics
        val coreChanged = previous.currentTrack != snapshot.currentTrack ||
            previous.isPlaying != snapshot.isPlaying ||
            previous.repeatMode != snapshot.repeatMode ||
            previous.quality != snapshot.quality ||
            previous.error != snapshot.error
        val positionChanged = previous.positionMs != snapshot.positionMs ||
            previous.durationMs != snapshot.durationMs

        var delivered = true
        if (coreChanged) delivered = emit("player.snapshot", playerMapper.snapshot(snapshot)) && delivered
        if (queueChanged) delivered = emit("queue.changed", playerMapper.queue(snapshot)) && delivered
        if (lyricsChanged) delivered = emit("lyrics.changed", playerMapper.lyrics(snapshot)) && delivered
        if (positionChanged && !coreChanged && !queueChanged && !lyricsChanged && musicPositionEventsEnabled) {
            delivered = emit("player.position", playerMapper.position(snapshot)) && delivered
        }
        if (delivered) lastPlayerSnapshot = snapshot
    }

    fun setMusicPositionEventsEnabled(enabled: Boolean) {
        musicPositionEventsEnabled = enabled
    }
}
