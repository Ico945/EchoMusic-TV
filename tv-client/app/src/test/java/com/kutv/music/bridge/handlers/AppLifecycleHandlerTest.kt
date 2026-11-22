package com.kutv.music.bridge.handlers

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLifecycleHandlerTest {
    @Test
    fun `exit accepts only an empty payload and invokes the native callback`() = runBlocking {
        var exits = 0
        val handler = AppLifecycleHandler(exit = { exits += 1 }).handlers().getValue("app.exit")

        val result = handler.handle(JSONObject())

        assertEquals(1, exits)
        assertTrue(result.getBoolean("accepted"))
    }

    @Test
    fun `IME commands invoke their exact native callbacks`() = runBlocking {
        var shows = 0
        var hides = 0
        val handlers = AppLifecycleHandler(
            exit = {},
            showIme = { shows += 1 },
            hideIme = { hides += 1 },
        ).handlers()

        assertTrue(handlers.getValue("app.showIme").handle(JSONObject()).getBoolean("accepted"))
        assertTrue(handlers.getValue("app.hideIme").handle(JSONObject()).getBoolean("accepted"))
        assertEquals(1, shows)
        assertEquals(1, hides)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `exit rejects unexpected payload fields`() {
        runBlocking {
            val handler = AppLifecycleHandler(exit = {}).handlers().getValue("app.exit")
            handler.handle(JSONObject().put("reason", "remote"))
        }
    }
}
