package com.kutv.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kutv.music.ui.theme.TvDesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设计 token 锁定测试（Task 1 · 测试先行）。
 * 依据 spec §5（Apple Music TV · Liquid Glass 视觉系统）锁定：
 *  - 顶部导航 6 栏目顺序（推荐/新内容/广播/资料库/正在播放/搜索）
 *  - 焦点缩放 1.06 + 光晕阴影
 *  - 背景接近黑但不纯黑；玻璃浮层半透明
 *  - 字号/圆角/间距满足三米观看距离
 *  - 横幅渐变调色板确定性且以深色收尾
 * （androidTest：可访问 main 源码的 TvDesignTokens 常量。）
 */
@RunWith(AndroidJUnit4::class)
class TvDesignTokensTest {

    @Test
    fun topNavHasSixTabsInSpecOrder() {
        assertEquals(
            listOf("推荐", "新内容", "广播", "资料库", "正在播放", "搜索"),
            TvDesignTokens.TOP_NAV_ITEMS,
        )
    }

    @Test
    fun focusScaleIsPointZeroSix() {
        assertEquals("焦点放大必须为 1.06", 1.06f, TvDesignTokens.FOCUS_SCALE, 0.0001f)
    }

    @Test
    fun focusShadowProvidesGlow() {
        assertTrue("焦点光晕阴影应 >= 8dp", TvDesignTokens.FOCUS_SHADOW_DP >= 8f)
        assertTrue("焦点过渡应 >= 150ms", TvDesignTokens.FOCUS_TRANSITION_MS >= 150L)
    }

    @Test
    fun backgroundIsNearBlackNotPureBlack() {
        val c = TvDesignTokens.BG_DEEP
        assertNotEquals("背景不得使用绝对纯黑", 0xFF000000L, c)
        val r = ((c shr 16) and 0xFFL).toInt()
        val g = ((c shr 8) and 0xFFL).toInt()
        val b = (c and 0xFFL).toInt()
        assertTrue("背景应为接近黑的深色（RGB 各通道 < 0x40）", r < 0x40 && g < 0x40 && b < 0x40)
    }

    @Test
    fun glassOverlayIsTranslucent() {
        val alpha = ((TvDesignTokens.GLASS_TOP shr 24) and 0xFFL).toInt()
        assertTrue("玻璃高光应为半透明（alpha 0x10..0x7F）", alpha in 0x10..0x7F)
    }

    @Test
    fun focusRingIsVisible() {
        val alpha = ((TvDesignTokens.FOCUS_RING shr 24) and 0xFFL).toInt()
        assertTrue("焦点环必须可见（alpha > 0）", alpha > 0)
    }

    @Test
    fun secondaryLabelMeetsTvMinimumSize() {
        assertTrue("次要标签不得小于 18sp（1080p 等效）", TvDesignTokens.SECONDARY_LABEL_SP >= 18f)
    }

    @Test
    fun pageTitleIsTvScale() {
        assertTrue("页面标题应适配三米观看距离（>= 32sp）", TvDesignTokens.PAGE_TITLE_SP >= 32f)
        assertTrue("导航文字应 >= 20sp", TvDesignTokens.NAV_SP >= 20f)
    }

    @Test
    fun cardRadiusMeetsLiquidGlass() {
        assertTrue("卡片圆角应 >= 12dp", TvDesignTokens.RADIUS_CARD_DP >= 12f)
        assertTrue("横幅圆角应 >= 20dp", TvDesignTokens.RADIUS_HERO_DP >= 20f)
    }

    @Test
    fun safeMarginsFitTvLayout() {
        assertTrue("TV 安全区边距应 >= 32dp", TvDesignTokens.SAFE_MARGIN_DP >= 32f)
        assertTrue("顶部导航高度应 >= 72dp", TvDesignTokens.TOP_NAV_HEIGHT_DP >= 72f)
    }

    @Test
    fun heroPalettesAreDeterministic() {
        assertTrue("横幅调色板不得为空", TvDesignTokens.HERO_PALETTES.isNotEmpty())
        TvDesignTokens.HERO_PALETTES.forEach { palette ->
            assertEquals("每组渐变 3 色（高光/主色/深底）", 3, palette.size)
            val bottom = palette[2]
            val r = ((bottom shr 16) and 0xFFL).toInt()
            val g = ((bottom shr 8) and 0xFFL).toInt()
            val b = (bottom and 0xFFL).toInt()
            assertTrue("渐变末尾应为深色底（保证文字对比度）", r < 0x40 && g < 0x40 && b < 0x40)
            assertTrue("底色的 alpha 必须不透明", ((bottom shr 24) and 0xFFL).toInt() == 0xFF)
        }
        assertEquals(
            "同一 seed 恒返回同一调色板（确定性）",
            TvDesignTokens.paletteFor("track-1"),
            TvDesignTokens.paletteFor("track-1"),
        )
    }

    @Test
    fun textColorsAreThreeTiered() {
        assertNotEquals(TvDesignTokens.TEXT_PRIMARY, TvDesignTokens.TEXT_SECONDARY)
        assertNotEquals(TvDesignTokens.TEXT_SECONDARY, TvDesignTokens.TEXT_TERTIARY)
    }
}
