package com.kutv.music.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MusicSleepTimerTest {
    @Test fun `deadline pauses once and clears without requiring a visible page`() = runBlocking {
        var pauses = 0
        val gate = CompletableDeferred<Unit>()
        var duration = 0L
        val timer = MusicSleepTimer(this, { pauses++ }, { 1_000L }, { duration = it; gate.await() })
        timer.setMinutes(15); yield()
        assertEquals(901_000L, timer.endsAt); assertEquals(900_000L, duration)
        gate.complete(Unit); yield()
        assertEquals(1, pauses); assertEquals(0L, timer.endsAt)
        timer.close()
    }
    @Test fun `rearming and cancellation prevent stale deadlines from pausing`() = runBlocking {
        var pauses = 0
        val waits = mutableListOf<CompletableDeferred<Unit>>()
        val timer = MusicSleepTimer(this, { pauses++ }, { 0L }, { CompletableDeferred<Unit>().also { waits.add(it) }.await() })
        timer.setMinutes(15); yield()
        timer.setMinutes(30); yield()
        waits[0].complete(Unit); yield()
        assertEquals(0, pauses); assertEquals(1_800_000L, timer.endsAt)
        timer.setMinutes(0); waits[1].complete(Unit); yield()
        assertEquals(0, pauses); assertEquals(0L, timer.endsAt)
        timer.setMinutes(60); yield(); timer.close(); waits[2].complete(Unit); yield()
        assertEquals(0, pauses)
    }
    @Test fun `invalid duration does not overwrite an existing timer`() = runBlocking {
        val timer = MusicSleepTimer(this, {}, { 0L }, { CompletableDeferred<Unit>().await() })
        timer.setMinutes(90)
        assertTrue(runCatching { timer.setMinutes(-1) }.isFailure)
        assertEquals(5_400_000L, timer.endsAt)
        timer.close()
    }
}
