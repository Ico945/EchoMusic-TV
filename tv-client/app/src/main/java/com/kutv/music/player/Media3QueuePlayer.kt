package com.kutv.music.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.kutv.music.core.model.PlaybackSource
import com.kutv.music.direct.KugouDomainPolicy
import java.net.URI

internal data class ControlledQueueMetadata(
    val quality: String,
    val qualities: List<String>,
    val projectionValues: List<String>,
)

internal fun QueueTrack.toControlledMetadata(quality: String): ControlledQueueMetadata =
    ControlledQueueMetadata(
        quality = quality,
        qualities = qualities.distinct(),
        projectionValues = buildList {
            add(id)
            add(title)
            addAll(artists)
            add(album)
            add(quality)
            addAll(qualities)
            when (val value = source) {
                is PlaybackSource.Local -> add(QueueMetadataKeys.SOURCE_LOCAL)
                is PlaybackSource.Kugou -> {
                    add(QueueMetadataKeys.SOURCE_KUGOU)
                    add(value.trackId)
                    value.albumId?.let(::add)
                    value.lyricId?.let(::add)
                    add(value.preferredQuality)
                }
            }
        },
    )

fun isSupportedPlaybackUri(uri: String, allowCleartext: Boolean): Boolean {
    val scheme = try {
        URI(uri).scheme?.lowercase()
    } catch (_: Exception) {
        null
    }
    return scheme == "android.resource" || scheme == "https" || (allowCleartext && scheme == "http")
}

fun isAllowedResolvedPlaybackUri(item: ResolvedQueueTrack): Boolean = when (item.track.source) {
    is PlaybackSource.Local -> isSupportedPlaybackUri(item.streamUrl, allowCleartext = false)
    is PlaybackSource.Kugou -> KugouDomainPolicy.isAllowedMediaUrl(item.streamUrl)
}

internal fun restorePlayerPaused(player: Player, mediaItem: MediaItem, positionMs: Long) {
    require(positionMs >= 0L)
    player.pause()
    player.setMediaItem(mediaItem)
    player.seekTo(positionMs)
    player.prepare()
}

internal fun replaceCurrentPreservingState(player: Player, replacement: MediaItem) {
    val index = player.currentMediaItemIndex
    check(index in 0 until player.mediaItemCount &&
        player.getMediaItemAt(index).mediaId == replacement.mediaId)
    val position = player.currentPosition.coerceAtLeast(0L)
    val items = (0 until player.mediaItemCount).map {
        if (it == index) replacement else player.getMediaItemAt(it)
    }
    // setMediaItems and prepare preserve playWhenReady. Never call play here:
    // a pause received during URL resolution must still be respected.
    player.setMediaItems(items, index, position)
    player.prepare()
}

