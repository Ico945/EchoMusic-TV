package com.kutv.music.bridge.handlers

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppLicenseHandlerTest {
    @Test fun `license read is limited to two bundled texts`() = runBlocking {
        val read = mutableListOf<String>()
        val handler = AppLifecycleHandler({}, readLicense = { read.add(it); "License text" }).handlers().getValue("app.readLicense")
        assertEquals("License text", handler.handle(JSONObject().put("kind", "gpl")).getString("text"))
        handler.handle(JSONObject().put("kind", "notices"))
        assertEquals(listOf("EchoMusic-GPL-3.0.txt", "THIRD_PARTY_NOTICES.md"), read)
        for (payload in listOf(JSONObject().put("kind", "../../private"), JSONObject().put("kind", "gpl").put("path", "private"), JSONObject())) {
            assertTrue(runCatching { handler.handle(payload) }.isFailure)
        }
        assertEquals(2, read.size)
    }
}
