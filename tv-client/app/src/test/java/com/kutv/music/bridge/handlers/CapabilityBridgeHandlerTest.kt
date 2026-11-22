package com.kutv.music.bridge.handlers

import com.kutv.music.application.capability.InputMethodPort
import com.kutv.music.application.capability.PackageManagerPort
import com.kutv.music.application.capability.PermissionChecker
import com.kutv.music.application.capability.TvCapabilityDetector
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityBridgeHandlerTest {
    @Test
    fun `capability get projects the snapshot shape`() = runBlocking {
        val detector = TvCapabilityDetector(
            packageManager = PackageManagerPort { it == "android.hardware.microphone" },
            inputMethod = InputMethodPort { true },
            permissionChecker = PermissionChecker { false },
        )
        val handlers = CapabilityBridgeHandler(detector).handlers()
        val result = handlers.getValue("capability.get").handle(JSONObject())

        assertEquals("permission-required", result.getString("microphone"))
        assertTrue(result.getBoolean("voiceInput"))
        assertTrue(result.getBoolean("blur"))
    }

    @Test
    fun `capability get rejects a non empty payload`() {
        runBlocking {
            val detector = TvCapabilityDetector(
                packageManager = PackageManagerPort { false },
                inputMethod = InputMethodPort { false },
                permissionChecker = PermissionChecker { false },
            )
            val handlers = CapabilityBridgeHandler(detector).handlers()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { handlers.getValue("capability.get").handle(JSONObject().put("x", 1)) }
            }
        }
    }

    @Test
    fun `capability methods are registered`() {
        assertEquals(setOf("capability.get"), CapabilityBridgeHandler.METHODS)
    }
}
