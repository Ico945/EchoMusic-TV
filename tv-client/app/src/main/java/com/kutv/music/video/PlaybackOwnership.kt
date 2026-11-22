package com.kutv.music.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackOwner { MUSIC_SERVICE, MV }

class PlaybackOwnership(
    private val fallbackPauseMusic: () -> Boolean = { true },
) {
    private val mutable = MutableStateFlow(PlaybackOwner.MUSIC_SERVICE)
    val state: StateFlow<PlaybackOwner> = mutable.asStateFlow()
    val value: PlaybackOwner get() = mutable.value
    val allowsMusicPlayback: Boolean get() = value == PlaybackOwner.MUSIC_SERVICE
    private var mvToken: Any? = null
    private var musicEndpointToken: Any? = null
    private var pauseMusic: () -> Boolean = fallbackPauseMusic

    @Synchronized
    fun attachMusicEndpoint(token: Any, pause: () -> Boolean) {
        musicEndpointToken = token
        pauseMusic = pause
    }

    @Synchronized
    fun detachMusicEndpoint(token: Any) {
        if (musicEndpointToken !== token) return
        musicEndpointToken = null
        pauseMusic = fallbackPauseMusic
    }

    @Synchronized
    fun claimMv(token: Any): Boolean {
        if (mvToken === token) return true
        if (mvToken != null) return false
        if (!runCatching(pauseMusic).getOrDefault(false)) return false
        mvToken = token
        mutable.value = PlaybackOwner.MV
        return true
    }

    @Synchronized
    fun releaseMv(token: Any): Boolean {
        if (mvToken !== token) return false
        mvToken = null
        mutable.value = PlaybackOwner.MUSIC_SERVICE
        return true
    }
}
