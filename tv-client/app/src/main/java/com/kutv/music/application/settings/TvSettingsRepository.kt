package com.kutv.music.application.settings

import com.kutv.music.direct.DirectKeyValueStore

data class TvSettings(
    val reducedMotion: Boolean = false,
    val defaultQuality: String = "128",
    val lyricFontScale: Int = 100,
    val lyricBackground: String = "blur",
    val cacheLimitMb: Int = 1024,
    val compatiblePluginsEnabled: Boolean = true,
)

class TvSettingsRepository(private val store: DirectKeyValueStore) {
    fun get(): TvSettings = TvSettings(
        reducedMotion = readBoolean(KEY_REDUCED_MOTION, false),
        defaultQuality = readQuality(),
        lyricFontScale = readInt(KEY_LYRIC_FONT_SCALE, 100, 80, 160),
        lyricBackground = readBackground(),
        cacheLimitMb = readInt(KEY_CACHE_LIMIT_MB, 1024, 64, 4096),
        compatiblePluginsEnabled = readBoolean(KEY_COMPATIBLE_PLUGINS_ENABLED, true),
    )

    fun update(key: String, value: Any?): Result<TvSettings> = runCatching {
        val current = get()
        val updated = applyValue(current, key, value)
        persist(updated)
        updated
    }

    private fun applyValue(current: TvSettings, key: String, value: Any?): TvSettings = when (key) {
        KEY_REDUCED_MOTION -> current.copy(reducedMotion = requireBoolean(value, key))
        KEY_DEFAULT_QUALITY -> current.copy(defaultQuality = requireQuality(value))
        KEY_LYRIC_FONT_SCALE -> current.copy(lyricFontScale = requireIntInRange(value, 80, 160, key))
        KEY_LYRIC_BACKGROUND -> current.copy(lyricBackground = requireBackground(value))
        KEY_CACHE_LIMIT_MB -> current.copy(cacheLimitMb = requireIntInRange(value, 64, 4096, key))
        KEY_COMPATIBLE_PLUGINS_ENABLED -> current.copy(compatiblePluginsEnabled = requireBoolean(value, key))
        else -> throw IllegalArgumentException("未知设置项: $key")
    }

    private fun persist(settings: TvSettings) {
        store.put(storageKey(KEY_REDUCED_MOTION), settings.reducedMotion.toString())
        store.put(storageKey(KEY_DEFAULT_QUALITY), settings.defaultQuality)
        store.put(storageKey(KEY_LYRIC_FONT_SCALE), settings.lyricFontScale.toString())
        store.put(storageKey(KEY_LYRIC_BACKGROUND), settings.lyricBackground)
        store.put(storageKey(KEY_CACHE_LIMIT_MB), settings.cacheLimitMb.toString())
        store.put(storageKey(KEY_COMPATIBLE_PLUGINS_ENABLED), settings.compatiblePluginsEnabled.toString())
    }

    private fun readBoolean(key: String, default: Boolean): Boolean = when (store.get(storageKey(key))) {
        "true", "1" -> true
        "false", "0" -> false
        else -> default
    }

    private fun readQuality(): String = store.get(storageKey(KEY_DEFAULT_QUALITY))
        ?.takeIf { it in QUALITY_OPTIONS } ?: DEFAULT_QUALITY

    private fun readBackground(): String = store.get(storageKey(KEY_LYRIC_BACKGROUND))
        ?.takeIf { it in BACKGROUND_OPTIONS } ?: DEFAULT_BACKGROUND

    private fun readInt(key: String, default: Int, min: Int, max: Int): Int =
        store.get(storageKey(key))?.toIntOrNull()?.takeIf { it in min..max } ?: default

    private fun requireBoolean(value: Any?, key: String): Boolean {
        require(value is Boolean) { "$key 必须是布尔值" }
        return value
    }

    private fun requireQuality(value: Any?): String {
        require(value is String && value in QUALITY_OPTIONS) { "defaultQuality 无效" }
        return value
    }

    private fun requireBackground(value: Any?): String {
        require(value is String && value in BACKGROUND_OPTIONS) { "lyricBackground 无效" }
        return value
    }

    private fun requireIntInRange(value: Any?, min: Int, max: Int, key: String): Int {
        val int = when (value) {
            is Int -> value
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
            else -> null
        }
        require(int != null && int in min..max) { "$key 必须在 $min..$max" }
        return int
    }

    private fun storageKey(key: String): String = "$STORAGE_PREFIX$key"

    companion object {
        const val KEY_REDUCED_MOTION = "reducedMotion"
        const val KEY_DEFAULT_QUALITY = "defaultQuality"
        const val KEY_LYRIC_FONT_SCALE = "lyricFontScale"
        const val KEY_LYRIC_BACKGROUND = "lyricBackground"
        const val KEY_CACHE_LIMIT_MB = "cacheLimitMb"
        const val KEY_COMPATIBLE_PLUGINS_ENABLED = "compatiblePluginsEnabled"

        val KEYS = setOf(
            KEY_REDUCED_MOTION,
            KEY_DEFAULT_QUALITY,
            KEY_LYRIC_FONT_SCALE,
            KEY_LYRIC_BACKGROUND,
            KEY_CACHE_LIMIT_MB,
            KEY_COMPATIBLE_PLUGINS_ENABLED,
        )

        val QUALITY_OPTIONS = setOf("128", "320", "flac", "high", "super")
        val BACKGROUND_OPTIONS = setOf("blur", "gradient")
        const val DEFAULT_QUALITY = "128"
        const val DEFAULT_BACKGROUND = "blur"
        private const val STORAGE_PREFIX = "tv.settings."
    }
}