class Media3QueuePlayer(
    private val player: Player,
) : QueuePlayerPort {
    override fun currentQuality(trackId: String): String? = player.currentMediaItem
        ?.takeIf { it.mediaId == trackId }?.mediaMetadata?.extras?.getString(QueueMetadataKeys.QUALITY)

    override fun contains(trackId: String): Boolean =
        findIndex(trackId) >= 0

    override fun loadAndPlay(item: ResolvedQueueTrack) {
        player.setMediaItem(item.toMediaItem())
        player.prepare()
        player.play()
    }

    override fun loadPaused(item: ResolvedQueueTrack, positionMs: Long) {
        restorePlayerPaused(player, item.toMediaItem(), positionMs)
    }

    override fun loadSelection(item: ResolvedQueueTrack, positionMs: Long, play: Boolean) {
        player.setMediaItem(item.toMediaItem(), positionMs)
        player.prepare()
        if (play) player.play()
    }

    override fun replaceCurrent(item: ResolvedQueueTrack) {
        replaceCurrentPreservingState(player, item.toMediaItem())
    }

    override fun append(item: ResolvedQueueTrack) {
        if (!contains(item.track.id)) player.addMediaItem(item.toMediaItem())
    }

    override fun prependBeforeCurrent(item: ResolvedQueueTrack) {
        if (contains(item.track.id)) return
        val insertionIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        player.addMediaItem(insertionIndex, item.toMediaItem())
    }

    override fun insertAfterCurrent(item: ResolvedQueueTrack) {
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val insertionIndex = (currentIndex + 1).coerceAtMost(player.mediaItemCount)
        val existingIndex = findIndex(item.track.id)
        when {
            existingIndex < 0 -> player.addMediaItem(insertionIndex, item.toMediaItem())
            existingIndex == currentIndex -> Unit
            existingIndex == insertionIndex -> Unit
            existingIndex < currentIndex -> player.moveMediaItem(existingIndex, currentIndex)
            else -> player.moveMediaItem(existingIndex, insertionIndex.coerceAtMost(player.mediaItemCount - 1))
        }
    }

    override fun seekTo(trackId: String) {
        val index = findIndex(trackId)
        if (index < 0) return
        player.seekToDefaultPosition(index)
    }

    override fun clear() {
        player.clearMediaItems()
    }

    override fun retainCurrent() {
        val index = player.currentMediaItemIndex
        check(index in 0 until player.mediaItemCount)
        if (index + 1 < player.mediaItemCount) player.removeMediaItems(index + 1, player.mediaItemCount)
        if (index > 0) player.removeMediaItems(0, index)
    }

    private fun findIndex(trackId: String): Int =
        (0 until player.mediaItemCount).firstOrNull { index ->
            player.getMediaItemAt(index).mediaId == trackId
        } ?: -1

    private fun ResolvedQueueTrack.toMediaItem(): MediaItem {
        require(isAllowedResolvedPlaybackUri(this)) {
            "播放地址不在允许的媒体来源范围内"
        }
        return track.toSessionMediaItem(quality).buildUpon().setUri(streamUrl).build()
    }
}

/** Public session projection deliberately excludes the resolved URI and source hash. */
internal fun QueueTrack.toSessionMediaItem(
    quality: String = (source as? PlaybackSource.Kugou)?.preferredQuality ?: "LOCAL",
): MediaItem {
        val track = this
        val controlled = track.toControlledMetadata(quality)
        val extras = Bundle().apply {
            putString(QueueMetadataKeys.QUALITY, controlled.quality)
            putStringArrayList(QueueMetadataKeys.QUALITIES, ArrayList(controlled.qualities))
            putString(QueueMetadataKeys.ALBUM, track.album)
            putLong(QueueMetadataKeys.DURATION, track.durationMs)
            when (val source = track.source) {
                is PlaybackSource.Local -> {
                    putString(QueueMetadataKeys.SOURCE_KIND, QueueMetadataKeys.SOURCE_LOCAL)
                }
                is PlaybackSource.Kugou -> {
                    putString(QueueMetadataKeys.SOURCE_KIND, QueueMetadataKeys.SOURCE_KUGOU)
                    putString(QueueMetadataKeys.SOURCE_TRACK_ID, source.trackId)
                    putString(QueueMetadataKeys.SOURCE_ALBUM_ID, source.albumId)
                    putString(QueueMetadataKeys.SOURCE_LYRIC_ID, source.lyricId)
                    putString(QueueMetadataKeys.SOURCE_QUALITY, source.preferredQuality)
                }
            }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.joinToString(" · "))
            .setAlbumTitle(track.album)
            // Media3 does not compare Bundle contents in metadata equality.
            // A meaningful description updates system metadata as well as the
            // private quality extras, without changing the song title or artist.
            .setDescription(when (quality) {
                "128" -> "音质：标准 · 128 kbps"
                "320" -> "音质：高品质 · 320 kbps"
                "flac" -> "音质：无损"
                "LOCAL" -> "音质：本地音源"
                else -> "音质：$quality"
            })
            .setArtworkUri(track.artworkUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .build()
}
