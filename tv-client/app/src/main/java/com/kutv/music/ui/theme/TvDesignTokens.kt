package com.kutv.music.ui.theme

/**
 * 设计 token：Apple Music TV 风格 Liquid Glass 视觉系统（spec §5）。
 * 纯 Kotlin 常量（无 Android/Compose 运行时依赖），可在 JVM 与设备测试中断言。
 */
object TvDesignTokens {

    // ---- 色彩（ARGB Long）----
    /** 基础背景：接近黑但不使用绝对纯黑（spec §5.2） */
    const val BG_DEEP = 0xFF080A10L
    /** 抬升面（卡片底） */
    const val BG_RAISED = 0xFF141926L
    /** 抬升面聚焦态 */
    const val BG_RAISED_FOCUSED = 0xFF1E2738L
    /** 玻璃高光面：半透明白（顶部导航等浮层，spec §5.1） */
    const val GLASS_TOP = 0x2EFFFFFFL
    const val GLASS_BOTTOM = 0x14FFFFFFL
    /** 环境光主色（横幅渐变底） */
    const val ACCENT = 0xFF4D8DFFL
    /** 文字三级：主 / 次 / 环境（spec §5.2） */
    const val TEXT_PRIMARY = 0xFFFFFFFFL
    const val TEXT_SECONDARY = 0xFF9AA3B5L
    const val TEXT_TERTIARY = 0xFF5A6270L
    /** 焦点环（spec §5.4） */
    const val FOCUS_RING = 0xFFCBDFF5L
    /** 警告 / 错误 */
    const val WARN = 0xFFFF8F6BL

    // ---- 字号（1080p 等效 sp，spec §5.3）----
    const val PAGE_TITLE_SP = 36f
    const val NAV_SP = 22f
    const val PRIMARY_LABEL_SP = 20f
    const val SECONDARY_LABEL_SP = 18f
    const val METADATA_SP = 16f

    // ---- 圆角（dp）----
    const val RADIUS_CARD_DP = 16f
    const val RADIUS_HERO_DP = 24f
    const val RADIUS_NAV_DP = 12f

    // ---- 间距（dp）----
    /** TV 安全区边距 */
    const val SAFE_MARGIN_DP = 48f
    const val TOP_NAV_HEIGHT_DP = 88f
    const val ROW_SPACING_DP = 24f
    const val COL_SPACING_DP = 16f

    // ---- 焦点动效（spec §5.4）----
    const val FOCUS_SCALE = 1.06f
    const val FOCUS_SHADOW_DP = 12f
    const val FOCUS_TRANSITION_MS = 200L

    // ---- 顶层导航（第一版规格 §4；固定六栏目）----
    val TOP_NAV_ITEMS = listOf("推荐", "新内容", "广播", "资料库", "正在播放", "搜索")

    // ---- 横幅/封面动态调色板（高光/主色/深底 三色渐变，确定性，spec §5.2）----
    val HERO_PALETTES: List<List<Long>> = listOf(
        listOf(0xFF4D8DFFL, 0xFF6A5BFFL, 0xFF0B1120L),
        listOf(0xFFFF6B8DL, 0xFFFFB86BL, 0xFF201018L),
        listOf(0xFF31C4A0L, 0xFF2A7FE4L, 0xFF0A1A1EL),
        listOf(0xFFB388FFL, 0xFF5C6BC0L, 0xFF141020L),
        listOf(0xFFFF9A5CL, 0xFFFF5C8AL, 0xFF201218L),
        listOf(0xFF36D1DCL, 0xFF5B86E5L, 0xFF0A141EL),
    )

    /** 依据 seed（如 track id）确定性选择调色板 */
    fun paletteFor(seed: String): List<Long> {
        val hash = seed.hashCode()
        val absHash = if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash)
        return HERO_PALETTES[absHash % HERO_PALETTES.size]
    }
}
