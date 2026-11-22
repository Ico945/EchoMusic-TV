package com.kutv.music.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.kutv.music.core.model.Capability
import com.kutv.music.core.model.Track
import com.kutv.music.ui.AsyncArtwork
import com.kutv.music.ui.theme.TvDesignTokens

@Composable
fun PageHeading(
    eyebrow: String,
    title: String,
    subtitle: String,
) {
    Column {
        Text(eyebrow, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, letterSpacing = 2.6.sp)
        Text(title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.White.copy(alpha = 0.56f), fontSize = 16.sp)
    }
}

@Composable
fun HeroMusicCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String = "编辑精选",
) {
    val palette = remember(track.id) { TvDesignTokens.paletteFor(track.id).map(::Color) }
    Card(
        onClick = onClick,
        modifier = modifier.height(330.dp).testTag("hero-${track.id}"),
        shape = CardDefaults.shape(RoundedCornerShape(28.dp)),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        scale = CardDefaults.scale(focusedScale = 1.025f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, Color.White.copy(alpha = 0.86f)),
                shape = RoundedCornerShape(28.dp),
            ),
        ),
        glow = CardDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.22f), 16.dp)),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(palette))) {
            AsyncArtwork(url = track.artworkUri, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.76f), Color.Black.copy(alpha = 0.18f)),
                    ),
                ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(30.dp),
            ) {
                Text(eyebrow.uppercase(), color = Color.White.copy(alpha = 0.64f), fontSize = 13.sp, letterSpacing = 2.4.sp)
                Spacer(Modifier.height(8.dp))
                Text(track.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                Text(track.artists.joinToString(" · "), color = Color.White.copy(alpha = 0.76f), fontSize = 17.sp)
                Spacer(Modifier.height(18.dp))
                Text("▶  立即播放", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun MusicArtworkCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(208.dp),
        shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
        colors = CardDefaults.colors(
            containerColor = Color(0x451A1E29),
            focusedContainerColor = Color(0x842B303E),
        ),
        scale = CardDefaults.scale(focusedScale = TvDesignTokens.FOCUS_SCALE),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, Color.White.copy(alpha = 0.88f)),
                shape = RoundedCornerShape(22.dp),
            ),
        ),
        glow = CardDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.20f), 14.dp)),
    ) {
        Column(Modifier.padding(9.dp)) {
            Box {
                AsyncArtwork(
                    url = track.artworkUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(174.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                badge?.let {
                    PreviewBadge(Modifier.align(Alignment.TopStart).padding(9.dp), it)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                track.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artists.joinToString(" · "),
                color = Color.White.copy(alpha = 0.54f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TrackRow(
    index: Int,
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("track-${track.id}"),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.045f),
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
        ),
        scale = CardDefaults.scale(focusedScale = 1.015f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.78f)),
                shape = RoundedCornerShape(16.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("%02d".format(index + 1), color = Color.White.copy(alpha = 0.36f), fontSize = 13.sp)
            Spacer(Modifier.width(14.dp))
            AsyncArtwork(track.artworkUri, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(track.artists.joinToString(" · "), color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp)
            }
            Text(track.album, color = Color.White.copy(alpha = 0.36f), fontSize = 13.sp)
        }
    }
}

@Composable
fun PreviewBadge(modifier: Modifier = Modifier, text: String = "功能预览") {
    Box(
        modifier = modifier.background(Color(0xFFE5A33F), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF211609), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CapabilityLabel(capability: Capability) {
    if (capability == Capability.PREVIEW) PreviewBadge()
}

@Composable
fun StatePanel(title: String, message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(message, color = Color.White.copy(alpha = 0.54f), fontSize = 15.sp)
        }
    }
}
