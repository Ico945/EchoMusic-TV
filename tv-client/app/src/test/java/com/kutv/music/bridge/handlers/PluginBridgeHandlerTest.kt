package com.kutv.music.bridge.handlers

import com.kutv.music.application.plugins.TvPluginRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBridgeHandlerTest {
    @Test
    fun `plugins list projects sanitized entries without entry path`() = runBlocking {
        val registry = TvPluginRegistry(
            assetReader = { path -> if (path == "tv-plugins/p01/manifest.json") manifest("p01") else null },
            directoryLister = { listOf("p01") },
        )
        val handlers = PluginBridgeHandler(registry).handlers()
        val result = handlers.getValue("plugins.list").handle(JSONObject())

        val plugins = result.getJSONArray("plugins")
        assertEquals(1, plugins.length())
        val plugin = plugins.getJSONObject(0)
        assertEquals("p01", plugin.getString("id"))
        assertEquals("1.0.0", plugin.getString("version"))
        assertTrue(!plugin.has("entry"))
        assertTrue(plugin.getJSONArray("capabilities").toString().contains("android-tv-v1"))
    }

    @Test
    fun `plugins invoke validates payload`() {
        runBlocking {
            val registry = TvPluginRegistry(
                assetReader = { null },
                directoryLister = { emptyList() },
            )
            val handlers = PluginBridgeHandler(registry).handlers()

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { handlers.getValue("plugins.invoke").handle(JSONObject("""{"id":"x"}""")) }
            }
            assertThrows(UnsupportedOperationException::class.java) {
                runBlocking {
                    handlers.getValue("plugins.invoke").handle(JSONObject("""{"id":"x","action":"player.control"}"""))
                }
            }
        }
    }

    @Test
    fun `plugin methods are registered`() {
        assertEquals(setOf("plugins.list", "plugins.invoke"), PluginBridgeHandler.METHODS)
    }

    private fun manifest(id: String): String =
        """{"id":"$id","version":"1.0.0","entry":"index.js","capabilities":["android-tv-v1"],"bridgeScopes":["player.control"]}"""
}
