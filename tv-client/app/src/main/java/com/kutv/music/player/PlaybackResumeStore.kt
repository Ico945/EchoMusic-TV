package com.kutv.music.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kutv.music.core.model.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackResumeState(
    val queue: List<QueueTrack>,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val savedAtEpochMs: Long,
)

interface PlaybackResumeStore {
    fun load(): PlaybackResumeState?
    fun save(state: PlaybackResumeState)
    fun clear()
}

// Position snapshots must be durable before a process can be reclaimed. Writes
// already run on the dedicated IO writer, and the final service flush relies on
// synchronous commit ordering; apply() would weaken that guarantee.
@SuppressLint("ApplySharedPref", "UseKtx")
class EncryptedPlaybackResumeStore(context: Context) : PlaybackResumeStore {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): PlaybackResumeState? {
        val raw = preferences.getString(KEY, null) ?: return null
        return PlaybackResumeCodec.decode(raw).also { decoded ->
            if (decoded == null) preferences.edit().remove(KEY).commit()
        }
    }

    override fun save(state: PlaybackResumeState) {
        check(preferences.edit().putString(KEY, PlaybackResumeCodec.encode(state)).commit()) {
            "Unable to persist playback resume state"
        }
    }

    override fun clear() {
        check(preferences.edit().remove(KEY).commit()) {
            "Unable to clear playback resume state"
        }
    }

    private companion object {
        const val FILE = "kutv_playback_resume"
        const val KEY = "playback.resume.v1"
    }
}

/**
 * Serializes writes on an IO scope and conflates high-frequency position updates.
 * The payload never contains a resolved media URL, KuGou token or account value.
 */
internal class PlaybackResumeWriter(
    private val store: PlaybackResumeStore,
    scope: CoroutineScope,
    private val onFailure: () -> Unit = {},
) {
    private val updates = Channel<PlaybackResumeState?>(Channel.CONFLATED)
    private val writeLock = Any()
    @Volatile
    private var closed = false
    private val job = scope.launch {
        for (state in updates) {
            synchronized(writeLock) {
                if (!closed) write(state)
            }
        }
    }

    fun submit(state: PlaybackResumeState?) {
        if (!closed) updates.trySend(state)
    }

    fun closeAndSave(finalState: PlaybackResumeState?) {
        synchronized(writeLock) {
            if (closed) return
            closed = true
            write(finalState)
            updates.close()
            job.cancel()
        }
    }

    private fun write(state: PlaybackResumeState?) {
        runCatching {
            if (state == null) store.clear() else store.save(state)
        }.onFailure { onFailure() }
    }
}

internal object PlaybackResumeCodec {
    private const val VERSION = 1
    private const val MAX_RAW_LENGTH = 1_000_000
    private const val MAX_QUEUE_ITEMS = 500
    private const val MAX_ID_LENGTH = 512
    private const val MAX_TEXT_LENGTH = 512
    private const val MAX_URI_LENGTH = 4096
    private const val MAX_ARTISTS = 16
    private const val MAX_QUALITIES = 16

    fun encode(state: PlaybackResumeState): String {
        validate(state)
        return JSONObject().apply {
            put("version", VERSION)
            put("currentIndex", state.currentIndex)
            put("positionMs", state.positionMs)
            put("repeatMode", state.repeatMode)
            put("savedAtEpochMs", state.savedAtEpochMs)
            put("queue", JSONArray().apply {
                state.queue.forEach { put(encodeTrack(it)) }
            })
        }.toString()
    }

    fun decode(raw: String): PlaybackResumeState? = runCatching {
        require(raw.length <= MAX_RAW_LENGTH)
        val json = JSONObject(raw)
        require(json.getInt("version") == VERSION)
        val queueJson = json.getJSONArray("queue")
        require(queueJson.length() in 1..MAX_QUEUE_ITEMS)
        val queue = (0 until queueJson.length()).map { index ->
            decodeTrack(queueJson.getJSONObject(index))
        }
        PlaybackResumeState(
            queue = queue,
            currentIndex = json.getInt("currentIndex"),
            positionMs = json.getLong("positionMs"),
            repeatMode = json.getInt("repeatMode"),
            savedAtEpochMs = json.getLong("savedAtEpochMs"),
        ).also(::validate)
    }.getOrNull()

