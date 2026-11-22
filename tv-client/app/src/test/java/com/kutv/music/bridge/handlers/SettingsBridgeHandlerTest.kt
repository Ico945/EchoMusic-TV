package com.kutv.music.bridge.handlers

import com.kutv.music.application.settings.TvSettingsRepository
import com.kutv.music.direct.MemoryDirectKeyValueStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBridgeHandlerTest {
    @Test
    fun `settings get projects the six keys with cache bytes`() = runBlocking {
        val handlers = SettingsBridgeHandler(TvSettingsRepository(MemoryDirectKeyValueStore())).handlers()
        val result = handlers.getValue("settings.get").handle(JSONObject())

        val settings = result.getJSONObject("settings")
        assertEquals(false, settings.getBoolean("reducedMotion"))
        assertEquals("128", settings.getString("defaultQuality"))
        assertEquals(100, settings.getInt("lyricFontScale"))
        assertEquals("blur", settings.getString("lyricBackground"))
        assertEquals(1024, settings.getInt("cacheLimitMb"))
        assertEquals(true, settings.getBoolean("compatiblePluginsEnabled"))
        assertEquals(0L, result.getLong("cacheBytes"))
    }

    @Test
    fun `settings update validates payload and returns the updated projection`() {
        runBlocking {
            val handlers = SettingsBridgeHandler(TvSettingsRepository(MemoryDirectKeyValueStore())).handlers()
            val result = handlers.getValue("settings.update")
                .handle(JSONObject("""{"key":"reducedMotion","value":true}"""))

            assertTrue(result.getJSONObject("settings").getBoolean("reducedMotion"))

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    handlers.getValue("settings.update").handle(JSONObject("""{"key":"lyricFontScale","value":999}"""))
                }
            }
        }
    }

    @Test
    fun `settings get rejects a non empty payload`() {
        runBlocking {
            val handlers = SettingsBridgeHandler(TvSettingsRepository(MemoryDirectKeyValueStore())).handlers()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { handlers.getValue("settings.get").handle(JSONObject().put("extra", 1)) }
            }
        }
    }

    @Test
    fun `app clear cache returns zero cache bytes`() = runBlocking {
        val handlers = SettingsBridgeHandler(TvSettingsRepository(MemoryDirectKeyValueStore())).handlers()
        val result = handlers.getValue("app.clearCache").handle(JSONObject())

        assertEquals(0L, result.getLong("cacheBytes"))
    }

    @Test
    fun `settings methods are registered`() {
        assertEquals(
            setOf("settings.get", "settings.update", "app.clearCache"),
            SettingsBridgeHandler.METHODS,
        )
    }
}
