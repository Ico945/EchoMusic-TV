package com.kutv.music.player

import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.direct.KugouDomainPolicy
import com.kutv.music.direct.KugouPlaybackGateway
import kotlinx.coroutines.CancellationException

class KugouDirectPlaybackResolver(
    private val api: KugouPlaybackGateway,
    private val preferredQuality: () -> String = { "128" },
) : QueueTrackResolver {
    override suspend fun resolve(track: QueueTrack): ResolvedQueueTrack = when (val source = track.source) {
        is PlaybackSource.Local -> ResolvedQueueTrack(track, source.uri, "LOCAL")
        is PlaybackSource.Kugou -> resolveKugou(track, source)
    }

    private suspend fun resolveKugou(
        track: QueueTrack,
        source: PlaybackSource.Kugou,
    ): ResolvedQueueTrack {
        val privilege = api.getPrivilege(source.hash)
        if (!privilege.playable) throw PlaybackResolutionException("当前歌曲不可播放")
        val granted = privilege.qualities.ifEmpty { listOf("128") }.toSet()
        val order = listOf("hires", "flac", "320", "128")
        val preference = if (source.preferredQuality == "auto") preferredQuality() else source.preferredQuality
        val preferredIndex = order.indexOf(preference).takeIf { it >= 0 } ?: order.lastIndex
        val candidates = order.drop(preferredIndex).filter(granted::contains)
            .ifEmpty { order.filter(granted::contains) }
        for (quality in candidates) {
            val playbackHash = privilege.qualityHashes[quality] ?: source.hash
            val stream = try {
                api.getSongUrl(playbackHash, quality)
            } catch (cancelled: CancellationException) {
                // A track switch/service shutdown is not a quality failure.
                // Stop immediately instead of issuing lower-quality requests.
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            val expectedMime = if (quality == "flac" || quality == "hires") "audio/flac" else "audio/mpeg"
            if (stream.mimeType != expectedMime || !KugouDomainPolicy.isAllowedMediaUrl(stream.url)) continue
            return ResolvedQueueTrack(
                track.copy(qualities = order.asReversed().filter(granted::contains)),
                stream.url, quality,
                availableQualities = order.asReversed().filter(granted::contains),
            )
        }
        throw PlaybackResolutionException("没有可用的播放地址")
    }
}
