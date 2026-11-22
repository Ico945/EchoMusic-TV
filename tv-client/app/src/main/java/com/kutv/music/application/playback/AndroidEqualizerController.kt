package com.kutv.music.application.playback

import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AudioEqualizerPort {
    val numberOfBands: Short
    val bandLevelRange: ShortArray
    var enabled: Boolean
    fun setBandLevel(band: Short, level: Short)
    fun release()
}

private class AndroidAudioEqualizerPort(
    private val delegate: Equalizer,
) : AudioEqualizerPort {
    override val numberOfBands get() = delegate.numberOfBands
    override val bandLevelRange get() = delegate.bandLevelRange
    override var enabled: Boolean
        get() = delegate.enabled
        set(value) { delegate.enabled = value }
    override fun setBandLevel(band: Short, level: Short) = delegate.setBandLevel(band, level)
    override fun release() = delegate.release()
}

class AndroidEqualizerController(
    private val createEqualizer: (Int) -> Result<AudioEqualizerPort> = { sessionId ->
        runCatching { AndroidAudioEqualizerPort(Equalizer(0, sessionId)) }
    },
) : AudioEffectController {
    private val mutableActivePreset = MutableStateFlow("off")
    override val activePreset: StateFlow<String> = mutableActivePreset.asStateFlow()
    private var equalizer: AudioEqualizerPort? = null
    private var desiredPreset = "off"

    override val supportedPresets: Set<String>
        get() = if ((equalizer?.numberOfBands ?: 0) >= 3.toShort()) PRESETS else setOf("off")

    override fun attach(audioSessionId: Int) {
        releaseEqualizer()
        if (audioSessionId <= 0) {
            mutableActivePreset.value = "off"
            return
        }
        val next = createEqualizer(audioSessionId).getOrElse {
            mutableActivePreset.value = "off"
            return
        }
        equalizer = next
        if (desiredPreset == "off") {
            runCatching { next.enabled = false }.onFailure {
                releaseEqualizer()
                mutableActivePreset.value = "off"
            }
        } else {
            applyPreset(next, desiredPreset).onSuccess {
                mutableActivePreset.value = desiredPreset
            }.onFailure {
                releaseEqualizer()
                mutableActivePreset.value = "off"
            }
        }
    }

    override fun select(preset: String): Result<Unit> {
        if (preset !in PRESETS || preset !in supportedPresets) {
            return Result.failure(UnsupportedOperationException("Unsupported audio preset"))
        }
        return runCatching {
            val effect = equalizer
            if (preset == "off") {
                effect?.enabled = false
            } else {
                checkNotNull(effect)
                applyPreset(effect, preset).getOrThrow()
            }
            desiredPreset = preset
            mutableActivePreset.value = preset
        }.onFailure {
            releaseEqualizer()
            mutableActivePreset.value = "off"
        }
    }

    override fun release() {
        releaseEqualizer()
        desiredPreset = "off"
        mutableActivePreset.value = "off"
    }

    private fun applyPreset(effect: AudioEqualizerPort, preset: String): Result<Unit> = runCatching {
        effect.enabled = true
        setLevels(effect, preset)
    }

    private fun setLevels(effect: AudioEqualizerPort, preset: String) {
        val range = effect.bandLevelRange
        val amount = ((range[1] - range[0]) * 0.55f).toInt().toShort()
        repeat(effect.numberOfBands.toInt()) { index -> effect.setBandLevel(index.toShort(), 0) }
        val last = (effect.numberOfBands - 1).toShort()
        when (preset) {
            "bass" -> effect.setBandLevel(0, amount)
            "vocal" -> effect.setBandLevel((effect.numberOfBands / 2).toShort(), amount)
            "live" -> effect.setBandLevel(last, amount)
        }
    }

    private fun releaseEqualizer() {
        equalizer?.release()
        equalizer = null
    }

    private companion object {
        val PRESETS = setOf("off", "bass", "vocal", "live")
    }
}

object AudioEffectRuntime {
    @Volatile private var controller: AudioEffectController? = null

    fun install(value: AudioEffectController) {
        controller = value
    }

    fun current(): AudioEffectController? = controller

    fun release(value: AudioEffectController) {
        if (controller === value) controller = null
        value.release()
    }
}
