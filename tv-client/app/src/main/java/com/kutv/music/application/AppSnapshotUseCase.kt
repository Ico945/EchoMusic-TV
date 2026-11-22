package com.kutv.music.application

import com.kutv.music.direct.KugouSessionManager
import com.kutv.music.direct.KugouSessionState
import org.json.JSONObject

class AppSnapshotUseCase(
    private val homeUseCase: HomeUseCase,
    private val sessionManager: KugouSessionManager,
) {
    suspend fun snapshot(): JSONObject = JSONObject()
        .put("route", "home")
        .put("session", sessionDto(sessionManager.state.value))
        .apply { homeUseCase.bootstrapHome()?.let { put("bootstrapHome", it) } }

    private fun sessionDto(state: KugouSessionState): JSONObject = when (state) {
        KugouSessionState.LoggedOut -> JSONObject().put("status", "loggedOut")
        KugouSessionState.Expired -> JSONObject().put("status", "expired")
        is KugouSessionState.QrPending -> JSONObject().put("status", "qrPending")
        is KugouSessionState.LoggedIn -> JSONObject()
            .put("status", "loggedIn")
            .put("displayName", state.account.nickname)
            .put("membership", state.account.membership)
    }
}
