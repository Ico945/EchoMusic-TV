package com.kutv.music.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.kutv.music.core.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy

class Media3QueuePlayerTest {
    @Test fun `retaining current removes both neighbours without replay or seek`() {
        val items = mutableListOf(mediaItem("a"), mediaItem("b"), mediaItem("c"))
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> items.indexOfFirst { it.mediaId == "b" }
                "removeMediaItems" -> { items.subList(args!![0] as Int, args[1] as Int).clear(); null }
                "play", "pause", "prepare", "seekTo", "setMediaItem", "setMediaItems" -> { calls.add(method.name); null }
                else -> defaultValue(method.returnType)
            }
        } as Player
        Media3QueuePlayer(player).retainCurrent()
        assertEquals(listOf("b"), items.map { it.mediaId })
        assertEquals(emptyList<String>(), calls)
    }
    @Test
    fun `restored item is prepared paused and seeks without issuing play`() {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            if (method.name in setOf("pause", "setMediaItem", "seekTo", "prepare", "play")) {
                calls += method.name
            }
            defaultValue(method.returnType)
        } as Player
        val mediaItem = MediaItem.Builder().setMediaId("restore").build()

        restorePlayerPaused(player, mediaItem, 21_000L)

        assertEquals(listOf("pause", "setMediaItem", "seekTo", "prepare"), calls)
        assertFalse("restore must not start audible playback", "play" in calls)
    }

    @Test
    fun `production queue builds media item for scoped Kugou http media`() {
        val mediaUrl = "http://webfs.kugou.com/20260808/song.mp3"
        val resolved = ResolvedQueueTrack(
            track = QueueTrack(
                id = "track-1",
                title = "Track",
                artists = listOf("Artist"),
                artworkUrl = "https://imge.kugou.com/cover.jpg",
                source = PlaybackSource.Kugou("track-1", "HASH"),
            ),
            streamUrl = mediaUrl,
            quality = "128",
        )

        assertEquals(true, isAllowedResolvedPlaybackUri(resolved))
    }

    @Test
    fun `controlled metadata keeps all qualities without resolved stream url`() {
        val resolvedUrl = "https://webfs.kugou.com/private/song.mp3?token=secret"
        val track = QueueTrack(
            id = "track-1",
            title = "Track",
            artists = listOf("Artist"),
            artworkUrl = "https://imge.kugou.com/cover.jpg",
            source = PlaybackSource.Kugou("track-1", "HASH", preferredQuality = "320"),
            qualities = listOf("128", "320", "flac"),
        )

        val metadata = track.toControlledMetadata("320")

        assertEquals(listOf("128", "320", "flac"), metadata.qualities)
        assertEquals("320", metadata.quality)
        assertFalse(metadata.projectionValues.any { resolvedUrl in it || "secret" in it })
    }

    @Test
    fun `insert after current moves existing media item without replay or seek`() {
        val items = mutableListOf(mediaItem("a"), mediaItem("b"), mediaItem("x"))
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> 0
                "getMediaItemAt" -> items[args!![0] as Int]
                "moveMediaItem" -> {
                    val from = args!![0] as Int
                    val to = args[1] as Int
                    items.add(to, items.removeAt(from))
                    calls += "move:$from:$to"
                    null
                }
                "play", "prepare", "seekTo", "seekToDefaultPosition", "setMediaItem" -> {
                    calls += method.name
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as Player
        val queuePlayer = Media3QueuePlayer(player)

        queuePlayer.insertAfterCurrent(
            ResolvedQueueTrack(
                track = QueueTrack(
                    id = "x",
                    title = "X",
                    artists = emptyList(),
                    artworkUrl = "",
                    source = PlaybackSource.Kugou("x", "HASH_X"),
                    qualities = listOf("128", "320"),
                ),
                streamUrl = "https://webfs.kugou.com/x.mp3",
                quality = "320",
            ),
        )

        assertEquals(listOf("a", "x", "b"), items.map(MediaItem::mediaId))
        assertEquals(listOf("move:2:1"), calls)
    }

    @Test
    fun `insert after current accounts for existing item before current`() {
        val items = mutableListOf(mediaItem("x"), mediaItem("a"), mediaItem("b"))
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> 1
                "getMediaItemAt" -> items[args!![0] as Int]
                "moveMediaItem" -> {
                    val from = args!![0] as Int
                    val to = args[1] as Int
                    items.add(to, items.removeAt(from))
                    calls += "move:$from:$to"
                    null
                }
                "play", "prepare", "seekTo", "seekToDefaultPosition", "setMediaItem" -> {
                    calls += method.name
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as Player
        val queuePlayer = Media3QueuePlayer(player)

        queuePlayer.insertAfterCurrent(
            ResolvedQueueTrack(
                track = QueueTrack(
                    id = "x",
                    title = "X",
                    artists = emptyList(),
                    artworkUrl = "",
                    source = PlaybackSource.Kugou("x", "HASH_X"),
                ),
                streamUrl = "https://webfs.kugou.com/x.mp3",
                quality = "128",
            ),
        )

        assertEquals(listOf("a", "x", "b"), items.map(MediaItem::mediaId))
        assertEquals(listOf("move:0:1"), calls)
    }

    private fun mediaItem(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
