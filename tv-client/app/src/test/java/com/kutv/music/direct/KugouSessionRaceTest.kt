package com.kutv.music.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouSessionRaceTest {
    private val credentials = KugouCredentials("fixture-user", "fixture-token")
    private val account = KugouAccount("fixture-user", "Fixture")
    private val qr = KugouQrSession("fixture-key", "https://example.test/qr")

    @Test
    fun `cancel during qr creation prevents late pending state`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouQrSession>()
            val manager = KugouSessionManager(Gateway(create = { gate.await() }), MemoryKugouCredentialStore())
            val request = async(start = CoroutineStart.UNDISPATCHED) { manager.beginQr() }
            manager.cancelQr()
            gate.complete(qr)
            assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
            assertEquals(KugouSessionState.LoggedOut, manager.state.value)
        }
    }

    @Test
    fun `older qr creation cannot replace the refreshed qr`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouQrSession>()
            var calls = 0
            val newer = qr.copy(key = "new-key")
            val manager = KugouSessionManager(Gateway(create = { if (calls++ == 0) gate.await() else newer }), MemoryKugouCredentialStore())
            val old = async(start = CoroutineStart.UNDISPATCHED) { manager.beginQr() }
            manager.beginQr()
            gate.complete(qr)
            runCatching { old.await() }
            assertEquals(KugouSessionState.QrPending(newer), manager.state.value)
        }
    }

    @Test
    fun `late scan confirmation after cancel cannot persist credentials`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouQrState>()
            val store = MemoryKugouCredentialStore()
            val manager = KugouSessionManager(Gateway(poll = { gate.await() }), store)
            manager.beginQr()
            val poll = async(start = CoroutineStart.UNDISPATCHED) { manager.pollCurrentQr() }
            manager.cancelQr()
            gate.complete(KugouQrState.Confirmed(credentials))
            runCatching { poll.await() }
            assertEquals(KugouSessionState.LoggedOut, manager.state.value)
            assertNull(store.loadCredentials())
        }
    }

    @Test
    fun `logout during confirmed account lookup cannot resurrect login`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouAccount>()
            val store = MemoryKugouCredentialStore()
            val manager = KugouSessionManager(Gateway(lookup = { gate.await() }), store)
            val login = async(start = CoroutineStart.UNDISPATCHED) { manager.onConfirmed(credentials) }
            manager.logout()
            gate.complete(account)
            runCatching { login.await() }
            assertEquals(KugouSessionState.LoggedOut, manager.state.value)
            assertNull(store.loadCredentials())
            assertNull(store.loadAccount())
        }
    }

    @Test
    fun `logout during restore cannot restore erased credentials`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouAccount>()
            val store = MemoryKugouCredentialStore().apply { save(credentials, account) }
            val manager = KugouSessionManager(Gateway(lookup = { gate.await() }), store)
            val restore = async(start = CoroutineStart.UNDISPATCHED) { manager.restoreSession() }
            manager.logout()
            gate.complete(account)
            runCatching { restore.await() }
            assertEquals(KugouSessionState.LoggedOut, manager.state.value)
            assertNull(store.loadCredentials())
        }
    }

    @Test
    fun `old session expiry cannot erase a newer login`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouAccount>()
            val store = MemoryKugouCredentialStore().apply { save(credentials, account) }
            val newerCredentials = credentials.copy(userId = "new-user", token = "new-fixture-token")
            val newerAccount = account.copy(userId = "new-user")
            val manager = KugouSessionManager(Gateway(lookup = { if (it == credentials) gate.await() else newerAccount }), store)
            val restore = async(start = CoroutineStart.UNDISPATCHED) { manager.restoreSession() }
            manager.onConfirmed(newerCredentials)
            gate.completeExceptionally(KugouNetworkException(KugouNetworkErrorKind.SESSION_EXPIRED))
            runCatching { restore.await() }
            assertEquals(KugouSessionState.LoggedIn(newerAccount), manager.state.value)
            assertEquals(newerCredentials, store.loadCredentials())
        }
    }

    @Test
    fun `cancelling account lookup leaves no partially committed credentials`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouAccount>()
            val store = MemoryKugouCredentialStore()
            val manager = KugouSessionManager(Gateway(lookup = { gate.await() }), store)
            val login = async(start = CoroutineStart.UNDISPATCHED) { manager.onConfirmed(credentials) }
            login.cancel()
            login.join()
            assertNull(store.loadCredentials())
            assertEquals(KugouSessionState.LoggedOut, manager.state.value)
        }
    }

    @Test
    fun `new qr can poll while an abandoned qr poll is still pending`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouQrState>()
            var calls = 0
            val newer = qr.copy(key = "new-key")
            val manager = KugouSessionManager(Gateway(
                create = { if (calls++ == 0) qr else newer },
                poll = { if (it == qr.key) gate.await() else KugouQrState.Scanned },
            ), MemoryKugouCredentialStore())
            manager.beginQr()
            val old = async(start = CoroutineStart.UNDISPATCHED) { manager.pollCurrentQr() }
            manager.beginQr()
            assertEquals(KugouQrState.Scanned, withTimeout(2_000) { manager.pollCurrentQr() })
            gate.complete(KugouQrState.Waiting)
            runCatching { old.await() }
            assertEquals(KugouSessionState.QrPending(newer, KugouQrState.Scanned), manager.state.value)
        }
    }

    @Test
    fun `duplicate poll waits and cannot regress a completed login`() = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<KugouQrState>()
            var polls = 0
            val manager = KugouSessionManager(Gateway(poll = {
                polls += 1
                if (polls == 1) gate.await() else KugouQrState.Waiting
            }), MemoryKugouCredentialStore())
            manager.beginQr()
            val first = async(start = CoroutineStart.UNDISPATCHED) { manager.pollCurrentQr() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { manager.pollCurrentQr() }
            assertEquals(1, polls)
            gate.complete(KugouQrState.Confirmed(credentials))
            withTimeout(2_000) { first.await(); second.await() }
            assertEquals(1, polls)
            assertEquals(KugouSessionState.LoggedIn(account), manager.state.value)
        }
    }

    private inner class Gateway(
        private val create: suspend () -> KugouQrSession = { qr },
        private val poll: suspend (String) -> KugouQrState = { KugouQrState.Waiting },
        private val lookup: suspend (KugouCredentials) -> KugouAccount = { account },
    ) : KugouAuthGateway {
        override suspend fun createQr() = create()
        override suspend fun pollQr(key: String) = poll(key)
        override suspend fun getAccount(credentials: KugouCredentials) = lookup(credentials)
    }
}
