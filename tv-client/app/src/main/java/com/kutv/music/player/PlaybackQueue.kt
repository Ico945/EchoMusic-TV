package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class QueueTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val artworkUrl: String,
    val album: String = "",
    val source: PlaybackSource,
    val durationMs: Long = 0L,
    val qualities: List<String> = emptyList(),
)

internal fun QueueTrack.toDomainTrack() = Track(
    id = id,
    title = title,
    artists = artists,
    album = album,
    artworkUri = artworkUrl,
    durationMs = durationMs,
    qualities = qualities,
    source = source,
)

data class PlaybackQueueSnapshot(
    val queue: List<QueueTrack> = emptyList(),
    val currentIndex: Int = -1,
    /** Persisted position retained while the current source is being re-resolved. */
    val pendingRestorePositionMs: Long? = null,
)

internal fun PlaybackQueueSnapshot.effectivePositionMs(
    playerPositionMs: Long,
    hasPhysicalMediaItem: Boolean,
): Long = pendingRestorePositionMs
    ?.takeIf { !hasPhysicalMediaItem && currentIndex in queue.indices }
    ?: playerPositionMs

data class ResolvedQueueTrack(
    val track: QueueTrack,
    val streamUrl: String,
    val quality: String,
    /** Explicit server-verified capability, distinct from a caller's cached metadata. */
    val availableQualities: List<String>? = null,
)

object QueueMetadataKeys {
    const val QUALITY = "kutv.quality"
    const val QUALITIES = "kutv.qualities"
    const val START_INDEX = "kutv.startIndex"
    const val ALBUM = "kutv.album"
    const val DURATION = "kutv.duration"
    const val SOURCE_KIND = "kutv.source.kind"
    const val SOURCE_URI = "kutv.source.uri"
    const val SOURCE_TRACK_ID = "kutv.source.trackId"
    const val SOURCE_HASH = "kutv.source.hash"
    const val SOURCE_ALBUM_ID = "kutv.source.albumId"
    const val SOURCE_LYRIC_ID = "kutv.source.lyricId"
    const val SOURCE_QUALITY = "kutv.source.quality"
    const val SOURCE_LOCAL = "local"
    const val SOURCE_KUGOU = "kugou"
}

interface QueueTrackResolver {
    suspend fun resolve(track: QueueTrack): ResolvedQueueTrack
}

class PlaybackResolutionException(message: String) : Exception(message)

/** The command was accepted earlier but a newer queue/start has replaced it. */
class PlaybackCommandSupersededException : CancellationException("Playback command was superseded")

interface QueuePlayerPort {
    fun currentQuality(trackId: String): String? = null
    fun contains(trackId: String): Boolean
    fun loadAndPlay(item: ResolvedQueueTrack)
    fun loadPaused(item: ResolvedQueueTrack, positionMs: Long)
    /** Replace only the active source, preserving position and playback intent at commit time. */
    fun replaceCurrent(item: ResolvedQueueTrack) { throw UnsupportedOperationException() }
    fun loadSelection(item: ResolvedQueueTrack, positionMs: Long, play: Boolean) {
        throw UnsupportedOperationException()
    }
    fun append(item: ResolvedQueueTrack)
    fun prependBeforeCurrent(item: ResolvedQueueTrack)
    fun insertAfterCurrent(item: ResolvedQueueTrack)
    fun seekTo(trackId: String)
    fun clear()
    /** Discard resolved neighbours without changing the current item or position. */
    fun retainCurrent() { throw UnsupportedOperationException() }
}

enum class PlaybackQueueAction {
    START,
    RESTORE,
    QUALITY,
    PREFETCH,
    NEXT,
    PREVIOUS,
}

data class PlaybackQueueError(
    val action: PlaybackQueueAction,
    val trackId: String?,
    val message: String,
    val retryable: Boolean,
    val trackTitle: String? = null,
)