    private fun encodeTrack(track: QueueTrack): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("title", track.title)
        put("artists", JSONArray(track.artists))
        put("artworkUrl", track.artworkUrl)
        put("album", track.album)
        put("durationMs", track.durationMs)
        put("qualities", JSONArray(track.qualities))
        put("source", when (val source = track.source) {
            is PlaybackSource.Local -> JSONObject().apply {
                put("kind", QueueMetadataKeys.SOURCE_LOCAL)
                put("uri", source.uri)
            }
            is PlaybackSource.Kugou -> JSONObject().apply {
                put("kind", QueueMetadataKeys.SOURCE_KUGOU)
                put("trackId", source.trackId)
                put("hash", source.hash)
                put("albumId", source.albumId ?: JSONObject.NULL)
                put("lyricId", source.lyricId ?: JSONObject.NULL)
                put("preferredQuality", source.preferredQuality)
            }
        })
    }

    private fun decodeTrack(json: JSONObject): QueueTrack {
        val sourceJson = json.getJSONObject("source")
        val source = when (sourceJson.getString("kind")) {
            QueueMetadataKeys.SOURCE_LOCAL -> PlaybackSource.Local(
                sourceJson.requiredText("uri", MAX_URI_LENGTH),
            )
            QueueMetadataKeys.SOURCE_KUGOU -> PlaybackSource.Kugou(
                trackId = sourceJson.requiredText("trackId", MAX_ID_LENGTH),
                hash = sourceJson.requiredText("hash", MAX_ID_LENGTH),
                albumId = sourceJson.optionalText("albumId", MAX_ID_LENGTH),
                lyricId = sourceJson.optionalText("lyricId", MAX_ID_LENGTH),
                preferredQuality = sourceJson.requiredText("preferredQuality", 32),
            )
            else -> error("Unknown playback source")
        }
        return QueueTrack(
            id = json.requiredText("id", MAX_ID_LENGTH),
            title = json.boundedText("title", MAX_TEXT_LENGTH),
            artists = json.stringList("artists", MAX_ARTISTS, MAX_TEXT_LENGTH),
            artworkUrl = json.boundedText("artworkUrl", MAX_URI_LENGTH),
            album = json.boundedText("album", MAX_TEXT_LENGTH),
            durationMs = json.getLong("durationMs"),
            qualities = json.stringList("qualities", MAX_QUALITIES, 32),
            source = source,
        )
    }

    private fun validate(state: PlaybackResumeState) {
        require(state.queue.size in 1..MAX_QUEUE_ITEMS)
        require(state.currentIndex in state.queue.indices)
        require(state.positionMs >= 0L)
        require(state.repeatMode in 0..2)
        require(state.savedAtEpochMs >= 0L)
        state.queue.forEach { track ->
            require(track.id.isNotBlank() && track.id.length <= MAX_ID_LENGTH)
            require(track.title.length <= MAX_TEXT_LENGTH)
            require(track.artists.size <= MAX_ARTISTS && track.artists.all { it.length <= MAX_TEXT_LENGTH })
            require(track.artworkUrl.length <= MAX_URI_LENGTH)
            require(track.album.length <= MAX_TEXT_LENGTH)
            require(track.durationMs >= 0L)
            require(track.qualities.size <= MAX_QUALITIES && track.qualities.all { it.length <= 32 })
            when (val source = track.source) {
                is PlaybackSource.Local -> require(source.uri.isNotBlank() && source.uri.length <= MAX_URI_LENGTH)
                is PlaybackSource.Kugou -> {
                    require(source.trackId.isNotBlank() && source.trackId.length <= MAX_ID_LENGTH)
                    require(source.hash.isNotBlank() && source.hash.length <= MAX_ID_LENGTH)
                    require(source.albumId == null || source.albumId.length <= MAX_ID_LENGTH)
                    require(source.lyricId == null || source.lyricId.length <= MAX_ID_LENGTH)
                    require(source.preferredQuality.isNotBlank() && source.preferredQuality.length <= 32)
                }
            }
        }
    }

    private fun JSONObject.requiredText(key: String, maxLength: Int): String =
        getString(key).also { require(it.isNotBlank() && it.length <= maxLength) }

    private fun JSONObject.boundedText(key: String, maxLength: Int): String =
        getString(key).also { require(it.length <= maxLength) }

    private fun JSONObject.optionalText(key: String, maxLength: Int): String? =
        opt(key).takeUnless { it == null || it == JSONObject.NULL }
            ?.toString()
            ?.also { require(it.isNotBlank() && it.length <= maxLength) }

    private fun JSONObject.stringList(key: String, maxItems: Int, maxLength: Int): List<String> {
        val values = getJSONArray(key)
        require(values.length() <= maxItems)
        return (0 until values.length()).map { index ->
            values.getString(index).also { require(it.length <= maxLength) }
        }
    }
}
