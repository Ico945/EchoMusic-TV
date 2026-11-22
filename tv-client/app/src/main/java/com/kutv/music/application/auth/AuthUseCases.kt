package com.kutv.music.application.auth

import com.kutv.music.direct.KugouAccount
import com.kutv.music.direct.KugouNetworkException
import com.kutv.music.direct.KugouNetworkErrorKind
import com.kutv.music.direct.KugouQrSession
import com.kutv.music.direct.KugouQrState
import com.kutv.music.direct.KugouSessionManager
import com.kutv.music.direct.KugouSessionState

class AuthUseCases(
    private val sessionManager: KugouSessionManager,
    private val qrRenderer: (qrUrl: String) -> String,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class CachedQr(val session: KugouQrSession, val image: String, val expiresAt: Long)
    private var cachedQr: CachedQr? = null

    suspend fun beginQr(): AuthProjection {
        val session = sessionManager.beginQr()
        // A refresh starts a new deadline even if the gateway reuses a URL.
        cachedQr = renderQr(session)
        return current()
    }

    suspend fun pollOnce(): AuthProjection {
        val before = current()
        if (before.state != "qrPending" && before.state != "scanned") return before
        val pending = sessionManager.state.value as? KugouSessionState.QrPending
            ?: return current()
        try {
            sessionManager.pollCurrentQr()
        } catch (error: KugouNetworkException) {
            if (error.kind == KugouNetworkErrorKind.SESSION_EXPIRED) sessionManager.expireQr(pending)
            // Temporary errors retain the last valid projection. The caller's
            // existing 2s interval retries serially, only until the fixed TTL.
            // CancellationException deliberately propagates unchanged.
        }
        return current()
    }

    fun cancel(): AuthProjection {
        cachedQr = null
        sessionManager.cancelQr()
        return current()
    }

    suspend fun restore(): AuthProjection {
        sessionManager.restoreSession()
        return current()
    }

    suspend fun logout(): AuthProjection {
        cachedQr = null
        sessionManager.logout()
        return current()
    }

    fun current(): AuthProjection = when (val state = sessionManager.state.value) {
        is KugouSessionState.LoggedOut -> AuthProjection("loggedOut")
        is KugouSessionState.Expired -> AuthProjection("expired")
        is KugouSessionState.LoggedIn -> AuthProjection("loggedIn", account = state.account.toPublic())
        is KugouSessionState.QrPending -> pendingProjection(state)
    }

    private fun renderQr(session: KugouQrSession) = CachedQr(
        session, qrRenderer(session.qrUrl), clockMs() + session.expiresInSeconds * 1000L,
    )

    private fun pendingProjection(state: KugouSessionState.QrPending): AuthProjection {
        if (state.qrState is KugouQrState.Confirmed) return AuthProjection("loggedIn", account = sessionAccount())
        val cached = cachedQr?.takeIf { it.session === state.session }
            ?: renderQr(state.session).also { cachedQr = it }
        if (state.qrState == KugouQrState.Expired || clockMs() >= cached.expiresAt) {
            sessionManager.expireQr(state)
            cachedQr = null
            return current()
        }
        return AuthProjection(
            state = if (state.qrState == KugouQrState.Scanned) "scanned" else "qrPending",
            qrImageDataUrl = cached.image,
            expiresAtEpochMs = cached.expiresAt,
        )
    }

    private fun sessionAccount(): PublicAccount? =
        (sessionManager.state.value as? KugouSessionState.LoggedIn)?.account?.toPublic()

    private fun KugouAccount.toPublic() = PublicAccount(
        userId = userId,
        nickname = nickname,
        avatar = avatarUrl.orEmpty(),
        membership = membership,
    )
}
