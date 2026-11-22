package com.kutv.music.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class Media3QualityReplacementTest {
    @Test fun `replacement reads current position and keeps physical neighbours without play pause or seek`() {
        val items = listOf("a", "b", "c").map { MediaItem.Builder().setMediaId(it).build() }
        val replacement = MediaItem.Builder().setMediaId("b").build()
        val calls = mutableListOf<String>()
        var capturedItems: List<*>? = null
        var capturedIndex = -1
        var capturedPosition = -1L
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItemIndex" -> 1
                "getMediaItemCount" -> 3
                "getMediaItemAt" -> items[args!![0] as Int]
                "getCurrentPosition" -> 47_321L
                "setMediaItems" -> { calls += method.name; capturedItems = args!![0] as List<*>;
                    capturedIndex = args[1] as Int; capturedPosition = args[2] as Long; null }
                "play", "pause", "prepare", "seekTo", "setPlayWhenReady" -> { calls += method.name; null }
                else -> null
            }
        } as Player
        replaceCurrentPreservingState(player, replacement)
        assertEquals(listOf("setMediaItems", "prepare"), calls)
        assertEquals(1, capturedIndex)
        assertEquals(47_321L, capturedPosition)
        assertSame(items[0], capturedItems!![0])
        assertSame(replacement, capturedItems!![1])
        assertSame(items[2], capturedItems!![2])
    }

    @Test fun `replacement refuses an item that is no longer current`() {
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItemIndex" -> 0
                "getMediaItemCount" -> 1
                "getMediaItemAt" -> MediaItem.Builder().setMediaId("new-song").build()
                else -> error("must not mutate")
            }
        } as Player
        assertThrows(IllegalStateException::class.java) {
            replaceCurrentPreservingState(player, MediaItem.Builder().setMediaId("old-song").build())
        }
    }
}
