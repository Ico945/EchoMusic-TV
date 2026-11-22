package com.kutv.music.application.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

interface PcmRecorder {
    fun start()
    fun stop(): ByteArray
}

/**
 * Captures at most [durationSeconds] of 8000 Hz mono PCM_16BIT audio into a
 * fixed in-memory buffer. This recorder never requests the RECORD_AUDIO
 * permission; permission acquisition is owned by the use-case layer. Start and
 * read failures surface as [IllegalStateException] so the use case can project
 * them as a terminal error state.
 */
class AndroidPcmRecorder(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    private val permissionGranted: () -> Boolean = { false },
) : PcmRecorder {
    private var audioRecord: AudioRecord? = null
    private val bufferSize: Int = sampleRate * durationSeconds * BYTES_PER_SAMPLE
    private val buffer = ByteArray(bufferSize)

    // RECORD_AUDIO 由 RecognitionUseCase 在 start 前经运行时权限流程取得；
    // 这里再做一次显式守卫（双保险），权限缺失时快速失败而非抛 SecurityException。
    @SuppressLint("MissingPermission")
    override fun start() {
        check(audioRecord == null) { "录音已在进行" }
        check(permissionGranted()) { "麦克风权限未授予" }
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            maxOf(minBuffer, bufferSize),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("录音初始化失败")
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException("录音启动失败")
        }
        audioRecord = record
    }

    override fun stop(): ByteArray {
        val record = audioRecord ?: return ByteArray(0)
        audioRecord = null
        var offset = 0
        while (offset < buffer.size) {
            val count = record.read(buffer, offset, buffer.size - offset)
            if (count < 0) break
            offset += count
        }
        runCatching { record.stop() }
        record.release()
        return buffer.copyOf(offset)
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 8000
        const val DEFAULT_DURATION_SECONDS = 10
        const val BYTES_PER_SAMPLE = 2
    }
}
