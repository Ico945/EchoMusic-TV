package com.kutv.music.bridge.handlers

import com.kutv.music.application.HomeUseCase
import com.kutv.music.bridge.BridgeHandler
import com.kutv.music.bridge.mappers.BridgeTrackIds
import org.json.JSONObject

class HomeHandler(private val useCase: HomeUseCase) : BridgeHandler {
    override suspend fun handle(payload: JSONObject): JSONObject {
        require(payload.length() == 0)
        return BridgeTrackIds.sanitizeHome(useCase.home())
    }
}
