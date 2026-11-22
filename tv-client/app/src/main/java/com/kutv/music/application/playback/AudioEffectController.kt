package com.kutv.music.application.playback

import kotlinx.coroutines.flow.StateFlow

interface AudioEffectController {
    val supportedPresets: Set<String>
    val activePreset: StateFlow<String>
    fun attach(audioSessionId: Int)
    fun select(preset: String): Result<Unit>
    fun release()
}
