package com.kutv.music.video

import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

internal interface MvPlayer {
    val currentMediaId: String?
    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
    fun setMediaItem(mediaId: String, uri: String, mimeType: String)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()
    fun clearMediaItems()
    fun setPlaybackErrorListener(listener: (() -> Unit)?)
    fun release()
}

/** All stateful entry points are serialized on the Android main thread by the Bridge/lifecycle scope. */
class MvPlaybackController private constructor(
    private val resolver: MvStreamResolver,
    private val player: MvPlayer,
    private val ownership: PlaybackOwnership,
    private val onVisibilityChanged: (Boolean) -> Unit,
    private val admission: MvResolverAdmission,
    resolverDispatcher: CoroutineDispatcher,
    private val stateDispatcher: CoroutineDispatcher,
    @Suppress("UNUSED_PARAMETER") serializedOnMainThread: Unit,
) {
    constructor(
        resolver: MvStreamResolver,
        player: ExoPlayer,
        ownership: PlaybackOwnership,
        admission: MvResolverAdmission = MvResolverAdmission(),
        onVisibilityChanged: (Boolean) -> Unit = {},
    ) : this(
        resolver,
        ExoMvPlayer(player),
        ownership,
        onVisibilityChanged,
        admission,
        Dispatchers.IO,
        Dispatchers.Main.immediate,
        Unit,
    )

    internal constructor(
        resolver: MvStreamResolver,
        player: MvPlayer,
        ownership: PlaybackOwnership,
        onVisibilityChanged: (Boolean) -> Unit = {},
        admission: MvResolverAdmission = MvResolverAdmission(),
        resolverDispatcher: CoroutineDispatcher = Dispatchers.IO,
        stateDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) : this(
        resolver,
        player,
        ownership,
        onVisibilityChanged,
        admission,
        resolverDispatcher,
        stateDispatcher,
        Unit,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serializedResolverDispatcher = resolverDispatcher.limitedParallelism(1)

    private var mvId: String? = null
    private var quality = ""
    private var availableQualities: List<String> = emptyList()
    private var errorCode: String? = null
    private var destroyed = false
    private var foreground = true
    private var generation = 0L
    private val operationLock = Any()
    private var operationJob: Job? = null
    @Volatile
    private var operationKind: String? = null
    private val ownershipToken = Any()
    private val playbackErrorListener: () -> Unit = { handleAsyncPlaybackError() }
    val isOpen: Boolean get() = mvId != null && !destroyed

    init {
        player.setPlaybackErrorListener(playbackErrorListener)
    }

    @MainThread
    suspend fun open(mvId: String, quality: String): JSONObject {
        if (destroyed) return rejected("MV_DESTROYED")
        if (hasActiveOperation()) return resolverBusy()
        if (isOpen && this.mvId == mvId && this.quality == quality) return accepted(quality)
        val lease = admission.tryAcquire() ?: return resolverBusy()
        val operation = nextGeneration()
        operationKind = "open"
        closeActivePlayback()
        return try {
            completeOpen(operation, mvId, quality)
        } finally {
            operationKind = null
            lease.release()
        }
    }

    @MainThread
    fun requestOpen(scope: CoroutineScope, mvId: String, quality: String): JSONObject {
        if (destroyed) return rejected("MV_DESTROYED")
        if (hasActiveOperation()) return resolverBusy()
        if (isOpen && this.mvId == mvId && this.quality == quality) return accepted(quality)
        val lease = admission.tryAcquire() ?: return resolverBusy()
        val operation = nextGeneration()
        val job = reserveOperation(scope, lease, "open") {
            val resolved = resolveSafely(mvId, quality)
            withContext(stateDispatcher) { applyOpen(operation, mvId, quality, resolved) }
        } ?: run {
            lease.release()
            return resolverBusy()
        }
        closeActivePlayback()
        job.start()
        return pending(quality)
    }

    private suspend fun completeOpen(operation: Long, mvId: String, quality: String): JSONObject {
        return applyOpen(operation, mvId, quality, resolveSafely(mvId, quality))
    }

    private fun applyOpen(
        operation: Long,
        mvId: String,
        quality: String,
        resolved: Result<ResolvedMvStream>,
    ): JSONObject {
        val stream = resolved.getOrElse {
            if (!isCurrent(operation)) return superseded()
            errorCode = "MV_RESOLVE_FAILED"
            return rejected(errorCode!!)
        }
        if (!isCurrent(operation)) return superseded()
        return try {
            if (!ownership.claimMv(ownershipToken)) {
                if (!isCurrent(operation)) return superseded()
                errorCode = "MV_MUSIC_NOT_READY"
                return rejected(errorCode!!)
            }
            if (!isCurrent(operation)) return supersededAfterClaim()
            player.setMediaItem(mvId, stream.url, stream.mimeType)
            if (!isCurrent(operation)) return supersededAfterClaim()
            player.prepare()
            if (!isCurrent(operation)) return supersededAfterClaim()
            if (foreground) player.play() else player.pause()
            if (!isCurrent(operation)) return supersededAfterClaim()
            this.mvId = mvId
            this.quality = stream.quality
            availableQualities = stream.availableQualities
            errorCode = null
            if (!isCurrent(operation)) return supersededAfterClaim()
            onVisibilityChanged(true)
            accepted(this.quality)
        } catch (_: Throwable) {
            if (!isCurrent(operation)) return supersededAfterClaim()
            failPlayback()
        }
    }

    @MainThread
    fun toggle(): JSONObject {
        if (!isOpen) return rejected("MV_NOT_OPEN")
        if (!foreground) return rejected("MV_BACKGROUND")
        return try {
            if (player.isPlaying) player.pause() else player.play()
            errorCode = null
            accepted(quality)
        } catch (_: Throwable) {
            failPlayback()
        }
    }

    @MainThread
    fun seekBy(deltaMs: Long): JSONObject {
        if (!isOpen) return rejected("MV_NOT_OPEN")
        val duration = sanitizedDuration()
        val upperBound = duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (sanitizedPosition() + deltaMs).coerceIn(0L, upperBound)
        return try {
            player.seekTo(target)
            errorCode = null
            accepted(quality)
        } catch (_: Throwable) {
            failPlayback()
        }
    }

    @MainThread
    suspend fun setQuality(nextQuality: String): JSONObject {
        if (hasActiveOperation()) return resolverBusy()
        val activeMvId = mvId ?: return rejected("MV_NOT_OPEN")
        if (nextQuality == quality) return accepted(quality)
        val lease = admission.tryAcquire() ?: return resolverBusy()
        val operation = nextGeneration()
        operationKind = "quality"
        return try {
            completeQuality(operation, activeMvId, nextQuality)
        } finally {
            operationKind = null
            lease.release()
        }
    }

    @MainThread
    fun requestSetQuality(scope: CoroutineScope, nextQuality: String): JSONObject {
        if (hasActiveOperation()) return resolverBusy()
        val activeMvId = mvId ?: return rejected("MV_NOT_OPEN")
        if (nextQuality == quality) return accepted(quality)
        val lease = admission.tryAcquire() ?: return resolverBusy()
        val operation = nextGeneration()
        val job = reserveOperation(scope, lease, "quality") {
            val resolved = resolveSafely(activeMvId, nextQuality)
            withContext(stateDispatcher) { applyQuality(operation, activeMvId, nextQuality, resolved) }
        } ?: run {
            lease.release()
            return resolverBusy()
        }
        job.start()
        return pending(nextQuality)
    }

    private suspend fun completeQuality(operation: Long, activeMvId: String, nextQuality: String): JSONObject {
        return applyQuality(operation, activeMvId, nextQuality, resolveSafely(activeMvId, nextQuality))
    }

    private fun applyQuality(
        operation: Long,
        activeMvId: String,
        nextQuality: String,
        resolved: Result<ResolvedMvStream>,
    ): JSONObject {
        val stream = resolved.getOrElse {
            if (!isCurrent(operation)) return superseded()
            errorCode = "MV_RESOLVE_FAILED"
            return rejected(errorCode!!)
        }
        if (!isCurrent(operation)) return superseded()
        val position = sanitizedPosition()
        val wasPlaying = player.isPlaying
        return try {
            player.setMediaItem(activeMvId, stream.url, stream.mimeType)
            if (!isCurrent(operation)) return supersededAfterClaim()
            player.prepare()
            if (!isCurrent(operation)) return supersededAfterClaim()
            player.seekTo(position)
            if (!isCurrent(operation)) return supersededAfterClaim()
            if (foreground && wasPlaying) player.play() else player.pause()
            if (!isCurrent(operation)) return supersededAfterClaim()
            quality = stream.quality
            availableQualities = stream.availableQualities
            errorCode = null
            accepted(quality)
        } catch (_: Throwable) {
            if (!isCurrent(operation)) supersededAfterClaim() else failPlayback()
        }
    }

    @MainThread
    fun setForeground(active: Boolean) {
        foreground = active
        if (!active && isOpen) runCatching { player.pause() }.onFailure { failPlayback() }
        // Keep the media/position, but do not unexpectedly autoplay on return.
    }

    @MainThread
    fun close(): JSONObject {
        invalidateGeneration()
        cancelOperation()
        closeActivePlayback()
        return JSONObject().put("accepted", true)
    }

    @MainThread
    fun snapshot(): JSONObject = JSONObject()
        .put("durationMs", sanitizedDuration())
        .put("positionMs", sanitizedPosition())
        .put("playing", isOpen && player.isPlaying)
        .put("quality", quality)
        .put("errorCode", errorCode ?: JSONObject.NULL)
        .put("open", isOpen)
        .put("operation", operationKind ?: JSONObject.NULL)
        .apply { if (availableQualities.isNotEmpty()) put("availableQualities", JSONArray(availableQualities)) }

    @MainThread
    fun destroy() {
        if (destroyed) return
        invalidateGeneration()
        cancelOperation()
        destroyed = true
        player.setPlaybackErrorListener(null)
        closeActivePlayback()
        player.release()
    }

    private fun handleAsyncPlaybackError() {
        if (destroyed) return
        failPlayback()
    }

    private fun failPlayback(): JSONObject {
        invalidateGeneration()
        cancelOperation()
        closeActivePlayback(clearError = false)
        errorCode = "MV_PLAYBACK_FAILED"
        return rejected(errorCode!!)
    }

    private fun closeActivePlayback(clearError: Boolean = true) {
        if (isOpen || player.currentMediaId != null) {
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
        }
        mvId = null
        quality = ""
        availableQualities = emptyList()
        if (clearError) errorCode = null
        onVisibilityChanged(false)
        ownership.releaseMv(ownershipToken)
    }

    private fun nextGeneration(): Long = (++generation)
    private fun invalidateGeneration() { generation += 1L }

    private fun hasActiveOperation(): Boolean = synchronized(operationLock) {
        val current = operationJob ?: return@synchronized false
        if (current.isCompleted) {
            if (operationJob === current) {
                operationJob = null
                operationKind = null
            }
            false
        } else {
            true
        }
    }

    private fun cancelOperation() {
        synchronized(operationLock) { operationJob }?.cancel()
    }

    private fun reserveOperation(
        scope: CoroutineScope,
        lease: MvResolverAdmission.Lease,
        kind: String,
        block: suspend () -> Unit,
    ): Job? = synchronized(operationLock) {
        if (operationJob?.isCompleted == true) operationJob = null
        if (operationJob != null) return@synchronized null
        val job = scope.launch(serializedResolverDispatcher, start = CoroutineStart.LAZY) { block() }
        operationJob = job
        operationKind = kind
        job.invokeOnCompletion {
            lease.release()
            synchronized(operationLock) {
                if (operationJob === job) {
                    operationJob = null
                    operationKind = null
                }
            }
        }
        job
    }

    private suspend fun resolveSafely(mvId: String, quality: String): Result<ResolvedMvStream> = try {
        resolver.resolve(mvId, quality)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        Result.failure(IllegalStateException("MV resolve failed"))
    }

    private fun isCurrent(operation: Long): Boolean = !destroyed && operation == generation
    private fun superseded(): JSONObject = rejected("MV_OPERATION_SUPERSEDED")
    private fun resolverBusy(): JSONObject = rejected("MV_RESOLVER_BUSY")
    private fun supersededAfterClaim(): JSONObject {
        closeActivePlayback()
        return superseded()
    }

    private fun sanitizedDuration(): Long = player.durationMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
    private fun sanitizedPosition(): Long = player.currentPositionMs.coerceAtLeast(0L)
    private fun accepted(quality: String): JSONObject = JSONObject().put("accepted", true).put("quality", quality)
    private fun pending(quality: String): JSONObject = accepted(quality).put("pending", true)
    private fun rejected(code: String): JSONObject = JSONObject().put("accepted", false).put("errorCode", code)

    private class ExoMvPlayer(private val delegate: ExoPlayer) : MvPlayer {
        private var errorListener: Player.Listener? = null
        override val currentMediaId: String? get() = delegate.currentMediaItem?.mediaId
        override val durationMs: Long get() = delegate.duration
        override val currentPositionMs: Long get() = delegate.currentPosition
        override val isPlaying: Boolean get() = delegate.isPlaying

        override fun setMediaItem(mediaId: String, uri: String, mimeType: String) {
            delegate.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(mediaId)
                    .setUri(uri.toUri())
                    .setMimeType(mimeType)
                    .build(),
            )
        }

        override fun prepare() = delegate.prepare()
        override fun play() = delegate.play()
        override fun pause() = delegate.pause()
        override fun seekTo(positionMs: Long) = delegate.seekTo(positionMs)
        override fun stop() = delegate.stop()
        override fun clearMediaItems() = delegate.clearMediaItems()
        override fun setPlaybackErrorListener(listener: (() -> Unit)?) {
            errorListener?.let(delegate::removeListener)
            errorListener = listener?.let { callback ->
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        callback()
                    }
                }.also(delegate::addListener)
            }
        }
        override fun release() = delegate.release()
    }
}
