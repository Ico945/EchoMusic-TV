package com.kutv.music.application.playback

import com.kutv.music.core.model.Track
import com.kutv.music.player.PlaybackQueueRuntime
import com.kutv.music.player.QueueTrack

interface QueueCommands {
    val ownsNavigation: Boolean get() = false
    suspend fun playQueue(trackIds: List<String>, startIndex: Int)
    suspend fun playNext(trackId: String)
    suspend fun select(trackId: String)
    suspend fun next(): Boolean = false
    suspend fun previous(): Boolean = false
    suspend fun editQueue(action: String, trackId: String?): Boolean = false
    suspend fun retryCurrent() {
        throw UnsupportedOperationException("Retry selection is unavailable")
    }
    suspend fun setQuality(trackIds: List<String>, startIndex: Int, quality: String) {
        throw UnsupportedOperationException("Quality selection is unavailable")
    }

    fun synchronize(snapshot: PlayerPortSnapshot) = Unit
}

class PlaybackQueueRuntimeCommands(
    private val trackLookup: suspend (String) -> Track?,
) : QueueCommands {
    override val ownsNavigation = true
    private var queueIds: List<String> = emptyList()
    private var queueIndex: Int = -1

    override suspend fun playQueue(trackIds: List<String>, startIndex: Int) {
        require(trackIds.isNotEmpty() && startIndex in trackIds.indices)
        submit(trackIds, startIndex)
    }

    override suspend fun playNext(trackId: String) {
        require(trackId.isNotBlank())
        val currentId = queueIds.getOrNull(queueIndex)
        val track = trackLookup(trackId) ?: throw IllegalArgumentException("Unknown track")
        val accepted = PlaybackQueueRuntime.enqueueNext(track.toQueueTrack())?.await() ?: false
        check(accepted) { "Playback service is unavailable" }
        val updated = queueIds.toMutableList().apply {
            remove(trackId)
            val current = indexOf(currentId).takeIf { it >= 0 } ?: -1
            add((current + 1).coerceIn(0, size), trackId)
        }
        queueIds = PlaybackQueueRuntime.currentSnapshot().queue.map(QueueTrack::id)
            .takeIf { it.isNotEmpty() }
            ?: updated
        queueIndex = currentId?.let(queueIds::indexOf)?.takeIf { it >= 0 } ?: -1
    }

    override suspend fun select(trackId: String) {
        val selectedIndex = queueIds.indexOf(trackId)
        require(selectedIndex >= 0)
        check(PlaybackQueueRuntime.requestSelect(trackId)?.await() == true) { "Playback service is unavailable" }
    }

    override suspend fun next(): Boolean =
        PlaybackQueueRuntime.requestNext()?.await() ?: false

    override suspend fun previous(): Boolean =
        PlaybackQueueRuntime.requestPrevious()?.await() ?: false

    override suspend fun editQueue(action: String, trackId: String?): Boolean =
        PlaybackQueueRuntime.editQueue(action, trackId)?.await() ?: false

    override suspend fun retryCurrent() {
        require(queueIds.isNotEmpty() && queueIndex in queueIds.indices)
        submit(queueIds, queueIndex)
    }

    override suspend fun setQuality(trackIds: List<String>, startIndex: Int, quality: String) {
        require(trackIds.isNotEmpty() && startIndex in trackIds.indices && quality.isNotBlank())
        val accepted = PlaybackQueueRuntime.setCurrentQuality(trackIds[startIndex], quality)?.await() ?: false
        check(accepted) { "Quality selection is unavailable" }
    }

    override fun synchronize(snapshot: PlayerPortSnapshot) {
        val completeQueueIds = PlaybackQueueRuntime.currentSnapshot().queue.map(QueueTrack::id)
        val currentId = snapshot.currentTrack?.id
        queueIds = completeQueueIds.takeIf { it.isNotEmpty() && currentId in it }
            ?: snapshot.queue.map(Track::id)
        queueIndex = currentId?.let(queueIds::indexOf)?.takeIf { it >= 0 }
            ?: snapshot.queueIndex.takeIf { it in queueIds.indices }
            ?: -1
    }

    private suspend fun submit(trackIds: List<String>, startIndex: Int) {
        submitResolved(resolve(trackIds), startIndex)
    }

    private suspend fun resolve(trackIds: List<String>): List<Track> = trackIds.map { trackId ->
        trackLookup(trackId) ?: throw IllegalArgumentException("Unknown track")
    }

    private suspend fun submitResolved(tracks: List<Track>, startIndex: Int) {
        val accepted = PlaybackQueueRuntime.submitAndPlay(tracks.map { it.toQueueTrack() }, startIndex)
            ?.await()
            ?: false
        check(accepted) { "Playback service is unavailable" }
        queueIds = tracks.map(Track::id)
        queueIndex = startIndex
    }

    private fun Track.toQueueTrack() = QueueTrack(
        id = id,
        title = title,
        artists = artists,
        artworkUrl = artworkUri,
        album = album,
        source = source,
        durationMs = durationMs,
        qualities = qualities,
    )
}
