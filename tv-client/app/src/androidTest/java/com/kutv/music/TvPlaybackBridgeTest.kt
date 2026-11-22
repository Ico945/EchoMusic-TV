package com.kutv.music

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kutv.music.application.playback.AndroidEqualizerController
import com.kutv.music.application.playback.AudioEffectController
import com.kutv.music.application.playback.LyricsRepository
import com.kutv.music.application.playback.PlayerPort
import com.kutv.music.application.playback.PlayerPortEvent
import com.kutv.music.application.playback.PlayerPortSnapshot
import com.kutv.music.application.playback.PlaybackControllerFacade
import com.kutv.music.application.playback.QueueCommands
import com.kutv.music.bridge.BridgeRouter
import com.kutv.music.bridge.handlers.PlayerBridgeHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvPlaybackBridgeTest {
    @Test
    fun realEqualizerWrapperReappliesOrExplicitlyRefusesTheCurrentSession() {
        val sampleRate = 44_100
        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4_096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(minimumBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val effects = AndroidEqualizerController()
        try {
            effects.attach(track.audioSessionId)
            if ("bass" in effects.supportedPresets) {
                assertTrue(effects.select("bass").isSuccess)
                effects.attach(track.audioSessionId)
                assertEquals("bass", effects.activePreset.value)
            } else {
                assertFalse(effects.select("bass").isSuccess)
                assertEquals("off", effects.activePreset.value)
            }
        } finally {
            effects.release()
            track.release()
        }
    }

    @Test
    fun soundEffectBridgeReportsSupportAndRefusesUnsupportedPresets() = runBlocking {
        val effects = RecordingEffects(setOf("off", "bass"))
        val facade = PlaybackControllerFacade(
            SilentPlayerPort(), SilentQueueCommands(), LyricsRepository { emptyList() }, this, audioEffects = { effects },
        )
        val router = BridgeRouter(PlayerBridgeHandler { facade }.handlers())

        assertTrue(request(router, "bass").getJSONObject("payload").getBoolean("accepted"))
        assertFalse(request(router, "vocal").getJSONObject("payload").getBoolean("accepted"))
        assertEquals(listOf("bass"), effects.selected)
        assertEquals("SOUND_EFFECT_UNAVAILABLE", facade.state.value.error?.code)
        facade.close()
    }

    private suspend fun request(router: BridgeRouter, preset: String): JSONObject = JSONObject(router.handle(
        JSONObject().put("version", 1).put("type", "request").put("id", "task-5")
            .put("method", "player.setSoundEffect").put("payload", JSONObject().put("preset", preset)).toString(),
    ))

    private class RecordingEffects(override val supportedPresets: Set<String>) : AudioEffectController {
        private val state = MutableStateFlow("off")
        override val activePreset: StateFlow<String> = state
        val selected = mutableListOf<String>()
        override fun attach(audioSessionId: Int) = Unit
        override fun select(preset: String): Result<Unit> = if (preset in supportedPresets) {
            selected += preset
            state.value = preset
            Result.success(Unit)
        } else Result.failure(UnsupportedOperationException(preset))
        override fun release() = Unit
    }

    private class SilentPlayerPort : PlayerPort {
        override val events: Flow<PlayerPortEvent> = emptyFlow()
        override val snapshot = PlayerPortSnapshot()
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekToNext() = Unit
        override fun seekToPrevious() = Unit
        override fun setRepeatMode(mode: Int) = Unit
    }

    private class SilentQueueCommands : QueueCommands {
        override suspend fun playQueue(trackIds: List<String>, startIndex: Int) = Unit
        override suspend fun playNext(trackId: String) = Unit
        override suspend fun select(trackId: String) = Unit
    }
}
