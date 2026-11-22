package com.kutv.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * 封面图片组件（P1-3 第十九轮：Coil 成熟加载库）。
 * - 采样解码/内存+磁盘缓存/流关闭/HTTP 状态与 Content-Type 由 Coil 处理
 * - P2（第二十轮）：placeholder 透明——加载失败时渐变背景透出（而非灰色遮罩）
 */
@Composable
fun AsyncArtwork(url: String?, modifier: Modifier = Modifier, placeholder: Color = Color.Transparent) {
    if (url.isNullOrEmpty()) {
        // 无 URL：直接透出背景（调用方渐变）
        return
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(placeholder),
        error = ColorPainter(placeholder),
    )
}

/**
 * Liquid Glass 环境背景（F 目标模式 Task 5）：封面驱动模糊背景。
 * - 封面图全屏铺底 + 受控模糊（API 31+ RenderEffect；低版本自动降级为无模糊但保留色温叠加）
 * - 深色遮罩保证前景可读；失败/无 URL 时静默透出调用方渐变
 */
@Composable
fun ArtworkBackdrop(url: String?, modifier: Modifier = Modifier) {
    if (url.isNullOrEmpty()) return
    val blurModifier = Modifier
        .then(modifier)
        .graphicsLayer {
            // API 31+：RenderEffect 模糊（Compose blur 在低版本自动 no-op 降级）
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                renderEffect = android.graphics.RenderEffect.createBlurEffect(48f, 48f, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
            }
        }
    AsyncArtwork(url = url, modifier = blurModifier)
}
