package com.kutv.music.application.playback

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

class FakePlayerPort : PlayerPort {
    private val mutableEvents = MutableSharedFlow<PlayerPortEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlayerPortEvent> = mutableEvents

    private var backingSnapshot = PlayerPortSnapshot()
    var snapshotReads = 0
        private set
    override val snapshot: PlayerPortSnapshot
        get() {
            snapshotReads += 1
            return backingSnapshot
        }

    var playCalls = 0
        private set
    var pauseCalls = 0
        private set
    var nextCalls = 0
        private set
    var previousCalls = 0
        private set
    var closeCalls = 0
        private set
    val seekPositions = mutableListOf<Long>()
    val repeatModes = mutableListOf<Int>()

    override fun play() {
        playCalls += 1
    }

    override fun pause() {
        pauseCalls += 1
    }

    override fun seekTo(positionMs: Long) {
        seekPositions += positionMs
    }

    override fun seekToNext() {
        nextCalls += 1
    }

    override fun seekToPrevious() {
        previousCalls += 1
    }

    override fun setRepeatMode(mode: Int) {
        repeatModes += mode
        backingSnapshot = backingSnapshot.copy(repeatMode = mode)
    }

    override fun close() {
        closeCalls += 1
    }

    suspend fun emitTrack(
        trackId: String,
        title: String,
        durationMs: Long,
        isPlaying: Boolean = false,
        positionMs: Long = 0L,
        quality: String = "128",
    ) {
        emitSnapshot(
            PlayerPortSnapshot(
                currentTrack = track(trackId, title, durationMs, quality),
                isPlaying = isPlaying,
                playWhenReady = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                queue = listOf(track(trackId, title, durationMs, quality)),
                queueIndex = 0,
                quality = quality,
            ),
        )
    }

    suspend fun emitSnapshot(value: PlayerPortSnapshot) {
        backingSnapshot = value
        mutableEvents.emit(PlayerPortEvent.Snapshot(value))
    }

    fun updatePosition(positionMs: Long) {
        backingSnapshot = backingSnapshot.copy(positionMs = positionMs)
    }

    private fun track(id: String, title: String, durationMs: Long, quality: String) = Track(
        id = id,
        title = title,
        artists = listOf("Artist $id"),
        album = "Album $id",
        artworkUri = "https://img.example/$id.jpg",
        durationMs = durationMs,
        qualities = listOf(quality),
        source = PlaybackSource.Kugou(id, "HASH_$id", preferredQuality = quality),
    )
}

class FakeQueueCommands : QueueCommands {
    data class QueueRequest(val trackIds: List<String>, val startIndex: Int)
    data class QualityRequest(val trackIds: List<String>, val startIndex: Int, val quality: String)

    val playQueueRequests = mutableListOf<QueueRequest>()
    val playNextRequests = mutableListOf<String>()
    val selectRequests = mutableListOf<String>()
    val qualityRequests = mutableListOf<QualityRequest>()
    var nextCalls = 0
        private set
    var previousCalls = 0
        private set
    var retryCurrentCalls = 0
        private set
    var handlesNext = false
    var handlesPrevious = false
    var failure: Throwable? = null

    override suspend fun playQueue(trackIds: List<String>, startIndex: Int) {
        failure?.let { throw it }
        playQueueRequests += QueueRequest(trackIds, startIndex)
    }

    override suspend fun playNext(trackId: String) {
        failure?.let { throw it }
        playNextRequests += trackId
    }

    override suspend fun select(trackId: String) {
        failure?.let { throw it }
        selectRequests += trackId
    }

    override suspend fun next(): Boolean {
        failure?.let { throw it }
        nextCalls += 1
        return handlesNext
    }

    override suspend fun previous(): Boolean {
        failure?.let { throw it }
        previousCalls += 1
        return handlesPrevious
    }

    override suspend fun retryCurrent() {
        failure?.let { throw it }
        retryCurrentCalls += 1
    }

    override suspend fun setQuality(trackIds: List<String>, startIndex: Int, quality: String) {
        failure?.let { throw it }
        qualityRequests += QualityRequest(trackIds, startIndex, quality)
    }
}

class FakeLyricsRepository : LyricsRepository {
    val requests = mutableListOf<String>()
    private data class PendingLyrics(
        val result: CompletableDeferred<List<TimedLyricLine>>,
        val ignoresCancellation: Boolean,
    )

    private val responses = mutableMapOf<String, MutableList<PendingLyrics>>()

    fun respondLater(
        trackId: String,
        ignoresCancellation: Boolean = false,
    ): CompletableDeferred<List<TimedLyricLine>> =
        CompletableDeferred<List<TimedLyricLine>>().also { deferred ->
            responses.getOrPut(trackId) { mutableListOf() }
                .add(PendingLyrics(deferred, ignoresCancellation))
        }

    fun setLyrics(trackId: String, lines: List<TimedLyricLine>) {
        responses.getOrPut(trackId) { mutableListOf() }
            .add(PendingLyrics(CompletableDeferred(lines), ignoresCancellation = false))
    }

    override suspend fun lyrics(trackId: String): List<TimedLyricLine> {
        requests += trackId
        val response = responses[trackId]
            ?.removeFirstOrNull()
            ?: PendingLyrics(CompletableDeferred(emptyList()), ignoresCancellation = false)
        return if (response.ignoresCancellation) {
            withContext(NonCancellable) { response.result.await() }
        } else {
            response.result.await()
        }
    }
}
