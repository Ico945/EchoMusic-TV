package com.kutv.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import androidx.tv.material3.Button
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kutv.music.direct.KugouQrState
import com.kutv.music.direct.KugouSessionManager
import com.kutv.music.direct.KugouSessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginPanel(
    visible: Boolean,
    sessionManager: KugouSessionManager,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    if (!visible) return
    val sessionState by sessionManager.state.collectAsState()
    var error by remember(visible) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val actionFocusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (!visible || sessionManager.state.value is KugouSessionState.LoggedIn) return@LaunchedEffect
        error = null
        runCatching { sessionManager.beginQr() }
            .onFailure { error = "二维码获取失败，请检查网络和电视日期时间" }
    }

    val pendingKey = (sessionState as? KugouSessionState.QrPending)?.session?.key
    LaunchedEffect(visible, pendingKey) {
        if (!visible || pendingKey == null) return@LaunchedEffect
        while (visible && (sessionManager.state.value as? KugouSessionState.QrPending)?.session?.key == pendingKey) {
            delay(3_000)
            val pending = sessionManager.state.value as? KugouSessionState.QrPending ?: break
            if (pending.session.key != pendingKey) break
            if (pending.qrState is KugouQrState.Expired) break
            runCatching { sessionManager.pollCurrentQr() }
                .onSuccess { state ->
                    if (state is KugouQrState.Confirmed) {
                        onLoggedIn()
                        onDismiss()
                    }
                }
                .onFailure { error = "登录状态更新失败，正在等待重试" }
        }
    }

    LaunchedEffect(sessionState, error) {
        delay(80)
        runCatching { actionFocusRequester.requestFocus() }
    }

    val dismissLogin = {
        sessionManager.cancelQr()
        onDismiss()
    }
    Dialog(
        onDismissRequest = dismissLogin,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.width(780.dp).padding(20.dp),
                shape = RoundedCornerShape(32.dp),
                colors = SurfaceDefaults.colors(containerColor = Color(0xF21A1D24)),
            ) {
                when (val state = sessionState) {
                    is KugouSessionState.LoggedIn -> AccountContent(
                        state = state,
                        focusRequester = actionFocusRequester,
                        onLogout = {
                            scope.launch {
                                sessionManager.logout()
                                onLoggedIn()
                                onDismiss()
                            }
                        },
                        onDismiss = onDismiss,
                    )

                    is KugouSessionState.QrPending -> QrContent(
                        state = state,
                        error = error,
                        focusRequester = actionFocusRequester,
                        onRefresh = {
                            scope.launch {
                                error = null
                                runCatching { sessionManager.beginQr() }
                                    .onFailure { error = "二维码获取失败，请检查网络和电视日期时间" }
                            }
                        },
                        onDismiss = dismissLogin,
                    )

                    else -> LoadingLoginContent(
                        error = error,
                        focusRequester = actionFocusRequester,
                        onRetry = {
                            scope.launch {
                                error = null
                                runCatching { sessionManager.beginQr() }
                                    .onFailure { error = "二维码获取失败，请检查网络和电视日期时间" }
                            }
                        },
                        onDismiss = dismissLogin,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrContent(
    state: KugouSessionState.QrPending,
    error: String?,
    focusRequester: FocusRequester,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(state.session.qrUrl) { qrBitmap(state.session.qrUrl, 420) }
    Row(
        modifier = Modifier.padding(36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = "酷狗登录二维码",
            modifier = Modifier.size(320.dp).background(Color.White, RoundedCornerShape(18.dp)).padding(10.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("登录酷狗音乐", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("使用酷狗概念版 App 扫码", color = Color.White.copy(alpha = 0.68f), fontSize = 17.sp)
            val message = when (state.qrState) {
                KugouQrState.Scanned -> "已扫码，请在手机上确认"
                KugouQrState.Expired -> "二维码已过期"
                else -> "二维码约 ${state.session.expiresInSeconds} 秒内有效"
            }
            Text(
                error ?: message,
                color = if (state.qrState is KugouQrState.Scanned) Color(0xFF62D59B) else Color.White.copy(alpha = 0.54f),
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val showRefresh = state.qrState is KugouQrState.Expired || error != null
                if (showRefresh) {
                    Button(onClick = onRefresh, modifier = Modifier.focusRequester(focusRequester)) {
                        Text("刷新二维码", color = Color.White)
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = if (showRefresh) Modifier else Modifier.focusRequester(focusRequester),
                ) { Text("取消", color = Color.White) }
            }
        }
    }
}

@Composable
private fun AccountContent(
    state: KugouSessionState.LoggedIn,
    focusRequester: FocusRequester,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(38.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(state.account.nickname, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            if (state.account.membership == "none") "酷狗账号" else "酷狗 ${state.account.membership.uppercase()} 会员",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 15.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onLogout, modifier = Modifier.focusRequester(focusRequester)) {
                Text("退出登录", color = Color.White)
            }
            Button(onClick = onDismiss) { Text("完成", color = Color.White) }
        }
    }
}

@Composable
private fun LoadingLoginContent(
    error: String?,
    focusRequester: FocusRequester,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(38.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("正在生成登录二维码…", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        error?.let { Text(it, color = Color(0xFFFF9D7A), fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error != null) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(focusRequester)) {
                    Text("重试", color = Color.White)
                }
            }
            Button(
                onClick = onDismiss,
                modifier = if (error != null) Modifier else Modifier.focusRequester(focusRequester),
            ) { Text("取消", color = Color.White) }
        }
    }
}

private fun qrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 2),
    )
    val pixels = IntArray(size * size) { index ->
        if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return createBitmap(size, size).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
}
