package com.kutv.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import com.kutv.music.core.model.Track
import com.kutv.music.ui.theme.TvDesignTokens

@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpenPlayer,
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .testTag("mini-player"),
        shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
        colors = CardDefaults.colors(
            containerColor = Color(0xC51A1D24),
            focusedContainerColor = Color(0xE02A2E38),
        ),
        scale = CardDefaults.scale(focusedScale = 1.015f),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(24.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.72f)),
                shape = RoundedCornerShape(24.dp),
            ),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(Color.White.copy(alpha = 0.18f), 10.dp),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncArtwork(
                url = track.artworkUri,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artists.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .width(280.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(
                            if (durationMs > 0L) {
                                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                        )
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.88f), RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.width(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isPlaying) "暂停" else "播放",
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 13.sp,
                )
                Card(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(48.dp),
                    shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
                    colors = CardDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        focusedContainerColor = Color.White,
                    ),
                ) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text(if (isPlaying) "Ⅱ" else "▶", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
