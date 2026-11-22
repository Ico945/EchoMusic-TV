package com.kutv.music.application.playback

import androidx.media3.common.Player
import com.kutv.music.core.model.Track
import kotlinx.coroutines.flow.Flow

interface PlayerPort : AutoCloseable {
    val events: Flow<PlayerPortEvent>
    val snapshot: PlayerPortSnapshot

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekToNext()
    fun seekToPrevious()
    fun setRepeatMode(mode: Int)

    override fun close() = Unit
}

data class PlayerPortSnapshot(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val quality: String = "",
    val buffering: Boolean = false,
)

sealed interface PlayerPortEvent {
    data class Snapshot(val value: PlayerPortSnapshot) : PlayerPortEvent
    data class AudioSessionChanged(val audioSessionId: Int) : PlayerPortEvent
}
