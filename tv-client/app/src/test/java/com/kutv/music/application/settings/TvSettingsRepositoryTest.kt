package com.kutv.music.application.settings

import com.kutv.music.direct.MemoryDirectKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSettingsRepositoryTest {
    @Test
    fun `defaults are returned for an empty store`() {
        val repository = TvSettingsRepository(MemoryDirectKeyValueStore())
        val settings = repository.get()

        assertEquals(false, settings.reducedMotion)
        assertEquals("128", settings.defaultQuality)
        assertEquals(100, settings.lyricFontScale)
        assertEquals("blur", settings.lyricBackground)
        assertEquals(1024, settings.cacheLimitMb)
        assertEquals(true, settings.compatiblePluginsEnabled)
    }

    @Test
    fun `update persists and reads back round trip`() {
        val store = MemoryDirectKeyValueStore()
        val repository = TvSettingsRepository(store)

        repository.update("reducedMotion", true).getOrThrow()
        repository.update("lyricFontScale", 140).getOrThrow()
        repository.update("lyricBackground", "gradient").getOrThrow()

        val reloaded = TvSettingsRepository(store).get()
        assertEquals(true, reloaded.reducedMotion)
        assertEquals(140, reloaded.lyricFontScale)
        assertEquals("gradient", reloaded.lyricBackground)
    }

    @Test
    fun `all six keys are accepted by the whitelist`() {
        val repository = TvSettingsRepository(MemoryDirectKeyValueStore())

        repository.update("reducedMotion", true).getOrThrow()
        repository.update("defaultQuality", "320").getOrThrow()
        repository.update("lyricFontScale", 80).getOrThrow()
        repository.update("lyricBackground", "gradient").getOrThrow()
        repository.update("cacheLimitMb", 64).getOrThrow()
        repository.update("compatiblePluginsEnabled", false).getOrThrow()

        val settings = repository.get()
        assertEquals(true, settings.reducedMotion)
        assertEquals("320", settings.defaultQuality)
        assertEquals(80, settings.lyricFontScale)
        assertEquals("gradient", settings.lyricBackground)
        assertEquals(64, settings.cacheLimitMb)
        assertEquals(false, settings.compatiblePluginsEnabled)
    }

    @Test
    fun `unknown key is rejected without persisting`() {
        val store = MemoryDirectKeyValueStore()
        val repository = TvSettingsRepository(store)

        val result = repository.update("desktopShortcuts", true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(TvSettings(), TvSettingsRepository(store).get())
    }

    @Test
    fun `invalid value is rejected without persisting`() {
        val store = MemoryDirectKeyValueStore()
        val repository = TvSettingsRepository(store)

        val cases = listOf<Pair<String, Any?>>(
            "reducedMotion" to "not-a-bool",
            "defaultQuality" to "999",
            "lyricFontScale" to 40,
            "lyricFontScale" to 200,
            "lyricBackground" to "solid",
            "cacheLimitMb" to 32,
            "cacheLimitMb" to 5000,
            "compatiblePluginsEnabled" to "yes",
        )
        for ((key, value) in cases) {
            val result = repository.update(key, value)
            assertTrue("$key=$value should fail", result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

        assertEquals(TvSettings(), TvSettingsRepository(store).get())
    }

    @Test
    fun `invalid stored value falls back to defaults`() {
        val store = MemoryDirectKeyValueStore()
        store.put("tv.settings.lyricFontScale", "not-a-number")
        store.put("tv.settings.cacheLimitMb", "99999")
        store.put("tv.settings.lyricBackground", "unknown")

        val settings = TvSettingsRepository(store).get()
        assertEquals(100, settings.lyricFontScale)
        assertEquals(1024, settings.cacheLimitMb)
        assertEquals("blur", settings.lyricBackground)
    }
}
