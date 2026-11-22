package com.kutv.music.application.playback

import androidx.media3.common.Player
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track

data class PlayerSnapshot(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val quality: String = "",
    val lyrics: List<TimedLyricLine> = emptyList(),
    val error: PlayerCommandError? = null,
    val errorRevision: Long = 0L,
    val buffering: Boolean = false,
)
