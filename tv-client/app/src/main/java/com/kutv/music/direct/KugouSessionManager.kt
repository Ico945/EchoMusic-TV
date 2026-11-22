package com.kutv.music.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface KugouSessionState {
    data object LoggedOut : KugouSessionState
    data class QrPending(
        val session: KugouQrSession,
        val qrState: KugouQrState = KugouQrState.Waiting,
    ) : KugouSessionState

    data class LoggedIn(val account: KugouAccount) : KugouSessionState
    data object Expired : KugouSessionState
}

class KugouSessionManager(
    private val gateway: KugouAuthGateway,
    private val credentialStore: KugouCredentialStore,
) {
    private val _state = MutableStateFlow<KugouSessionState>(
        credentialStore.loadAccount()?.withPrivacySafeNickname()?.let(KugouSessionState::LoggedIn)
            ?: KugouSessionState.LoggedOut,
    )
    val state: StateFlow<KugouSessionState> = _state.asStateFlow()
    private val stateLock = Any()
    private var generation = 0L
    private var qrFlow: QrFlow? = null

    private class QrFlow(val generation: Long, val polls: Mutex = Mutex())

    suspend fun beginQr(): KugouQrSession {
        val epoch = beginOperation()
        val session = gateway.createQr()
        commit(epoch) {
            qrFlow = QrFlow(epoch)
            _state.value = KugouSessionState.QrPending(session)
        }
        return session
    }

    suspend fun pollCurrentQr(): KugouQrState {
        val flow = synchronized(stateLock) { qrFlow } ?: return KugouQrState.Waiting
        // Serialize polls for one QR, but never let an abandoned QR block a new one.
        return flow.polls.withLock {
            val pending = commit(flow.generation) {
                _state.value as? KugouSessionState.QrPending
            } ?: return@withLock KugouQrState.Waiting
            val qrState = gateway.pollQr(pending.session.key)
            commit(flow.generation) { }
            if (qrState is KugouQrState.Confirmed) {
                confirm(qrState.credentials, flow.generation)
            } else {
                commit(flow.generation) { _state.value = pending.copy(qrState = qrState) }
            }
            qrState
        }
    }

    suspend fun onConfirmed(credentials: KugouCredentials) {
        confirm(credentials, beginOperation())
    }

    private suspend fun confirm(credentials: KugouCredentials, epoch: Long) {
        // Keep credentials request-local until account lookup has completed.
        // Cancellation/logout must not leave a half-committed login on disk.
        val account = try {
            gateway.getAccount(credentials).withPrivacySafeNickname()
        } catch (error: KugouNetworkException) {
            if (error.kind == KugouNetworkErrorKind.SESSION_EXPIRED) {
                commit(epoch) {
                    credentialStore.clear()
                    qrFlow = null
                    _state.value = KugouSessionState.Expired
                }
                throw error
            }
            KugouAccount(
                userId = credentials.userId,
                nickname = KUGOU_PRIVATE_DISPLAY_NAME,
                membership = if (credentials.vipType > 0) "vip" else "none",
            )
        }
        commit(epoch) {
            credentialStore.save(credentials, account)
            qrFlow = null
            _state.value = KugouSessionState.LoggedIn(account)
        }
    }

    suspend fun restoreSession(): KugouAccount? {
        val epoch = beginOperation()
        val credentials = commit(epoch) {
            credentialStore.loadCredentials().also {
                if (it == null) _state.value = KugouSessionState.LoggedOut
            }
        } ?: run {
            return null
        }
        return try {
            gateway.getAccount(credentials).withPrivacySafeNickname().also { account ->
                commit(epoch) {
                    credentialStore.save(credentials, account)
                    _state.value = KugouSessionState.LoggedIn(account)
                }
            }
        } catch (error: KugouNetworkException) {
            commit(epoch) {
                if (error.kind == KugouNetworkErrorKind.SESSION_EXPIRED) {
                    credentialStore.clear()
                    _state.value = KugouSessionState.Expired
                } else {
                    _state.value = credentialStore.loadAccount()
                        ?.withPrivacySafeNickname()
                        ?.let(KugouSessionState::LoggedIn)
                        ?: KugouSessionState.LoggedOut
                }
            }
            null
        }
    }

    fun cancelQr() {
        synchronized(stateLock) {
            generation += 1
            qrFlow = null
            if (_state.value is KugouSessionState.QrPending) {
                _state.value = KugouSessionState.LoggedOut
            }
        }
    }

    /** Expire only the QR that the caller observed, never a refreshed/login state. */
    fun expireQr(expected: KugouSessionState.QrPending) {
        synchronized(stateLock) {
            val pending = _state.value as? KugouSessionState.QrPending ?: return
            if (pending !== expected) return
            generation += 1
            qrFlow = null
            _state.value = KugouSessionState.Expired
        }
    }

    suspend fun logout() {
        currentCoroutineContext().ensureActive()
        synchronized(stateLock) {
            generation += 1
            qrFlow = null
            credentialStore.clear()
            _state.value = KugouSessionState.LoggedOut
        }
    }

    private suspend fun beginOperation(): Long {
        currentCoroutineContext().ensureActive()
        return synchronized(stateLock) {
            generation += 1
            qrFlow = null
            if (_state.value is KugouSessionState.QrPending) _state.value = KugouSessionState.LoggedOut
            generation
        }
    }

    private suspend fun <T> commit(epoch: Long, action: () -> T): T {
        val context = currentCoroutineContext()
        return synchronized(stateLock) {
            context.ensureActive()
            if (epoch != generation) throw CancellationException("Authentication operation superseded")
            action()
        }
    }
}