interface PlaybackQueueCommands {
    val errors: SharedFlow<PlaybackQueueError>
    val snapshot: StateFlow<PlaybackQueueSnapshot>
    fun submitAndPlay(queue: List<QueueTrack>, startIndex: Int): Deferred<Boolean>
    fun setCurrentQuality(trackId: String, quality: String): Deferred<Boolean> = CompletableDeferred(false)
    fun requestSelect(trackId: String, positionMs: Long = 0L, play: Boolean = false): Deferred<Boolean> =
        CompletableDeferred(false)
    fun enqueueNext(track: QueueTrack): Deferred<Boolean>
    fun requestNext(): Deferred<Boolean>
    fun requestPrevious(): Deferred<Boolean>
    fun onMediaItemTransition(trackId: String)
    fun clear(): Deferred<Boolean>
    fun editQueue(action: String, trackId: String? = null): Deferred<Boolean> = CompletableDeferred(false)
}

class PlaybackQueueCoordinator(
    private val resolver: QueueTrackResolver,
    private val player: QueuePlayerPort,
    private val scope: CoroutineScope,
) : PlaybackQueueCommands {
    private val stateMutex = Mutex()
    private val enqueueMutex = Mutex()
    private val _errors = MutableSharedFlow<PlaybackQueueError>(extraBufferCapacity = 16)
    override val errors: SharedFlow<PlaybackQueueError> = _errors.asSharedFlow()
    private val _snapshot = MutableStateFlow(PlaybackQueueSnapshot())
    override val snapshot: StateFlow<PlaybackQueueSnapshot> = _snapshot.asStateFlow()
    private var generation = 0L
    private var qualityRevision = 0L
    private var selectionRevision = 0L
    var repeatAll: Boolean = false
    private var queue: List<QueueTrack> = emptyList()
    private var currentIndex = -1
    private var pendingRestorePositionMs: Long? = null
    private var startReady: CompletableDeferred<Boolean>? = null
    private val inFlight = mutableMapOf<ResolutionKey, ResolutionFlight>()

    fun restorePaused(
        queue: List<QueueTrack>,
        startIndex: Int,
        positionMs: Long,
    ): Deferred<Boolean> = scope.async {
        val ready = CompletableDeferred<Boolean>()
        try {
            val restoredGeneration = stateMutex.withLock {
                startReady?.complete(false)
                generation += 1
                this@PlaybackQueueCoordinator.queue = queue.toList()
                currentIndex = if (startIndex in queue.indices) startIndex else -1
                pendingRestorePositionMs = positionMs.takeIf { currentIndex >= 0 && it >= 0L }
                publishSnapshotLocked()
                startReady = ready
                generation
            }
            if (startIndex !in queue.indices || positionMs < 0L) return@async false
            val current = queue[startIndex]
            val resolved = try {
                resolveSingleFlight(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitError(restoredGeneration, PlaybackQueueAction.RESTORE, current.id, "上次播放恢复失败")
                return@async false
            }
            if (!isCurrent(restoredGeneration, startIndex, current.id)) throw PlaybackCommandSupersededException()
            try {
                player.loadPaused(resolved, positionMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitError(restoredGeneration, PlaybackQueueAction.RESTORE, current.id, "上次播放恢复失败")
                return@async false
            }
            stateMutex.withLock {
                if (generation != restoredGeneration || queue.getOrNull(startIndex)?.id != current.id) {
                    throw PlaybackCommandSupersededException()
                }
                pendingRestorePositionMs = null
                publishSnapshotLocked()
            }
            ready.complete(true)
            prefetch(restoredGeneration, startIndex + 1)
            true
        } finally {
            ready.complete(false)
        }
    }

    override fun submitAndPlay(queue: List<QueueTrack>, startIndex: Int): Deferred<Boolean> = scope.async {
        val ready = CompletableDeferred<Boolean>()
        try {
            val submittedGeneration = stateMutex.withLock {
                startReady?.complete(false)
                generation += 1
                this@PlaybackQueueCoordinator.queue = queue.toList()
                currentIndex = if (startIndex in queue.indices) startIndex else -1
                pendingRestorePositionMs = null
                publishSnapshotLocked()
                startReady = ready
                generation
            }
            if (startIndex !in queue.indices) {
                emitError(submittedGeneration, PlaybackQueueAction.START, null, "播放失败，请重试")
                return@async false
            }
            val current = queue[startIndex]
            val resolved = try {
                resolveSingleFlight(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitError(submittedGeneration, PlaybackQueueAction.START, current.id, "播放失败，请重试")
                return@async false
            }
            if (!isCurrent(submittedGeneration, startIndex, current.id)) throw PlaybackCommandSupersededException()

            try {
                player.loadAndPlay(resolved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitError(submittedGeneration, PlaybackQueueAction.START, current.id, "播放失败，请重试")
                return@async false
            }
            ready.complete(true)
            prefetch(submittedGeneration, startIndex + 1)
            true
        } finally {
            // Next/previous may already be awaiting startup. Every exit,
            // including cancellation, must release them; success stays true.
            ready.complete(false)
        }
    }

    override fun requestSelect(trackId: String, positionMs: Long, play: Boolean): Deferred<Boolean> = scope.async {
        val target = stateMutex.withLock {
            val index = queue.indexOfFirst { it.id == trackId }
            if (index < 0 || positionMs < 0L) return@async false
            selectionRevision++
            qualityRevision++
            SelectionTarget(generation, selectionRevision, index, queue[index])
        }
        fun isActiveTarget() = generation == target.generation && selectionRevision == target.revision &&
            queue.getOrNull(target.index)?.id == trackId
        try {
            val resolved = resolveSingleFlight(target.track)
            val committedGeneration = stateMutex.withLock {
                if (!isActiveTarget()) throw PlaybackCommandSupersededException()
                player.loadSelection(resolved, positionMs, play)
                generation++
                startReady?.complete(false)
                startReady = CompletableDeferred(true)
                currentIndex = target.index
                pendingRestorePositionMs = null
                publishSnapshotLocked()
                generation
            }
            prefetch(committedGeneration, target.index + 1)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stateMutex.withLock {
                if (!isActiveTarget()) throw PlaybackCommandSupersededException()
                _errors.emit(PlaybackQueueError(PlaybackQueueAction.START, trackId, "播放失败，请重试", true, queue.firstOrNull { it.id == trackId }?.title))
            }
            false
        }
    }

    override fun setCurrentQuality(trackId: String, quality: String): Deferred<Boolean> = scope.async {
        val target = stateMutex.withLock {
            qualityRevision++
            val current = queue.getOrNull(currentIndex) ?: return@async false
            if (current.id != trackId || quality !in current.qualities) return@async false
            val source = current.source as? PlaybackSource.Kugou ?: return@async false
            // A previous request may have fallen back. Preferred is not the
            // actual playing quality, so the same preference must be retryable.
            if (player.currentQuality(trackId) == quality) return@async true
            QualityTarget(generation, qualityRevision, currentIndex,
                current.copy(source = source.copy(preferredQuality = quality)), startReady)
        }
        fun isActiveTarget() = generation == target.generation &&
            qualityRevision == target.revision && currentIndex == target.index &&
            queue.getOrNull(currentIndex)?.id == trackId
        try {
            val resolved = resolveSingleFlight(target.track)
            val committedGeneration = stateMutex.withLock {
                if (!isActiveTarget()) throw PlaybackCommandSupersededException()
                // A switch during initial startup can supersede an unresolved
                // old URL. Normal switches preserve the active player's intent.
                if (target.ready?.isCompleted == false || !player.contains(trackId)) {
                    val restorePosition = pendingRestorePositionMs
                    if (restorePosition != null) player.loadPaused(resolved, restorePosition)
                    else player.loadAndPlay(resolved)
                    generation++
                    startReady?.complete(false)
                    startReady = CompletableDeferred(true)
                    pendingRestorePositionMs = null
                } else {
                    // Read position/playWhenReady at commit, after any pause/seek.
                    player.replaceCurrent(resolved)
                }
                queue = queue.toMutableList().apply { set(currentIndex, resolved.track) }
                publishSnapshotLocked()
                generation
            }
            prefetch(committedGeneration, target.index + 1)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stateMutex.withLock {
                if (!isActiveTarget()) throw PlaybackCommandSupersededException()
                _errors.emit(PlaybackQueueError(PlaybackQueueAction.QUALITY, trackId,
                    "音质切换失败，已保留原音源", true, queue.firstOrNull { it.id == trackId }?.title))
            }
            false
        }
    }

    override fun enqueueNext(track: QueueTrack): Deferred<Boolean> = scope.async {
        require(track.id.isNotBlank())
        enqueueMutex.withLock { enqueueNextSerial(track) }
    }

    override fun clear(): Deferred<Boolean> = scope.async {
        stateMutex.withLock {
            startReady?.complete(false)
            startReady = null
            generation += 1
            queue = emptyList()
            currentIndex = -1
            pendingRestorePositionMs = null
            publishSnapshotLocked()
        }
        runCatching { player.clear() }.isSuccess
    }

    override fun editQueue(action: String, trackId: String?): Deferred<Boolean> = scope.async {
        val committed = stateMutex.withLock {
            val current = queue.getOrNull(currentIndex) ?: return@async false
            // Editing during initial resolution would orphan its startReady owner.
            if (startReady?.isCompleted != true || !player.contains(current.id)) return@async false
            val updated = when (action) {
                "shuffle" -> queue.take(currentIndex + 1) + queue.drop(currentIndex + 1).shuffled()
                "clearPending" -> queue.take(currentIndex + 1)
                "remove" -> {
                    if (trackId == current.id || queue.none { it.id == trackId }) return@async false
                    queue.filterNot { it.id == trackId }
                }
                else -> return@async false
            }
            // Physical autoplay must not visit a neighbour removed from the domain queue.
            player.retainCurrent()
            generation++
            qualityRevision++
            selectionRevision++
            queue = updated
            currentIndex = updated.indexOfFirst { it.id == current.id }
            publishSnapshotLocked()
            generation to currentIndex
        }
        prefetch(committed.first, committed.second + 1)
        true
    }

    suspend fun cancelPendingSelection() {
        stateMutex.withLock {
            generation++; qualityRevision++; selectionRevision++
            startReady?.complete(false)
        }
    }

    private suspend fun enqueueNextSerial(track: QueueTrack): Boolean {
        val target = stateMutex.withLock {
            val current = queue.getOrNull(currentIndex) ?: return@withLock null
            EnqueueTarget(generation, current.id, track, queue, currentIndex)
        } ?: return false
        if (target.track.id == target.currentTrackId) return true

        val resolved = try {
            resolveSingleFlight(track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emitError(target.generation, PlaybackQueueAction.PREFETCH, track.id, "下一首加载失败")
            return false
        }

        val appliedGeneration = stateMutex.withLock {
            if (generation != target.generation || queue.getOrNull(currentIndex)?.id != target.currentTrackId) {
                null
            } else {
                generation += 1
                val updated = queue.toMutableList().apply {
                    removeAll { it.id == target.track.id }
                }
                currentIndex = updated.indexOfFirst { it.id == target.currentTrackId }
                if (currentIndex < 0) return@withLock null
                updated.add(currentIndex + 1, resolved.track)
                queue = updated
                publishSnapshotLocked()
                generation
            }
        }
        if (appliedGeneration == null) throw PlaybackCommandSupersededException()

        return try {
            player.insertAfterCurrent(resolved)
            true
        } catch (_: Exception) {
            val restoredGeneration = stateMutex.withLock {
                if (generation == appliedGeneration && queue.getOrNull(currentIndex)?.id == target.currentTrackId) {
                    generation += 1
                    queue = target.originalQueue
                    currentIndex = target.originalCurrentIndex
                    publishSnapshotLocked()
                    generation
                } else null
            }
            if (restoredGeneration == null) throw PlaybackCommandSupersededException()
            emitError(restoredGeneration, PlaybackQueueAction.PREFETCH, track.id, "下一首加载失败")
            false
        }
    }

    override fun requestNext(): Deferred<Boolean> = scope.async {
        val wrap = stateMutex.withLock {
            queue.firstOrNull()?.id?.takeIf { repeatAll && currentIndex == queue.lastIndex }
        }
        if (wrap != null) return@async requestSelect(wrap).await()
        val target = stateMutex.withLock {
            qualityRevision++
            selectionRevision++
            val targetIndex = currentIndex + 1
            if (targetIndex !in queue.indices) null else Target(
                generation,
                targetIndex,
                queue[targetIndex],
                startReady,
                selectionRevision,
            )
        } ?: return@async false

        if (!player.contains(target.track.id)) {
            val resolved = try {
                resolveSingleFlight(target.track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isNavigationCurrent(target)) throw PlaybackCommandSupersededException()
                emitError(target.generation, PlaybackQueueAction.NEXT, target.track.id, "下一首加载失败，按确认键重试")
                return@async false
            }
            if (target.startReady?.await() != true) throw PlaybackCommandSupersededException()
            if (!isNavigationCurrent(target)) throw PlaybackCommandSupersededException()
            if (!player.contains(target.track.id)) player.append(resolved)
        }
        if (target.startReady?.await() != true) throw PlaybackCommandSupersededException()
        if (!isNavigationCurrent(target)) throw PlaybackCommandSupersededException()
        player.seekTo(target.track.id)
        true
    }

    override fun requestPrevious(): Deferred<Boolean> = scope.async {
        val wrap = stateMutex.withLock {
            queue.lastOrNull()?.id?.takeIf { repeatAll && currentIndex == 0 }
        }
        if (wrap != null) return@async requestSelect(wrap).await()
        val target = stateMutex.withLock {
            qualityRevision++
            selectionRevision++
            val targetIndex = currentIndex - 1
            if (targetIndex !in queue.indices) null else Target(
                generation,
                targetIndex,
                queue[targetIndex],
                startReady,
                selectionRevision,
            )
        } ?: return@async false

        if (!player.contains(target.track.id)) {
            val resolved = try {
                resolveSingleFlight(target.track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isNavigationCurrent(target)) throw PlaybackCommandSupersededException()
                emitError(target.generation, PlaybackQueueAction.PREVIOUS, target.track.id, "上一首加载失败，按确认键重试")
                return@async false
            }
            if (target.startReady?.await() != true) throw PlaybackCommandSupersededException()
            if (!isNavigationCurrent(target)) throw PlaybackCommandSupersededException()
            if (!player.contains(target.track.id)) player.prependBeforeCurrent(resolved)
        }
        if (target.startReady?.await() != true) throw PlaybackCommandSupersededException()
        if (!isNavigationCurrent(target)) throw PlaybackCommandSupersededException()
        player.seekTo(target.track.id)
        true
    }

    override fun onMediaItemTransition(trackId: String) {
        scope.launch {
            val transition = stateMutex.withLock {
                val index = queue.indexOfFirst { it.id == trackId }
                if (index < 0) null else {
                    if (currentIndex != index) { qualityRevision++; selectionRevision++ }
                    currentIndex = index
                    pendingRestorePositionMs = null
                    publishSnapshotLocked()
                    Target(generation, index, queue[index], startReady)
                }
            } ?: return@launch
            prefetch(transition.generation, transition.index + 1)
        }
    }

    private fun prefetch(expectedGeneration: Long, index: Int) {
        scope.launch {
            val track = stateMutex.withLock {
                if (generation != expectedGeneration || index !in queue.indices) null else queue[index]
            } ?: return@launch
            if (player.contains(track.id)) return@launch

            val resolved = try {
                resolveSingleFlight(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emitError(expectedGeneration, PlaybackQueueAction.PREFETCH, track.id, "下一首加载失败")
                return@launch
            }
            if (!isCurrent(expectedGeneration, index, track.id)) return@launch
            if (!player.contains(track.id)) player.append(resolved)
        }
    }

    private suspend fun resolveSingleFlight(track: QueueTrack): ResolvedQueueTrack {
        val requestedGeneration = stateMutex.withLock { generation }
        // A stable UI id is not a media identity: quality, catalog hash,
        // local URI or even the source kind may change while work is pending.
        val key = ResolutionKey(track.id, track.source)
        val flight = stateMutex.withLock {
            val shared = inFlight[key] ?: ResolutionFlight(scope.async(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]
                try {
                    runCatching { resolver.resolve(track) }
                } finally {
                    // Resolution owns this entry, not an individual waiter.
                    // Cancelling one Next command must not create duplicate
                    // requests while a shared prefetch is still in flight.
                    withContext(NonCancellable) {
                        stateMutex.withLock {
                            val current = inFlight[key]
                            if (current != null && current.deferred === owner && current.waiters == 0) inFlight.remove(key)
                        }
                    }
                }
            }).also { inFlight[key] = it }
            shared.waiters++
            shared
        }
        return try {
            val result = flight.deferred.await()
            currentCoroutineContext().ensureActive()
            // Only share the resolution, not a previous caller's title,
            // artwork or queue metadata. The source is equal by key above.
            val resolved = result.getOrThrow()
            val enriched = track.copy(qualities = resolved.availableQualities ?: track.qualities)
            // The logical queue is the public/control source of truth. Preserve
            // caller-owned metadata while publishing newly verified capability.
            // A superseded queue or quality request must not overwrite it.
            stateMutex.withLock {
                if (generation == requestedGeneration) {
                    val index = queue.indexOfFirst { it.id == track.id && it.source == track.source }
                    if (index >= 0 && queue[index].qualities != enriched.qualities) {
                        queue = queue.toMutableList().apply { set(index, get(index).copy(qualities = enriched.qualities)) }
                        publishSnapshotLocked()
                    }
                }
            }
            resolved.copy(track = enriched)
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    flight.waiters--
                    if (inFlight[key] === flight && flight.waiters == 0 && flight.deferred.isCompleted) {
                        inFlight.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun isNavigationCurrent(target: Target): Boolean = stateMutex.withLock {
        generation == target.generation && queue.getOrNull(target.index)?.id == target.track.id &&
            (target.navigationRevision == null || target.navigationRevision == selectionRevision)
    }

    private suspend fun isCurrent(expectedGeneration: Long, index: Int, trackId: String): Boolean =
        stateMutex.withLock {
            generation == expectedGeneration && queue.getOrNull(index)?.id == trackId
        }

    private suspend fun emitError(
        expectedGeneration: Long,
        action: PlaybackQueueAction,
        trackId: String?,
        message: String,
    ) {
        // Serialize checking/emitting with queue replacement, including a
        // backpressured collector; an old generation cannot emit after a new
        // queue has committed. Cancellation is never a retryable failure.
        currentCoroutineContext().ensureActive()
        stateMutex.withLock {
            if (generation != expectedGeneration) throw PlaybackCommandSupersededException()
            _errors.emit(
                PlaybackQueueError(
                    action = action,
                    trackId = trackId,
                    message = message,
                    retryable = true,
                    trackTitle = queue.firstOrNull { it.id == trackId }?.title,
                ),
            )
        }
    }

    private fun publishSnapshotLocked() {
        _snapshot.value = PlaybackQueueSnapshot(queue.toList(), currentIndex, pendingRestorePositionMs)
    }

    private data class Target(
        val generation: Long,
        val index: Int,
        val track: QueueTrack,
        val startReady: Deferred<Boolean>?,
        val navigationRevision: Long? = null,
    )

    private data class SelectionTarget(
        val generation: Long, val revision: Long, val index: Int, val track: QueueTrack,
    )

    private data class QualityTarget(
        val generation: Long, val revision: Long, val index: Int,
        val track: QueueTrack, val ready: Deferred<Boolean>?,
    )

    private data class ResolutionKey(val trackId: String, val source: PlaybackSource)

    private data class ResolutionFlight(
        val deferred: Deferred<Result<ResolvedQueueTrack>>,
        var waiters: Int = 0,
    )

    private data class EnqueueTarget(
        val generation: Long,
        val currentTrackId: String,
        val track: QueueTrack,
        val originalQueue: List<QueueTrack>,
        val originalCurrentIndex: Int,
    )
}
