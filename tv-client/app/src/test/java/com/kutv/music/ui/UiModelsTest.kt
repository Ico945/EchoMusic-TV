package com.kutv.music.ui

import com.kutv.music.core.model.TimedLyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class UiModelsTest {

    @Test
    fun topDestinationsMatchProductNavigationOrder() {
        assertEquals(
            listOf("推荐", "新内容", "广播", "资料库", "正在播放", "搜索"),
            TopDestination.entries.map { it.label },
        )
    }

    @Test
    fun playerModesMatchDemoOrder() {
        assertEquals(
            listOf("封面", "歌词", "队列"),
            PlayerViewMode.entries.map { it.label },
        )
    }

    @Test
    fun activeLyricFollowsPlaybackPositionAndHandlesEmptyInput() {
        val lines = listOf(
            TimedLyricLine(0L, 2_999L, "One"),
            TimedLyricLine(3_000L, 5_999L, "Two"),
            TimedLyricLine(6_000L, 8_999L, "Three"),
        )

        assertEquals(-1, activeLyricIndex(emptyList(), 5_000L))
        assertEquals(0, activeLyricIndex(lines, 0L))
        assertEquals(1, activeLyricIndex(lines, 5_500L))
        assertEquals(2, activeLyricIndex(lines, 9_500L))
    }

    @Test
    fun connectionErrorAlwaysExposesThreeRecoveryActions() {
        assertEquals(
            listOf(ConnectionAction.RETRY, ConnectionAction.EDIT_URL, ConnectionAction.USE_EXPERIENCE),
            ConnectionState.Error("offline").actions,
        )
    }
}
