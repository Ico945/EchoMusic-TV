package com.kutv.music.application.auth

import com.kutv.music.direct.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AuthPollingRecoveryTest {
    private var now = 1_700_000_000_000L
    private val qr = KugouQrSession("fixture-key", "https://example.test/qr", expiresInSeconds = 10)

    @Test
    fun `status reads never extend the original expiry`() = runBlocking {
        val useCases = create(Gateway())
        val initial = useCases.beginQr()
        now += 5_000
        assertEquals(initial.expiresAtEpochMs, useCases.current().expiresAtEpochMs)
    }

    @Test
    fun `transient poll failure recovers without escaping the polling loop`() = runBlocking {
        var polls = 0
        val useCases = create(Gateway(poll = {
            if (polls++ == 0) throw KugouNetworkException(KugouNetworkErrorKind.NETWORK)
            KugouQrState.Scanned
        }))
        val initial = useCases.beginQr()
        assertEquals(initial, useCases.pollOnce())
        assertEquals("scanned", useCases.pollOnce().state)
        assertEquals(2, polls)
    }

    @Test
    fun `local expiry stops polling even when the server is unavailable`() = runBlocking {
        var polls = 0
        val useCases = create(Gateway(poll = {
            polls += 1
            throw KugouNetworkException(KugouNetworkErrorKind.TIMEOUT)
        }))
        useCases.beginQr()
        now += 10_000
        assertEquals("expired", useCases.pollOnce().state)
        assertEquals("expired", useCases.current().state)
        assertEquals("expired", useCases.pollOnce().state)
        assertEquals(0, polls)
    }

    @Test
    fun `scanned projection retains its image and fixed expiry during retry`() = runBlocking {
        var polls = 0
        val useCases = create(Gateway(poll = {
            if (polls++ == 0) KugouQrState.Scanned
            else throw KugouNetworkException(KugouNetworkErrorKind.HTTP)
        }))
        val initial = useCases.beginQr()
        val scanned = useCases.pollOnce()
        now += 3_000
        val retried = useCases.pollOnce()
        assertEquals("scanned", retried.state)
        assertEquals(initial.qrImageDataUrl, scanned.qrImageDataUrl)
        assertEquals(initial.expiresAtEpochMs, retried.expiresAtEpochMs)
    }

    @Test
    fun `explicit server expiry terminates polling and permits refresh`() = runBlocking {
        val useCases = create(Gateway(poll = { throw KugouNetworkException(KugouNetworkErrorKind.SESSION_EXPIRED) }))
        val initial = useCases.beginQr()
        assertEquals("expired", useCases.pollOnce().state)
        assertNull(useCases.current().qrImageDataUrl)
        now += 5_000
        assertEquals(initial.expiresAtEpochMs!! + 5_000, useCases.beginQr().expiresAtEpochMs)
        assertEquals("qrPending", useCases.current().state)
    }

    @Test
    fun `parent cancellation is not treated as a recoverable network error`() = runBlocking {
        val useCases = create(Gateway(poll = { throw CancellationException("fixture cancellation") }))
        useCases.beginQr()
        assertTrue(runCatching { useCases.pollOnce() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `expired qr can be regenerated even when gateway reuses session object`() = runBlocking {
        val useCases = create(Gateway())
        val initial = useCases.beginQr()
        now += 10_000
        assertEquals("expired", useCases.current().state)
        val refreshed = useCases.beginQr()
        assertEquals("qrPending", refreshed.state)
        assertEquals(initial.expiresAtEpochMs!! + 10_000, refreshed.expiresAtEpochMs)
    }

    @Test
    fun `old expiry cannot replace a refreshed qr`() = runBlocking {
        var calls = 0
        val gateway = object : KugouAuthGateway {
            override suspend fun createQr() = qr.copy(key = "fixture-${calls++}")
            override suspend fun pollQr(key: String) = KugouQrState.Waiting
            override suspend fun getAccount(credentials: KugouCredentials) = error("unused")
        }
        val manager = KugouSessionManager(gateway, MemoryKugouCredentialStore())
        manager.beginQr()
        val old = manager.state.value as KugouSessionState.QrPending
        val refreshed = manager.beginQr()
        manager.expireQr(old)
        assertEquals(KugouSessionState.QrPending(refreshed), manager.state.value)
    }

    @Test
    fun `server waiting returned after local deadline becomes expired`() = runBlocking {
        val useCases = create(Gateway(poll = {
            now += 11_000
            KugouQrState.Waiting
        }))
        useCases.beginQr()
        assertEquals("expired", useCases.pollOnce().state)
        assertNull(useCases.current().qrImageDataUrl)
    }

    @Test
    fun `old expiry cannot replace refreshed state even with identical session object`() = runBlocking {
        val manager = KugouSessionManager(Gateway(), MemoryKugouCredentialStore())
        manager.beginQr()
        val old = manager.state.value as KugouSessionState.QrPending
        manager.beginQr()
        val refreshed = manager.state.value
        manager.expireQr(old)
        assertSame(refreshed, manager.state.value)
    }

    private fun create(gateway: KugouAuthGateway) = AuthUseCases(
        KugouSessionManager(gateway, MemoryKugouCredentialStore()),
        qrRenderer = { "data:image/png;base64,RklYVFVSRQ==" },
        clockMs = { now },
    )

    private inner class Gateway(
        private val poll: suspend () -> KugouQrState = { KugouQrState.Waiting },
    ) : KugouAuthGateway {
        override suspend fun createQr() = qr
        override suspend fun pollQr(key: String) = poll()
        override suspend fun getAccount(credentials: KugouCredentials) = error("unused")
    }
}
