package com.kutv.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import com.kutv.music.core.model.HomeContent
import com.kutv.music.core.model.TimedLyricLine
import com.kutv.music.core.model.Track
import com.kutv.music.data.MusicRepository
import com.kutv.music.direct.KugouSessionManager
import com.kutv.music.direct.KugouSessionState

@Composable
fun KutvApp(
    repository: MusicRepository,
    sessionManager: KugouSessionManager,
    nowPlaying: Track?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    quality: String,
    lyrics: List<TimedLyricLine>,
    queue: List<Track>,
    queueIndex: Int,
    onPlay: (Track, List<Track>) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSelectQueue: (Track) -> Unit,
) {
    val sessionState by sessionManager.state.collectAsState()
    var destination by remember { mutableStateOf(TopDestination.HOME) }
    var loginVisible by remember { mutableStateOf(false) }
    var home by remember { mutableStateOf<HomeContent?>(null) }
    var newTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var libraryTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadEpoch by remember { mutableIntStateOf(0) }
    val searchNavFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        sessionManager.restoreSession()
    }

    LaunchedEffect(loadEpoch, sessionState is KugouSessionState.LoggedIn) {
        loading = true
        loadError = null
        if (home == null) {
            repository.bootstrapHome()?.let {
                home = it
                loading = false
            }
        }
        val homeResult = repository.home()
        val newResult = repository.newReleases()
        homeResult.onSuccess { home = it }
        newTracks = newResult.getOrDefault(emptyList())
        libraryTracks = if (sessionState is KugouSessionState.LoggedIn) {
            repository.library().getOrDefault(emptyList())
        } else {
            emptyList()
        }
        homeResult.exceptionOrNull()?.let {
            loadError = "酷狗内容暂时不可用，请检查网络后重试"
        }
        loading = false
    }

    val accountLabel = when (val state = sessionState) {
        is KugouSessionState.LoggedIn -> state.account.nickname.take(8)
        is KugouSessionState.QrPending -> "等待扫码"
        else -> "登录"
    }

    AppShell(
        destination = destination,
        onDestinationChange = { destination = it },
        nowPlaying = nowPlaying,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        accountLabel = accountLabel,
        onAccountClick = { loginVisible = true },
        onTogglePlay = onTogglePlay,
        onOpenPlayer = { destination = TopDestination.NOW_PLAYING },
        searchNavFocusRequester = searchNavFocusRequester,
    ) {
        when {
            loading && home == null -> LoadingContent()
            loadError != null && home == null -> RepositoryErrorContent(
                message = loadError!!,
                onRetry = { loadEpoch++ },
            )

            else -> when (destination) {
                TopDestination.HOME -> home?.let { RecommendedScreen(it, onPlay) } ?: LoadingContent()
                TopDestination.NEW -> NewContentScreen(newTracks, onPlay)
                TopDestination.RADIO -> RadioPreviewScreen()
                TopDestination.LIBRARY -> if (sessionState is KugouSessionState.LoggedIn) {
                    LibraryScreen(libraryTracks, onPlay)
                } else {
                    LoginRequiredContent(onLogin = { loginVisible = true })
                }

                TopDestination.NOW_PLAYING -> NowPlayingScreen(
                    track = nowPlaying,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    quality = quality,
                    lyrics = lyrics,
                    queue = queue,
                    queueIndex = queueIndex,
                    onTogglePlay = onTogglePlay,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeekBy = onSeekBy,
                    onSelectQueue = onSelectQueue,
                    onBackToHome = { destination = TopDestination.HOME },
                )

                TopDestination.SEARCH -> RepositorySearchScreen(
                    repository = repository,
                    onNavigateUp = searchNavFocusRequester::requestFocus,
                    onPlay = onPlay,
                )
            }
        }
    }

    LoginPanel(
        visible = loginVisible,
        sessionManager = sessionManager,
        onDismiss = { loginVisible = false },
        onLoggedIn = {
            destination = TopDestination.HOME
            loadEpoch++
        },
    )
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("正在准备音乐…", color = Color.White, fontSize = 20.sp)
    }
}

@Composable
private fun RepositoryErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("内容加载失败", color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color.White.copy(alpha = 0.54f), fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("重试", color = Color.White) }
    }
}

@Composable
private fun LoginRequiredContent(onLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("登录后查看资料库", color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        Text("使用酷狗概念版 App 扫码即可同步历史与收藏", color = Color.White.copy(alpha = 0.54f), fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onLogin) { Text("登录", color = Color.White) }
    }
}
