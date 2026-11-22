package com.kutv.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kutv.music.core.model.Track
import com.kutv.music.ui.theme.TvDesignTokens

@Composable
fun AppShell(
    destination: TopDestination,
    onDestinationChange: (TopDestination) -> Unit,
    nowPlaying: Track?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    accountLabel: String,
    onAccountClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenPlayer: () -> Unit,
    searchNavFocusRequester: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = remember(nowPlaying?.id) {
        TvDesignTokens.paletteFor(nowPlaying?.id ?: "kutv-home").map(::Color)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        palette[0].copy(alpha = 0.34f),
                        palette[1].copy(alpha = 0.18f),
                        Color(TvDesignTokens.BG_DEEP),
                    ),
                    radius = 1_600f,
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(TvDesignTokens.BG_DEEP).copy(alpha = 0.74f)),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TvDesignTokens.SAFE_MARGIN_DP.dp,
                    end = TvDesignTokens.SAFE_MARGIN_DP.dp,
                    top = 112.dp,
                    bottom = if (nowPlaying == null) 30.dp else 122.dp,
                ),
            content = content,
        )
        TopNav(
            selected = destination,
            accountLabel = accountLabel,
            onDestinationChange = onDestinationChange,
            onAccountClick = onAccountClick,
            searchNavFocusRequester = searchNavFocusRequester,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = TvDesignTokens.SAFE_MARGIN_DP.dp, vertical = 18.dp),
        )
        nowPlaying?.let { track ->
            MiniPlayer(
                track = track,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                onTogglePlay = onTogglePlay,
                onOpenPlayer = onOpenPlayer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = TvDesignTokens.SAFE_MARGIN_DP.dp, vertical = 22.dp),
            )
        }
    }
}
