package com.kutv.music.application.auth

import com.kutv.music.direct.KugouAccount
import com.kutv.music.direct.KugouAuthGateway
import com.kutv.music.direct.KugouCredentials
import com.kutv.music.direct.KugouNetworkErrorKind
import com.kutv.music.direct.KugouNetworkException
import com.kutv.music.direct.KugouQrSession
import com.kutv.music.direct.KugouQrState
import com.kutv.music.direct.KugouSessionManager
import com.kutv.music.direct.MemoryKugouCredentialStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUseCasesTest {
    private val fixedClock = 1_700_000_000_000L

    @Test
    fun `begin qr renders the safe display url and projects pending state`() = runBlocking {
        val gateway = ScriptedGateway()
        val rendered = mutableListOf<String>()
        val useCases = newUseCases(gateway, rendered, fixedClock)

        val projection = useCases.beginQr()

        assertEquals("qrPending", projection.state)
        assertEquals(listOf(gateway.qrSession.qrUrl), rendered)
        assertTrue(projection.qrImageDataUrl!!.startsWith("data:image/png;base64,"))
        assertEquals(fixedClock + gateway.qrSession.expiresInSeconds * 1000L, projection.expiresAtEpochMs)
        assertNull(projection.account)
    }

    @Test
    fun `confirmed qr stores credentials but returns only the sanitized account`() = runBlocking {
        val gateway = ScriptedGateway(
            pollResults = listOf(KugouQrState.Waiting, KugouQrState.Scanned, KugouQrState.Confirmed(KugouCredentials("42", "T-42", vipType = 1))),
            account = KugouAccount("42", "Echo User", membership = "vip"),
        )
        val store = MemoryKugouCredentialStore()
        val useCases = newUseCases(gateway, mutableListOf(), fixedClock, store)

        useCases.beginQr()
        useCases.pollOnce()
        val scanned = useCases.pollOnce()
        val confirmed = useCases.pollOnce()

        assertEquals("scanned", scanned.state)
        assertEquals("loggedIn", confirmed.state)
        assertEquals("Echo User", confirmed.account?.nickname)
        assertTrue(confirmed.qrImageDataUrl == null)
        assertTrue(store.loadCredentials() != null)
    }

    @Test
    fun `expired qr projects the expired state without an image`() = runBlocking {
        val gateway = ScriptedGateway(pollResults = listOf(KugouQrState.Expired))
        val useCases = newUseCases(gateway, mutableListOf(), fixedClock)

        useCases.beginQr()
        val expired = useCases.pollOnce()

        assertEquals("expired", expired.state)
        assertNull(expired.qrImageDataUrl)
    }

    @Test
    fun `cancel returns to logged out and clears the rendered qr cache`() = runBlocking {
        val gateway = ScriptedGateway()
        val useCases = newUseCases(gateway, mutableListOf(), fixedClock)

        useCases.beginQr()
        val cancelled = useCases.cancel()

        assertEquals("loggedOut", cancelled.state)
        assertNull(cancelled.qrImageDataUrl)
    }

    @Test
    fun `restore projects the stored account and logout clears it`() = runBlocking {
        val gateway = ScriptedGateway(account = KugouAccount("7", "恢复用户", membership = "none"))
        val store = MemoryKugouCredentialStore().apply { save(KugouCredentials("7", "T-7")) }
        val useCases = newUseCases(gateway, mutableListOf(), fixedClock, store)

        val restored = useCases.restore()

        assertEquals("loggedIn", restored.state)
        assertEquals("恢复用户", restored.account?.nickname)

        val loggedOut = useCases.logout()
        assertEquals("loggedOut", loggedOut.state)
        assertNull(store.loadCredentials())
    }

    @Test
    fun `failed restore never exposes credentials in the projection`() = runBlocking {
        val gateway = ScriptedGateway(error = KugouNetworkException(KugouNetworkErrorKind.NETWORK))
        val store = MemoryKugouCredentialStore().apply { save(KugouCredentials("7", "T-7")) }
        val useCases = newUseCases(gateway, mutableListOf(), fixedClock, store)

        val projection = useCases.restore()

        assertFalse(projection.toString().contains("T-7"))
    }

    private fun newUseCases(
        gateway: KugouAuthGateway,
        rendered: MutableList<String>,
        clock: Long,
        store: MemoryKugouCredentialStore = MemoryKugouCredentialStore(),
    ): AuthUseCases = AuthUseCases(
        sessionManager = KugouSessionManager(gateway, store),
        qrRenderer = { url -> rendered.add(url); "data:image/png;base64,RElN" },
        clockMs = { clock },
    )

    private class ScriptedGateway(
        val qrSession: KugouQrSession = KugouQrSession("k-1", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&key=SECRET"),
        private val pollResults: List<KugouQrState> = emptyList(),
        private val account: KugouAccount? = null,
        private val error: Exception? = null,
    ) : KugouAuthGateway {
        private var polls = 0
        override suspend fun createQr(): KugouQrSession = qrSession
        override suspend fun pollQr(key: String): KugouQrState {
            val index = polls++
            if (index < pollResults.size) return pollResults[index]
            return KugouQrState.Waiting
        }

        override suspend fun getAccount(credentials: KugouCredentials): KugouAccount {
            error?.let { throw it }
            return checkNotNull(account)
        }
    }
}
