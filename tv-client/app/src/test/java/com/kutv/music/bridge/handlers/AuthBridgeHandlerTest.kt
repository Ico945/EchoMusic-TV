package com.kutv.music.bridge.handlers

import com.kutv.music.application.auth.AuthJsonMapper
import com.kutv.music.application.auth.AuthProjection
import com.kutv.music.application.auth.AuthUseCases
import com.kutv.music.application.auth.PublicAccount
import com.kutv.music.direct.KugouAccount
import com.kutv.music.direct.KugouAuthGateway
import com.kutv.music.direct.KugouCredentials
import com.kutv.music.direct.KugouQrSession
import com.kutv.music.direct.KugouQrState
import com.kutv.music.direct.KugouSessionManager
import com.kutv.music.direct.MemoryKugouCredentialStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthBridgeHandlerTest {
    @Test
    fun `begin qr returns sanitized projection without the session key`() = runBlocking {
        val handlers = handler().handlers()
        val result = handlers.getValue("auth.beginQr").handle(JSONObject())

        assertEquals("qrPending", result.getString("state"))
        assertTrue(result.getString("qrImageDataUrl").startsWith("data:image/png;base64,"))
        assertTrue(result.has("expiresAt"))
        assertFalse(result.toString().contains("SECRET"))
        assertFalse(result.toString().contains("sessionKey"))
    }

    @Test
    fun `begin qr rejects a non-empty payload`() {
        runBlocking {
            val handlers = handler().handlers()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { handlers.getValue("auth.beginQr").handle(JSONObject().put("extra", "x")) }
            }
        }
    }

    @Test
    fun `cancel returns logged out and notifies the flow callback`() = runBlocking {
        var stopped = 0
        var started = 0
        val handlers = handler(onStarted = { started += 1 }, onStopped = { stopped += 1 }).handlers()
        handlers.getValue("auth.beginQr").handle(JSONObject())
        val result = handlers.getValue("auth.cancelQr").handle(JSONObject())

        assertEquals("loggedOut", result.getString("state"))
        assertEquals(1, started)
        assertEquals(1, stopped)
    }

    @Test
    fun `logout clears the session and projects logged out`() = runBlocking {
        val gateway = ScriptedGateway(account = KugouAccount("7", "用户"))
        val store = MemoryKugouCredentialStore().apply { save(KugouCredentials("7", "T-7")) }
        val useCases = AuthUseCases(KugouSessionManager(gateway, store), { "data:image/png;base64,RElN" })
        var playbackCleared = 0
        val handler = AuthBridgeHandler(useCases, onLoggedOut = { playbackCleared += 1 })

        val restored = useCases.restore()
        assertEquals("loggedIn", restored.state)
        val result = handler.handlers().getValue("auth.logout").handle(JSONObject())
        assertEquals("loggedOut", result.getString("state"))
        assertEquals(1, playbackCleared)
    }

    @Test
    fun `auth methods are registered in the allowed method set`() {
        assertEquals(
            setOf("auth.beginQr", "auth.cancelQr", "auth.restore", "auth.logout"),
            AuthBridgeHandler.METHODS,
        )
    }

    private fun handler(
        onStarted: suspend () -> Unit = {},
        onStopped: () -> Unit = {},
    ): AuthBridgeHandler {
        val useCases = AuthUseCases(
            sessionManager = KugouSessionManager(ScriptedGateway(), MemoryKugouCredentialStore()),
            qrRenderer = { "data:image/png;base64,RElN" },
            clockMs = { 1_700_000_000_000L },
        )
        return AuthBridgeHandler(useCases, onStarted, onStopped)
    }

    private class ScriptedGateway(
        private val account: KugouAccount? = null,
    ) : KugouAuthGateway {
        override suspend fun createQr(): KugouQrSession =
            KugouQrSession("k-1", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&key=SECRET")

        override suspend fun pollQr(key: String): KugouQrState = KugouQrState.Waiting
        override suspend fun getAccount(credentials: KugouCredentials): KugouAccount =
            account ?: KugouAccount("0", "用户0")
    }
}

class AuthJsonMapperContractTest {
    @Test
    fun `projection json never contains sensitive fields`() {
        val json = AuthJsonMapper.projection(
            AuthProjection(
                state = "loggedIn",
                account = PublicAccount("42", "Echo User", "", "vip"),
            ),
        )
        val text = json.toString()
        listOf("token", "key", "qrUrl", "secret", "signature", "envelope", "credential").forEach { name ->
            assertFalse("must not contain $name", text.contains(name))
        }
        assertEquals(setOf("userId", "nickname", "avatar", "membership"), json.getJSONObject("account").keys().asSequence().toSet())
    }
}
