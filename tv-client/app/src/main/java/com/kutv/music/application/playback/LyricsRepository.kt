package com.kutv.music.application.playback

import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.data.MusicRepository

fun interface LyricsRepository {
    suspend fun lyrics(trackId: String): List<TimedLyricLine>
}

class MusicRepositoryLyricsRepository(
    private val repository: MusicRepository,
) : LyricsRepository {
    override suspend fun lyrics(trackId: String): List<TimedLyricLine> =
        repository.lyrics(trackId).getOrThrow()
}
