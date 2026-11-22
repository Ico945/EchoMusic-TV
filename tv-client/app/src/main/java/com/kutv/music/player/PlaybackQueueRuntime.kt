package com.kutv.music.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object PlaybackQueueRuntime {
    private val _errors = MutableSharedFlow<PlaybackQueueError>(extraBufferCapacity = 16)
    val errors: SharedFlow<PlaybackQueueError> = _errors.asSharedFlow()
    private val _snapshot = MutableStateFlow(PlaybackQueueSnapshot())
    val snapshot: StateFlow<PlaybackQueueSnapshot> = _snapshot.asStateFlow()

    private var active: PlaybackQueueCommands? = null
    private var errorForwarder: Job? = null
    private var snapshotForwarder: Job? = null
    private var errorSink: (PlaybackQueueError) -> Unit = {}
    private var allowsMusicPlayback: () -> Boolean = { true }

    @Synchronized
    fun attach(
        commands: PlaybackQueueCommands,
        scope: CoroutineScope,
        onError: (PlaybackQueueError) -> Unit = {},
        allowsMusicPlayback: () -> Boolean = { true },
    ) {
        if (active === commands) return
        errorForwarder?.cancel()
        snapshotForwarder?.cancel()
        active = commands
        errorSink = onError
        this.allowsMusicPlayback = allowsMusicPlayback
        _snapshot.value = commands.snapshot.value
        errorForwarder = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            commands.errors.collect { error ->
                errorSink(error)
                _errors.emit(error)
            }
        }
        snapshotForwarder = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            commands.snapshot.collect { _snapshot.value = it }
        }
    }

    @Synchronized
    fun detach(commands: PlaybackQueueCommands) {
        if (active !== commands) return
        active = null
        errorSink = {}
        allowsMusicPlayback = { true }
        errorForwarder?.cancel()
        errorForwarder = null
        snapshotForwarder?.cancel()
        snapshotForwarder = null
        _snapshot.value = PlaybackQueueSnapshot()
    }

    @Synchronized
    fun submitAndPlay(queue: List<QueueTrack>, startIndex: Int): Deferred<Boolean>? =
        active?.takeIf { allowsMusicPlayback() }?.submitAndPlay(queue, startIndex)

    @Synchronized
    fun requestSelect(trackId: String, positionMs: Long = 0L, play: Boolean = true): Deferred<Boolean>? =
        active?.takeIf { allowsMusicPlayback() }?.requestSelect(trackId, positionMs, play)

    @Synchronized
    fun setCurrentQuality(trackId: String, quality: String): Deferred<Boolean>? =
        active?.takeIf { allowsMusicPlayback() }?.setCurrentQuality(trackId, quality)

    @Synchronized
    fun enqueueNext(track: QueueTrack): Deferred<Boolean>? =
        active?.takeIf { allowsMusicPlayback() }?.enqueueNext(track)

    @Synchronized
    fun currentSnapshot(): PlaybackQueueSnapshot = active?.snapshot?.value ?: _snapshot.value

    @Synchronized
    fun requestNext(): Deferred<Boolean>? = active?.takeIf { allowsMusicPlayback() }?.requestNext()

    @Synchronized
    fun requestPrevious(): Deferred<Boolean>? = active?.takeIf { allowsMusicPlayback() }?.requestPrevious()

    @Synchronized
    fun clear(): Deferred<Boolean>? = active?.clear()

    @Synchronized
    fun editQueue(action: String, trackId: String?): Deferred<Boolean>? =
        active?.takeIf { allowsMusicPlayback() }?.editQueue(action, trackId)
}
