package com.kutv.music.direct

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouSessionManagerTest {
    @Test
    fun `confirmed login survives manager recreation`() = runBlocking {
        val store = MemoryKugouCredentialStore()
        val gateway = FakeGateway(KugouAccount("42", "概念用户", membership = "vip"))
        val credentials = KugouCredentials("42", "T-42", vipType = 1)
        val first = KugouSessionManager(gateway, store)

        first.onConfirmed(credentials)
        val restored = KugouSessionManager(gateway, store).restoreSession()

        assertEquals("42", restored?.userId)
        assertEquals("概念用户", restored?.nickname)
        assertEquals(KugouSessionState.LoggedIn(restored!!), first.state.value)
    }

    @Test
    fun `network failure keeps credentials but confirmed expiry clears them`() = runBlocking {
        val store = MemoryKugouCredentialStore().apply {
            save(KugouCredentials("42", "T-42"))
        }
        val networkManager = KugouSessionManager(
            FakeGateway(error = KugouNetworkException(KugouNetworkErrorKind.NETWORK)),
            store,
        )
        assertNull(networkManager.restoreSession())
        assertEquals("T-42", store.loadCredentials()?.token)

        val expiredManager = KugouSessionManager(
            FakeGateway(error = KugouNetworkException(KugouNetworkErrorKind.SESSION_EXPIRED)),
            store,
        )
        assertNull(expiredManager.restoreSession())
        assertNull(store.loadCredentials())
    }

    @Test
    fun `confirmed login network fallback does not expose numeric user id`() = runBlocking {
        val store = MemoryKugouCredentialStore()
        val manager = KugouSessionManager(
            FakeGateway(error = KugouNetworkException(KugouNetworkErrorKind.NETWORK)),
            store,
        )

        manager.onConfirmed(KugouCredentials("408615481", "T-42"))

        val account = (manager.state.value as KugouSessionState.LoggedIn).account
        assertEquals(KUGOU_PRIVATE_DISPLAY_NAME, account.nickname)
        assertEquals(KUGOU_PRIVATE_DISPLAY_NAME, store.loadAccount()?.nickname)
    }

    @Test
    fun `logout clears credentials and account state`() = runBlocking {
        val store = MemoryKugouCredentialStore()
        val manager = KugouSessionManager(FakeGateway(KugouAccount("42", "用户")), store)
        manager.onConfirmed(KugouCredentials("42", "token"))

        manager.logout()

        assertEquals(KugouSessionState.LoggedOut, manager.state.value)
        assertNull(store.loadCredentials())
    }

    private class FakeGateway(
        private val account: KugouAccount? = null,
        private val error: Exception? = null,
    ) : KugouAuthGateway {
        override suspend fun createQr(): KugouQrSession = error("unused")
        override suspend fun pollQr(key: String): KugouQrState = error("unused")
        override suspend fun getAccount(credentials: KugouCredentials): KugouAccount {
            error?.let { throw it }
            return checkNotNull(account)
        }
    }
}
