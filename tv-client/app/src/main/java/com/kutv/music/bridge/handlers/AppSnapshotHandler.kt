package com.kutv.music.bridge.handlers

import com.kutv.music.application.AppSnapshotUseCase
import com.kutv.music.bridge.BridgeHandler
import com.kutv.music.bridge.mappers.BridgeTrackIds
import org.json.JSONObject

class AppSnapshotHandler(private val useCase: AppSnapshotUseCase) : BridgeHandler {
    override suspend fun handle(payload: JSONObject): JSONObject {
        require(payload.length() == 0)
        return useCase.snapshot().apply {
            optJSONObject("bootstrapHome")?.let(BridgeTrackIds::sanitizeHome)
        }
    }
}
