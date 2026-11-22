package com.kutv.music.application.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPluginRegistryTest {
    @Test
    fun `plugin without the tv capability is never listed`() {
        val registry = registryWith(
            "no-tv" to """{"id":"no-tv","version":"1.0.0","entry":"index.js","capabilities":["desktop-v1"],"bridgeScopes":["player.control"]}""",
        )

        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `path traversal directory is rejected`() {
        val registry = TvPluginRegistry(
            assetReader = { path -> if (path == "tv-plugins/good/manifest.json") manifest("good") else null },
            directoryLister = { listOf("../evil", "good") },
        )

        assertEquals(listOf("good"), registry.list().map { it.id })
    }

    @Test
    fun `non local entry is rejected`() {
        val registry = registryWith(
            "bad-entry" to """{"id":"bad-entry","version":"1.0.0","entry":"../escape.js","capabilities":["android-tv-v1"],"bridgeScopes":["player.control"]}""",
        )

        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `duplicate id keeps the first and rejects the later`() {
        val registry = registryWith(
            "first" to manifest("dup"),
            "second" to manifest("dup"),
        )

        assertEquals(listOf("dup"), registry.list().map { it.id })
    }

    @Test
    fun `unknown scope is rejected`() {
        val registry = registryWith(
            "bad-scope" to """{"id":"bad-scope","version":"1.0.0","entry":"index.js","capabilities":["android-tv-v1"],"bridgeScopes":["content.write"]}""",
        )

        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `disabled switch returns empty and retains the reason`() {
        val registry = registryWith(
            "p1" to manifest("p1"),
            enabled = false,
        )

        assertTrue(registry.list().isEmpty())
        assertEquals("compatible-plugins-disabled", registry.disabledReason)
    }

    @Test
    fun `invoke fails for unknown plugin and never executes in v1`() {
        val registry = registryWith(
            "p01" to manifest("p01", scopes = """["player.control"]"""),
        )

        val unknown = registry.invoke("missing", "player.control")
        assertTrue(unknown.isFailure)
        assertTrue(unknown.exceptionOrNull() is UnsupportedOperationException)

        val notWhitelisted = registry.invoke("p01", "library.read")
        assertTrue(notWhitelisted.isFailure)
        assertTrue(notWhitelisted.exceptionOrNull() is IllegalArgumentException)

        val whitelisted = registry.invoke("p01", "player.control")
        assertTrue(whitelisted.isFailure)
        assertTrue(whitelisted.exceptionOrNull() is UnsupportedOperationException)
    }

    private fun registryWith(
        vararg entries: Pair<String, String>,
        enabled: Boolean = true,
    ): TvPluginRegistry {
        val byDirectory = entries.toMap()
        return TvPluginRegistry(
            assetReader = { path ->
                if (path.startsWith("tv-plugins/") && path.endsWith("/manifest.json")) {
                    byDirectory[path.removePrefix("tv-plugins/").removeSuffix("/manifest.json")]
                } else {
                    null
                }
            },
            directoryLister = { path ->
                if (path == "tv-plugins") byDirectory.keys.toList() else emptyList()
            },
            compatiblePluginsEnabled = { enabled },
        )
    }

    private fun manifest(id: String, scopes: String = """["player.control"]"""): String =
        """{"id":"$id","version":"1.0.0","entry":"index.js","capabilities":["android-tv-v1"],"bridgeScopes":$scopes}"""
}
