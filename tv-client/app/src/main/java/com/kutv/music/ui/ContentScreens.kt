package com.kutv.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kutv.music.core.model.Capability
import com.kutv.music.core.model.HomeContent
import com.kutv.music.core.model.Track
import com.kutv.music.ui.components.HeroMusicCard
import com.kutv.music.ui.components.MusicArtworkCard
import com.kutv.music.ui.components.PageHeading
import com.kutv.music.ui.components.PreviewBadge
import com.kutv.music.ui.components.StatePanel
import com.kutv.music.ui.components.TrackRow

@Composable
fun RecommendedScreen(
    content: HomeContent,
    onPlay: (Track, List<Track>) -> Unit,
) {
    val heroQueue = content.shelves.firstOrNull { it.tracks.isNotEmpty() }?.tracks
        ?.let { tracks -> if (content.hero in tracks) tracks else listOf(content.hero) + tracks }
        ?: listOf(content.hero)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            PageHeading("LISTEN NOW", "为你推荐", "此刻适合你的声音")
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                HeroMusicCard(
                    track = content.hero,
                    onClick = { onPlay(content.hero, heroQueue) },
                    modifier = Modifier.weight(1.62f),
                    eyebrow = "为你精选",
                )
                Column(
                    modifier = Modifier.weight(0.92f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    content.secondary.take(2).forEach { track ->
                        CompactFeatureCard(track, onClick = { onPlay(track, heroQueue) })
                    }
                }
            }
        }
        items(content.shelves, key = { it.id }) { shelf ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(shelf.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (shelf.capability == Capability.PREVIEW) PreviewBadge()
                }
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(shelf.tracks, key = { it.id }) { track ->
                        MusicArtworkCard(track, onClick = { onPlay(track, shelf.tracks) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun CompactFeatureCard(track: Track, onClick: () -> Unit) {
    MusicArtworkCard(
        track = track,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NewContentScreen(
    tracks: List<Track>,
    onPlay: (Track, List<Track>) -> Unit,
) {
    if (tracks.isEmpty()) {
        StatePanel("暂无新内容", "登录酷狗后可获取更多发行内容")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item {
            PageHeading("NEW", "新内容", "新发行与本周热门")
            Spacer(Modifier.height(20.dp))
            HeroMusicCard(tracks.first(), { onPlay(tracks.first(), tracks) }, Modifier.fillMaxWidth(), "本周主推")
        }
        item {
            Text("新发行", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(tracks, key = { it.id }) { track ->
                    MusicArtworkCard(track, { onPlay(track, tracks) })
                }
            }
        }
        item {
            Text("热门歌曲", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tracks.forEachIndexed { index, track -> TrackRow(index, track, { onPlay(track, tracks) }) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("空间音频", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                PreviewBadge(text = "视觉预览")
            }
            Text("当前版本仅展示栏目形态，不声明真实音频规格", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
fun RadioPreviewScreen() {
    var selectedStation by remember { mutableStateOf<String?>(null) }
    val stations = listOf(
        "KU·TV Music 1" to "流行与新作",
        "KU·TV Hits" to "当下热门",
        "KU·TV Chill" to "轻松氛围",
    )
    Column(Modifier.fillMaxSize()) {
        PageHeading("RADIO", "广播", "未来的直播音乐空间")
        Spacer(Modifier.height(26.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(stations) { (name, description) ->
                androidx.tv.material3.Card(
                    onClick = { selectedStation = name },
                    modifier = Modifier
                        .fillParentMaxWidth(0.31f)
                        .height(260.dp)
                        .testTag("radio-$name"),
                    colors = androidx.tv.material3.CardDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.07f),
                        focusedContainerColor = Color.White.copy(alpha = 0.16f),
                    ),
                    scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.05f),
                ) {
                    Box(Modifier.fillMaxSize().padding(24.dp)) {
                        PreviewBadge(modifier = Modifier.align(Alignment.TopStart))
                        Column(Modifier.align(Alignment.BottomStart)) {
                            Text(name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(description, color = Color.White.copy(alpha = 0.54f), fontSize = 15.sp)
                        }
                    }
                }
            }
        }
        selectedStation?.let {
            Spacer(Modifier.height(24.dp))
            Column {
                Text("当前版本暂未接入广播音源", color = Color(0xFFFFBE62), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("$it 已保留为阶段 2 视觉预览，不会创建虚假播放状态。", color = Color.White.copy(alpha = 0.54f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun LibraryScreen(
    tracks: List<Track>,
    onPlay: (Track, List<Track>) -> Unit,
) {
    val filters = listOf("最近播放", "歌单", "艺人", "专辑", "歌曲")
    var selectedFilter by remember { mutableStateOf(filters.first()) }
    Column(Modifier.fillMaxSize()) {
        PageHeading("LIBRARY", "资料库", "你的播放记录与收藏空间")
        Spacer(Modifier.height(20.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filters) { filter ->
                androidx.tv.material3.Button(onClick = { selectedFilter = filter }) {
                    Text(filter, color = Color.White, fontSize = 15.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (selectedFilter == "最近播放") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(tracks, key = { it.id }) { track -> MusicArtworkCard(track, { onPlay(track, tracks) }) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewBadge()
                Text("$selectedFilter 将在阶段 2 接入云端资料库", color = Color.White.copy(alpha = 0.62f), fontSize = 16.sp)
            }
        }
    }
}
