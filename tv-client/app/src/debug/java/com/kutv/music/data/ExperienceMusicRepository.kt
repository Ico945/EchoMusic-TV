package com.kutv.music.data

import com.kutv.music.core.model.Capability
import com.kutv.music.core.model.HomeContent
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.core.model.Shelf
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track

/**
 * Debug-only deterministic catalogue used by instrumentation test activities.
 *
 * This class intentionally lives outside src/main so production builds cannot
 * bootstrap or silently fall back to experience content.
 */
class ExperienceMusicRepository(
    resourceBaseUri: String,
) : MusicRepository {
    private val normalizedResourceBase = resourceBaseUri.trimEnd('/')

    private val tracks = listOf(
        track("experience-1", "Aurora Drift", "Luma Fields", "Night Bloom", "cover_aurora", "track_1", "experience-aurora"),
        track("experience-2", "Velvet Sky", "Mira Vale", "Afterglow", "cover_velvet", "track_2"),
        track("experience-3", "Neon Tides", "Northline", "Glass Horizon", "cover_neon", "track_3"),
        track("experience-4", "Midnight Glass", "Solace Club", "City Reflections", "cover_aurora", "track_1"),
        track("experience-5", "Quiet Signals", "Harbor Lights", "Low Frequency", "cover_velvet", "track_2"),
    )

    private val lyricMap = tracks.associate { track ->
        track.id to listOf(
            "Debug lyric one",
            "Debug lyric two",
            "Debug lyric three",
            "Debug lyric four",
            "Debug lyric five",
            "Debug lyric six",
            "Debug lyric seven",
            "Debug lyric eight",
        ).mapIndexed { index, text ->
            val start = index * 1_500L
            TimedLyricLine(startMs = start, endMs = start + 1_499L, text = text)
        }
    }

    override suspend fun home(): Result<HomeContent> = Result.success(
        HomeContent(
            hero = tracks.first(),
            secondary = tracks.drop(1),
            shelves = listOf(
                Shelf("debug-recent", "Debug recent", tracks, Capability.AVAILABLE),
                Shelf("debug-recommended", "Debug recommended", tracks.reversed(), Capability.AVAILABLE),
            ),
        ),
    )

    override suspend fun newReleases(): Result<List<Track>> = Result.success(tracks)

    override suspend fun library(): Result<List<Track>> = Result.success(tracks.reversed())

    override suspend fun search(query: String): Result<List<Track>> {
        val needle = query.trim()
        if (needle.isEmpty()) return Result.success(emptyList())
        return Result.success(
            tracks.filter { track ->
                track.title.contains(needle, ignoreCase = true) ||
                    track.album.contains(needle, ignoreCase = true) ||
                    track.artists.any { it.contains(needle, ignoreCase = true) }
            },
        )
    }

    override suspend fun lyrics(trackId: String): Result<List<TimedLyricLine>> =
        Result.success(lyricMap[trackId].orEmpty())

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String,
        artwork: String,
        audio: String,
        mvId: String? = null,
    ) = Track(
        id = id,
        title = title,
        artists = listOf(artist),
        album = album,
        artworkUri = "$normalizedResourceBase/drawable/$artwork",
        durationMs = 12_000L,
        qualities = listOf("LOCAL"),
        source = PlaybackSource.Local("$normalizedResourceBase/raw/$audio"),
        mvId = mvId,
    )
}
