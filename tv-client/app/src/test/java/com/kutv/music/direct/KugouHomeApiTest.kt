package com.kutv.music.direct

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouHomeApiTest {
    @Test fun `independent home shelves start without waiting for new songs`() = runBlocking {
        val dailyStarted = CompletableDeferred<Unit>()
        val api = KugouDirectApi(KugouRequestExecutor {
            if (it.path == "/everyday_song_recommend") dailyStarted.complete(Unit)
            else dailyStarted.await()
            songs()
        }, { null })
        val result = withTimeout(1000) { api.home() }
        assertEquals(1, result.newSongs.size)
        assertEquals(1, result.daily.size)
    }

    private fun songs() = KugouResponse(200, JSONObject("""{"data":[{"hash":"H1","songname":"Real song"}]}"""))

    @Test fun `public home does not require device registration and routes daily correctly`() = runBlocking {
        val requests = mutableListOf<KugouRequest>()
        val api = KugouDirectApi(KugouRequestExecutor { request -> requests += request; songs() }, { null },
            ensureDeviceRegistered = { error("Registration unavailable") })
        val home = api.home()
        assertEquals("Real song", home.hero?.title)
        assertEquals(2, requests.size)
        assertEquals("everydayrec.service.kugou.com", requests.last().headers["x-router"])
        assertTrue(requests.none { it.includeCredentials })
    }

    @Test fun `a failed daily shelf does not discard successful public new songs`() = runBlocking {
        val api = KugouDirectApi(KugouRequestExecutor {
            if (it.path == "/everyday_song_recommend") throw KugouNetworkException(KugouNetworkErrorKind.HTTP, 503)
            songs()
        }, { null })
        val home = api.home()
        assertEquals(1, home.newSongs.size)
        assertTrue(home.daily.isEmpty())
        assertNotNull(home.hero)
    }

    @Test fun `all failed shelves preserve upstream failure instead of fake empty content`() = runBlocking {
        val failure = KugouNetworkException(KugouNetworkErrorKind.NETWORK)
        val api = KugouDirectApi(KugouRequestExecutor { throw failure }, { null })
        assertSame(failure, runCatching { api.home() }.exceptionOrNull())
    }

    @Test fun `cancellation stops all in flight shelves`() = runBlocking {
        val bothStarted = CompletableDeferred<Unit>()
        var started = 0
        var stopped = 0
        val api = KugouDirectApi(KugouRequestExecutor {
            if (++started == 2) bothStarted.complete(Unit)
            try { awaitCancellation() } finally { stopped++ }
        }, { null })
        val pending = async { api.home() }
        withTimeout(1000) { bothStarted.await() }
        pending.cancelAndJoin()
        assertEquals(2, stopped)
        assertTrue(pending.isCancelled)
    }

    @Test fun `stalled daily times out without losing new songs`() = runBlocking {
        var stopped = false
        val api = KugouDirectApi(KugouRequestExecutor {
            if (it.path == "/everyday_song_recommend") {
                try { awaitCancellation() } finally { stopped = true }
            }
            songs()
        }, { null }, homeShelfTimeoutMs = 100)
        val home = withTimeout(1000) { api.home() }
        assertEquals(1, home.newSongs.size)
        assertTrue(home.daily.isEmpty())
        assertTrue(stopped)
    }

    @Test fun `all stalled shelves return typed timeout not empty success`() = runBlocking {
        val api = KugouDirectApi(KugouRequestExecutor { awaitCancellation() }, { null }, homeShelfTimeoutMs = 100)
        val error = withTimeout(1000) { runCatching { api.home() }.exceptionOrNull() }
        assertEquals(KugouNetworkErrorKind.TIMEOUT, (error as KugouNetworkException).kind)
    }

    @Test fun `slow new songs and registration do not discard a successful daily shelf`() = runBlocking {
        var registrationStopped = false
        val api = KugouDirectApi(KugouRequestExecutor {
            if (it.path == "/everyday_song_recommend") songs() else awaitCancellation()
        }, { KugouCredentials("42", "TEST-TOKEN") }, ensureDeviceRegistered = {
            try { awaitCancellation() } finally { registrationStopped = true }
        }, homeShelfTimeoutMs = 100)
        val home = withTimeout(1000) { api.home() }
        assertEquals(1, home.daily.size)
        assertTrue(home.newSongs.isEmpty())
        assertTrue(home.history.isEmpty())
        assertTrue(registrationStopped)
        assertNotNull(home.hero)
    }
}
