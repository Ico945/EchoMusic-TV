package com.kutv.music.player

import com.kutv.music.video.PlaybackOwnership

object MusicSessionCommandPolicy {
    fun allowsPlayerCommand(ownership: PlaybackOwnership): Boolean = ownership.allowsMusicPlayback
    fun consumesMediaButton(ownership: PlaybackOwnership): Boolean = !ownership.allowsMusicPlayback
}
