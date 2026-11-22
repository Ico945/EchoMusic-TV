package com.kutv.music.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Service-owned deadline; navigating away from the WebView does not cancel it. */
class MusicSleepTimer(
    private val scope: CoroutineScope,
    private val pauseMusic: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitFor: suspend (Long) -> Unit = { delay(it) },
) {
    var endsAt: Long = 0L
        private set
    private var timer: Job? = null
    fun setMinutes(minutes: Int) {
        require(minutes in setOf(0, 15, 30, 60, 90))
        timer?.cancel(); timer = null; endsAt = 0L
        if (minutes == 0) return
        val duration = minutes * 60_000L
        endsAt = clock() + duration
        timer = scope.launch {
            waitFor(duration)
            endsAt = 0L
            pauseMusic()
        }
    }
    fun close() { timer?.cancel(); timer = null; endsAt = 0L }
}

object MusicSleepTimerRuntime {
    var timer: MusicSleepTimer? = null
        private set
    fun attach(value: MusicSleepTimer) { timer?.close(); timer = value }
    fun detach() { timer?.close(); timer = null }
}
