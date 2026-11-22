package com.kutv.music.data

import com.kutv.music.core.model.HomeContent
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track

interface MusicRepository {
    /** Fast local content that can be rendered while the network home feed is loading. */
    suspend fun bootstrapHome(): HomeContent? = null

    suspend fun home(): Result<HomeContent>
    suspend fun newReleases(): Result<List<Track>>
    suspend fun library(): Result<List<Track>>
    suspend fun search(query: String): Result<List<Track>>
    suspend fun lyrics(trackId: String): Result<List<TimedLyricLine>>
}
