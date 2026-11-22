package com.kutv.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Liquid Glass 深色主题（spec §5.2）：
 * 背景接近黑（非纯黑）、玻璃浮层、文字三级、环境光主色。
 */
private val LiquidGlassColorScheme = darkColorScheme(
    primary = Color(TvDesignTokens.ACCENT),
    onPrimary = Color(TvDesignTokens.BG_DEEP),
    primaryContainer = Color(0xFF22314F),
    onPrimaryContainer = Color(TvDesignTokens.TEXT_PRIMARY),
    secondary = Color(TvDesignTokens.TEXT_SECONDARY),
    onSecondary = Color(TvDesignTokens.BG_DEEP),
    background = Color(TvDesignTokens.BG_DEEP),
    onBackground = Color(TvDesignTokens.TEXT_PRIMARY),
    surface = Color(TvDesignTokens.BG_RAISED),
    onSurface = Color(TvDesignTokens.TEXT_PRIMARY),
    surfaceVariant = Color(0xFF1A2130),
    onSurfaceVariant = Color(TvDesignTokens.TEXT_SECONDARY),
    error = Color(TvDesignTokens.WARN),
    onError = Color(TvDesignTokens.BG_DEEP),
)

/** 应用根主题：所有 UI 包在此之下渲染（登录/首页/播放页共享） */
@Composable
fun LiquidGlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LiquidGlassColorScheme,
        content = content,
    )
}
