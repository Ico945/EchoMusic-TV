package com.kutv.music.application.playback

import androidx.media3.common.Player
import com.kutv.music.player.PlaybackCommandSupersededException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

class PlaybackControllerFacade(
    private val player: PlayerPort,
    private val queueCommands: QueueCommands,
    private val lyricsRepository: LyricsRepository,
    private val scope: CoroutineScope,
    private val positionIntervalMs: Long = 500L,
    private val audioEffects: () -> AudioEffectController? = AudioEffectRuntime::current,
    private val errorStore: PlaybackErrorStore = PlaybackErrorStore(MonotonicErrorIdentity()),
) : AutoCloseable {
    private val initialPlayerSnapshot = player.snapshot
    private val initialErrorState = errorStore.state.value
    private val mutableState = MutableStateFlow(
        initialPlayerSnapshot.toPlayerSnapshot(
            error = initialErrorState.error,
            errorRevision = initialErrorState.errorRevision,
        ),
    )
    val state: StateFlow<PlayerSnapshot> = mutableState.asStateFlow()

    private var lyricsJob: Job? = null
    private var lyricsGeneration = 0L
    private var positionJob: Job? = null
    private var closed = false
    private val commandMutex = Mutex()
    private var pendingPlayWhenReady: Boolean? = null
    private var pendingSeek: PendingSeek? = null
    private val eventJob: Job
    private val errorJob: Job

    init {
        require(positionIntervalMs > 0)
        queueCommands.synchronize(initialPlayerSnapshot)
        initialPlayerSnapshot.currentTrack?.id?.let(::loadLyrics)
        eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.events.collect { event ->
                when (event) {
                    is PlayerPortEvent.Snapshot -> publish(event.value)
                    is PlayerPortEvent.AudioSessionChanged -> Unit
                }
            }
        }
        errorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            errorStore.state.collect { errorState ->
                if (!closed) {
                    mutableState.update {
                        it.copy(error = errorState.error, errorRevision = errorState.errorRevision)
                    }
                }
            }
        }
    }

    fun setPositionUpdatesRequested(requested: Boolean) {
        if (closed) return
        if (requested && positionJob == null) {
            positionJob = scope.launch {
                while (currentCoroutineContext().isActive) {
                    val position = player.snapshot.positionMs.coerceAtLeast(0L)
                    mutableState.update { current -> current.copy(positionMs = position) }
                    delay(positionIntervalMs)
                }
            }
        } else if (!requested) {
            positionJob?.cancel()
            positionJob = null
        }
    }

    suspend fun playQueue(trackIds: List<String>, startIndex: Int): Boolean {
        if (trackIds.isEmpty() || startIndex !in trackIds.indices) return invalidCommand()
        return runCommand { queueCommands.playQueue(trackIds, startIndex) }
    }

    suspend fun playNext(trackId: String): Boolean {
        if (trackId.isBlank()) return invalidCommand()
        return runCommand { queueCommands.playNext(trackId) }
    }

    suspend fun toggle(): Boolean = commandMutex.withLock {
        val previousPending = pendingPlayWhenReady
        val target = !(previousPending ?: player.snapshot.playWhenReady)
        pendingPlayWhenReady = target
        runCommand {
            try {
                if (target) player.play() else player.pause()
            } catch (failure: Exception) {
                pendingPlayWhenReady = previousPending
                throw failure
            }
        }
    }

    suspend fun previous(): Boolean = runCommand {
        if (!queueCommands.previous()) {
            check(!queueCommands.ownsNavigation) { "Previous track unavailable" }
            player.seekToPrevious()
        }
    }

    suspend fun retryCurrent(): Boolean = commandMutex.withLock {
        val retryPositionMs = state.value.positionMs.coerceAtLeast(0L)
        runCommand {
            queueCommands.retryCurrent()
            if (retryPositionMs > 0L) player.seekTo(retryPositionMs)
        }
    }

    suspend fun next(): Boolean = runCommand {
        if (!queueCommands.next()) {
            check(!queueCommands.ownsNavigation) { "Next track unavailable" }
            player.seekToNext()
        }
    }

    suspend fun seekBy(deltaMs: Long): Boolean = commandMutex.withLock {
        val current = player.snapshot
        val trackId = current.currentTrack?.id
        val previousPending = pendingSeek
        val basePosition = previousPending
            ?.takeIf { it.trackId == trackId }
            ?.positionMs
            ?: current.positionMs
        val upperBound = current.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val targetPosition = (basePosition + deltaMs).coerceIn(0L, upperBound)
        pendingSeek = PendingSeek(trackId, targetPosition)
        runCommand {
            try {
                player.seekTo(targetPosition)
            } catch (failure: Exception) {
                pendingSeek = previousPending
                throw failure
            }
        }
    }

    /** Commit a TV scrub preview only to the track it was created for. */
    suspend fun seekTo(trackId: String, positionMs: Long): Boolean = commandMutex.withLock {
        val current = player.snapshot
        if (trackId != current.currentTrack?.id || positionMs < 0L || current.durationMs <= 0L) {
            return@withLock false
        }
        val previousPending = pendingSeek
        val target = positionMs.coerceAtMost(current.durationMs)
        pendingSeek = PendingSeek(trackId, target)
        runCommand {
            try { player.seekTo(target) }
            catch (failure: Exception) { pendingSeek = previousPending; throw failure }
        }
    }

    suspend fun selectQueueItem(trackId: String): Boolean {
        if (trackId.isBlank() || state.value.queue.none { it.id == trackId }) return invalidCommand()
        return runCommand { queueCommands.select(trackId) }
    }

    suspend fun editQueue(action: String, trackId: String?): Boolean = commandMutex.withLock {
        if (action !in setOf("shuffle", "remove", "clearPending")) return@withLock invalidCommand()
        runCommand { check(queueCommands.editQueue(action, trackId)) { "Queue edit unavailable" } }
    }

    suspend fun setRepeatMode(mode: String): Boolean {
        val media3Mode = when (mode) {
            "off" -> Player.REPEAT_MODE_OFF
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> return invalidCommand()
        }
        return runCommand { player.setRepeatMode(media3Mode) }
    }

    suspend fun setQuality(quality: String): Boolean {
        val current = state.value
        if (quality.isBlank() || quality !in current.currentTrack?.qualities.orEmpty() || current.queueIndex !in current.queue.indices) {
            return invalidCommand()
        }
        return runCommand {
            queueCommands.setQuality(current.queue.map { it.id }, current.queueIndex, quality)
        }
    }

    suspend fun setSoundEffect(preset: String): Boolean {
        val controller = audioEffects()
        if (preset !in SOUND_EFFECT_PRESETS || controller == null || preset !in controller.supportedPresets) {
            reportError(PlayerCommandErrors.soundEffectUnavailable())
            return false
        }
        return controller.select(preset).fold(
            onSuccess = {
                clearObservedError()
                true
            },
            onFailure = {
                reportError(PlayerCommandErrors.soundEffectUnavailable())
                false
            },
        )
    }

    fun clearError(errorId: Long): PlaybackErrorClearResult = errorStore.clear(errorId).also { result ->
        if (result.accepted) {
            mutableState.update { it.copy(error = null, errorRevision = result.errorRevision) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingPlayWhenReady = null
        pendingSeek = null
        positionJob?.cancel()
        positionJob = null
        lyricsGeneration += 1
        lyricsJob?.cancel()
        lyricsJob = null
        eventJob.cancel()
        errorJob.cancel()
        player.close()
    }

    private fun publish(snapshot: PlayerPortSnapshot) {
        if (closed) return
        val previousTrackId = state.value.currentTrack?.id
        val nextTrackId = snapshot.currentTrack?.id
        pendingPlayWhenReady?.let { expected ->
            if (snapshot.playWhenReady == expected) pendingPlayWhenReady = null
        }
        pendingSeek?.let { expected ->
            if (expected.trackId != nextTrackId || abs(snapshot.positionMs - expected.positionMs) <= 1_000L) {
                pendingSeek = null
            }
        }
        if (nextTrackId != previousTrackId) {
            lyricsGeneration += 1
            lyricsJob?.cancel()
        }
        queueCommands.synchronize(snapshot)
        mutableState.update { current ->
            snapshot.toPlayerSnapshot(
                lyrics = if (nextTrackId == previousTrackId) current.lyrics else emptyList(),
                error = current.error,
                errorRevision = current.errorRevision,
            )
        }
        if (nextTrackId != previousTrackId && nextTrackId != null) loadLyrics(nextTrackId)
    }

    private fun loadLyrics(trackId: String) {
        lyricsJob?.cancel()
        val generation = ++lyricsGeneration
        lyricsJob = scope.launch {
            val lines = try {
                lyricsRepository.lyrics(trackId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            if (!closed && lyricsGeneration == generation && state.value.currentTrack?.id == trackId) {
                mutableState.update { current -> current.copy(lyrics = lines) }
            }
        }
    }

    private suspend fun runCommand(block: suspend () -> Unit): Boolean {
        val observed = errorStore.state.value
        return try {
            block()
            observed.error?.let { clearError(it.errorId) }
            true
        } catch (_: PlaybackCommandSupersededException) {
            // Acknowledge an obsolete command without changing current errors
            // or falling back to a seek in the newly selected queue.
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            if (errorStore.state.value.errorRevision == observed.errorRevision) {
                reportError(PlayerCommandErrors.failed())
            }
            false
        }
    }

    private fun invalidCommand(): Boolean {
        reportError(PlayerCommandErrors.invalid())
        return false
    }

    private fun clearObservedError() {
        errorStore.state.value.error?.let { clearError(it.errorId) }
    }

    private fun reportError(descriptor: PlayerErrorDescriptor): PlayerCommandError =
        errorStore.report(descriptor).also { error ->
            mutableState.update { it.copy(error = error, errorRevision = error.errorId) }
        }

    private fun PlayerPortSnapshot.toPlayerSnapshot(
        lyrics: List<com.kutv.music.core.model.TimedLyricLine> = emptyList(),
        error: PlayerCommandError? = null,
        errorRevision: Long = 0L,
    ) = PlayerSnapshot(
        currentTrack = currentTrack,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        queue = queue,
        queueIndex = queueIndex,
        repeatMode = repeatMode,
        quality = quality,
        lyrics = lyrics,
        error = error,
        errorRevision = errorRevision,
        buffering = buffering,
    )

    private data class PendingSeek(
        val trackId: String?,
        val positionMs: Long,
    )

    private companion object {
        val SOUND_EFFECT_PRESETS = setOf("off", "bass", "vocal", "live")
    }
}
