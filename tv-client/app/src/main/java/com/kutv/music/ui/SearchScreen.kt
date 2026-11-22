package com.kutv.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kutv.music.core.model.Track
import com.kutv.music.data.MusicRepository
import com.kutv.music.ui.components.PageHeading
import com.kutv.music.ui.components.TrackRow
import kotlinx.coroutines.delay

@Composable
fun RepositorySearchScreen(
    repository: MusicRepository,
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit = {},
    onPlay: (Track, List<Track>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val inputFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(180)
        inputFocus.requestFocus()
    }

    LaunchedEffect(query, repository) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            results = emptyList()
            error = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        error = null
        delay(350)
        repository.search(normalized).fold(
            onSuccess = { results = it },
            onFailure = {
                results = emptyList()
                error = "搜索失败，请检查网络或登录后重试"
            },
        )
        searching = false
    }

    Column(modifier.fillMaxSize()) {
        PageHeading("SEARCH", "搜索", "搜索歌曲、歌手或专辑")
        Spacer(Modifier.height(20.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(inputFocus)
                .testTag("search-input")
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                        onNavigateUp()
                        true
                    } else {
                        false
                    }
                }
                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 18.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("搜索歌曲、歌手或专辑", color = Color.White.copy(alpha = 0.40f), fontSize = 20.sp)
                }
                inner()
            },
        )
        Spacer(Modifier.height(20.dp))
        when {
            query.isBlank() -> Text(
                "可搜索酷狗曲库；部分内容需要先登录",
                color = Color.White.copy(alpha = 0.46f),
                fontSize = 15.sp,
            )
            searching -> Text("正在搜索…", color = Color.White.copy(alpha = 0.52f), fontSize = 15.sp)
            error != null -> Text(error!!, color = Color(0xFFFF8F6B), fontSize = 15.sp)
            results.isEmpty() -> Text("没有找到匹配歌曲", color = Color.White.copy(alpha = 0.52f), fontSize = 15.sp)
            else -> LazyColumn {
                itemsIndexed(results, key = { _, track -> track.id }) { index, track ->
                    TrackRow(index = index, track = track, onClick = { onPlay(track, results) })
                }
            }
        }
    }
}
