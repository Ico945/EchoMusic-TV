package com.kutv.music.core.model

enum class Capability {
    AVAILABLE,
    PREVIEW,
    LOGIN_REQUIRED,
    UNAVAILABLE,
}

sealed interface PlaybackSource {
    data class Local(val uri: String) : PlaybackSource

    data class Kugou(
        val trackId: String,
        val hash: String,
        val albumId: String? = null,
        val lyricId: String? = null,
        val preferredQuality: String = "128",
    ) : PlaybackSource
}

data class Track(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val artworkUri: String,
    val durationMs: Long,
    val qualities: List<String>,
    val source: PlaybackSource,
    val mvId: String? = null,
)

data class TimedLyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class Shelf(
    val id: String,
    val title: String,
    val tracks: List<Track>,
    val capability: Capability,
)

data class HomeContent(
    val hero: Track,
    val secondary: List<Track>,
    val shelves: List<Shelf>,
)
