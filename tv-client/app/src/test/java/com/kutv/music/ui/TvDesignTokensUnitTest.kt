package com.kutv.music.ui

import com.kutv.music.ui.theme.TvDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class TvDesignTokensUnitTest {

    @Test
    fun topNavigationContainsSixDestinationsInProductOrder() {
        assertEquals(
            listOf("推荐", "新内容", "广播", "资料库", "正在播放", "搜索"),
            TvDesignTokens.TOP_NAV_ITEMS,
        )
    }
}
