package com.kutv.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.kutv.music.ui.theme.TvDesignTokens

/**
 * 顶层导航（spec §4 顶部导航 + §5.1 玻璃材质规则）：
 * 半透明玻璃底、6 个栏目（推荐/新内容/广播/资料库/搜索/正在播放）。
 * 每个栏目为 TV Material Card：焦点态放大 1.06 + 焦点环 + 光晕，选中态高亮。
 */
@Composable
fun TopNav(selected: String, onSelect: (String) -> Unit) {
    val destination = TopDestination.entries.firstOrNull { it.label == selected } ?: TopDestination.HOME
    TopNav(
        selected = destination,
        accountLabel = "登录",
        onDestinationChange = { onSelect(it.label) },
        onAccountClick = {},
    )
}

@Composable
fun TopNav(
    selected: TopDestination,
    accountLabel: String,
    onDestinationChange: (TopDestination) -> Unit,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
    searchNavFocusRequester: FocusRequester? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(74.dp),
        shape = RoundedCornerShape(24.dp),
        colors = SurfaceDefaults.colors(
            containerColor = Color(0xB51B1D24),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "KU·TV",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            TopDestination.entries.forEach { destination ->
                TopNavItem(
                    title = destination.label,
                    selected = destination == selected,
                    onClick = { onDestinationChange(destination) },
                    modifier = Modifier
                        .testTag("top-nav-${destination.name}")
                        .let { base ->
                            if (destination == TopDestination.SEARCH && searchNavFocusRequester != null) {
                                base.focusRequester(searchNavFocusRequester)
                            } else {
                                base
                            }
                        },
                )
            }
            Box(Modifier.weight(1f))
            TopNavItem(
                title = accountLabel,
                selected = false,
                onClick = onAccountClick,
                modifier = Modifier.testTag("top-nav-account"),
            )
        }
    }
}

@Composable
private fun TopNavItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = CardDefaults.shape(
            RoundedCornerShape(TvDesignTokens.RADIUS_NAV_DP.dp),
        ),
        colors = CardDefaults.colors(
            containerColor = if (selected) {
                Color.White.copy(alpha = 0.16f)
            } else {
                Color.Transparent
            },
            focusedContainerColor = Color.White.copy(alpha = 0.20f),
        ),
        scale = CardDefaults.scale(focusedScale = TvDesignTokens.FOCUS_SCALE),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color(TvDesignTokens.FOCUS_RING)),
                shape = RoundedCornerShape(TvDesignTokens.RADIUS_NAV_DP.dp),
            ),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = Color(TvDesignTokens.FOCUS_RING).copy(alpha = 0.45f),
                elevation = TvDesignTokens.FOCUS_SHADOW_DP.dp,
            ),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 17.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                color = if (selected) {
                    Color.White
                } else {
                    Color(TvDesignTokens.TEXT_PRIMARY)
                },
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            )
        }
    }
}
