package com.kutv.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * TV 焦点动效（spec §5.4）：光晕阴影 + 轻微放大（FOCUS_SCALE=1.06）。
 * 组件通过 onFocusChanged 驱动 isFocused，此处只负责绘制层变换；
 * 焦点环/描边由组件自身的 border / 容器色变化补充。
 */
fun Modifier.tvFocusScale(isFocused: Boolean): Modifier =
    this
        .shadow(
            elevation = if (isFocused) TvDesignTokens.FOCUS_SHADOW_DP.dp else 0.dp,
            shape = RoundedCornerShape(TvDesignTokens.RADIUS_CARD_DP.dp),
            ambientColor = Color(TvDesignTokens.FOCUS_RING).copy(alpha = 0.30f),
            spotColor = Color(TvDesignTokens.FOCUS_RING).copy(alpha = 0.45f),
        )
        .graphicsLayer {
            val scale = if (isFocused) TvDesignTokens.FOCUS_SCALE else 1f
            scaleX = scale
            scaleY = scale
        }
