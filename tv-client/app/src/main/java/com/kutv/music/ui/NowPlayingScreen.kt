package com.kutv.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import com.kutv.music.ui.components.StatePanel
import com.kutv.music.ui.components.TrackRow

@Composable
fun NowPlayingScreen(
    track: Track?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    quality: String,
    lyrics: List<TimedLyricLine>,
    queue: List<Track>,
    queueIndex: Int,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSelectQueue: (Track) -> Unit,
    onBackToHome: () -> Unit,
) {
    if (track == null) {
        Box(Modifier.fillMaxSize()) {
            StatePanel("还没有正在播放的音乐", "从推荐、新内容、资料库或搜索选择一首歌曲")
            Button(onClick = onBackToHome, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text("返回推荐", color = Color.White)
            }
        }
        return
    }

    var viewMode by remember { mutableStateOf(PlayerViewMode.COVER) }
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(390.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncArtwork(
                url = track.artworkUri,
                modifier = Modifier
                    .size(352.dp)
                    .clip(RoundedCornerShape(28.dp)),
            )
            Spacer(Modifier.height(22.dp))
            Text(track.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(
                track.artists.joinToString(" · "),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("正在播放", color = Color.White.copy(alpha = 0.46f), fontSize = 13.sp, letterSpacing = 2.8.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerViewMode.entries.forEach { mode ->
                    Card(
                        onClick = { viewMode = mode },
                        modifier = Modifier.testTag("player-mode-${mode.name}"),
                        shape = CardDefaults.shape(RoundedCornerShape(20.dp)),
                        colors = CardDefaults.colors(
                            containerColor = if (viewMode == mode) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = Color.White.copy(alpha = 0.24f),
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.78f)),
                                shape = RoundedCornerShape(20.dp),
                            ),
                        ),
                    ) {
                        Text(
                            mode.label,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (viewMode == mode) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            PlayerModeContent(
                mode = viewMode,
                track = track,
                lyrics = lyrics,
                positionMs = positionMs,
                queue = queue,
                queueIndex = queueIndex,
                onSelectQueue = onSelectQueue,
                modifier = Modifier.fillMaxWidth().height(250.dp),
            )
            Spacer(Modifier.height(18.dp))
            Card(
                onClick = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { onSeekBy(-10_000L); true }
                            Key.DirectionRight -> { onSeekBy(10_000L); true }
                            else -> false
                        }
                    },
                colors = CardDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.05f),
                    focusedContainerColor = Color.White.copy(alpha = 0.10f),
                ),
                shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.68f)),
                        shape = RoundedCornerShape(16.dp),
                    ),
                ),
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.17f), RoundedCornerShape(3.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(
                                    if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                                )
                                .height(5.dp)
                                .background(Color.White, RoundedCornerShape(3.dp)),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatPlaybackTime(positionMs), color = Color.White.copy(alpha = 0.52f), fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${quality.ifBlank { track.qualities.firstOrNull() ?: "自动" }} · ${formatPlaybackTime(durationMs)}", color = Color.White.copy(alpha = 0.52f), fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerButton("上一首", "‹", onPrevious)
                PlayerButton(if (isPlaying) "暂停" else "播放", if (isPlaying) "Ⅱ" else "▶", onTogglePlay, primary = true)
                PlayerButton("下一首", "›", onNext)
                Text("进度条聚焦后，左右键快退/快进 10 秒", color = Color.White.copy(alpha = 0.38f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PlayerModeContent(
    mode: PlayerViewMode,
    track: Track,
    lyrics: List<TimedLyricLine>,
    positionMs: Long,
    queue: List<Track>,
    queueIndex: Int,
    onSelectQueue: (Track) -> Unit,
    modifier: Modifier,
) {
    when (mode) {
        PlayerViewMode.COVER -> Box(modifier, contentAlignment = Alignment.CenterStart) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(track.album.ifBlank { "KU·TV Music" }, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("沉浸式封面视图", color = Color.White.copy(alpha = 0.54f), fontSize = 16.sp)
                Text("选择“歌词”查看同步歌词，选择“队列”浏览下一首。", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
            }
        }
        PlayerViewMode.LYRICS -> {
            val activeIndex = activeLyricIndex(lyrics, positionMs)
            if (lyrics.isEmpty()) {
                StatePanel("暂无歌词", "播放控制仍然可用", modifier)
            } else {
                LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(lyrics) { index, line ->
                        val active = index == activeIndex
                        Text(
                            line.text,
                            color = if (active) Color.White else Color.White.copy(alpha = 0.34f),
                            fontSize = if (active) 28.sp else 19.sp,
                            lineHeight = 32.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        PlayerViewMode.QUEUE -> LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            itemsIndexed(queue, key = { _, item -> item.id }) { index, item ->
                TrackRow(
                    index = index,
                    track = item,
                    onClick = { onSelectQueue(item) },
                    modifier = if (index == queueIndex) Modifier.background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun PlayerButton(
    label: String,
    symbol: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Button(onClick = onClick, modifier = Modifier.height(58.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(symbol, color = Color.White, fontSize = if (primary) 20.sp else 28.sp, textAlign = TextAlign.Center)
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
