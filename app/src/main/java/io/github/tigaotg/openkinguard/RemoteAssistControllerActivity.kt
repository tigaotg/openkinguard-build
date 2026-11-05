package io.github.tigaotg.openkinguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import org.webrtc.SurfaceViewRenderer

/**
 * Separate immersive controller screen, independent of the app's card layout.
 * A single persistent SurfaceViewRenderer occupies the entire screen for the
 * whole approved session. Floating overlays NEVER resize or recreate video.
 */
class RemoteAssistControllerActivity : ComponentActivity() {
    private var ownsTransport = false

    companion object {
        const val EXTRA_SESSION_ID = "io.github.tigaotg.openkinguard.CONTROL_SESSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val session = S1Runtime.manager(applicationContext).remoteAssistSession()
        if (sessionId.isBlank() || session?.sessionId != sessionId ||
            session.direction != RemoteAssistConsent.Direction.OUTGOING) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ControllerScreen(sessionId, onCollapse = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // MainActivity becomes STOPPED after this fullscreen Activity starts.
        // Explicitly take over its LAN/P2P listener before that transition.
        runCatching {
            S1Runtime.attachController(applicationContext)
            ownsTransport = true
        }.onFailure { failure ->
            Toast.makeText(this,
                "远程协助通信启动失败：" + failure.javaClass.simpleName,
                Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onStop() {
        if (ownsTransport) {
            ownsTransport = false
            S1Runtime.detachController()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}

@Composable
private fun ControllerScreen(sessionId: String, onCollapse: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { S1Runtime.manager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var phase by remember {
        mutableStateOf(manager.remoteAssistSession()?.phase)
    }
    var remoteSize by remember { mutableStateOf(manager.remoteAssistRtcSize()) }
    val touchVideoSize by rememberUpdatedState(remoteSize)
    var report by remember { mutableStateOf("") }
    var diagnosticVisible by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var toolbarVisible by remember { mutableStateOf(false) }
    var waitingSince by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var waitingSeconds by remember { mutableLongStateOf(0L) }

    fun command(label: String, perform: () -> String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { perform() }.fold(
                    onSuccess = { "$label：已发送" },
                    onFailure = { "$label：${it.message?.take(70) ?: it.javaClass.simpleName}" })
            }
            feedback = result
        }
    }

    LaunchedEffect(sessionId) {
        while (true) {
            val current = manager.remoteAssistSession()
            if (current?.sessionId != sessionId ||
                current.direction != RemoteAssistConsent.Direction.OUTGOING) {
                onCollapse()
                break
            }
            if (phase != current.phase) waitingSince = SystemClock.elapsedRealtime()
            phase = current.phase
            waitingSeconds = (SystemClock.elapsedRealtime() - waitingSince) / 1000L
            remoteSize = manager.remoteAssistRtcSize()
            delay(450)
        }
    }
    BackHandler { onCollapse() }


    // Video and system navigation occupy separate, non-overlapping rectangles.
    // The native renderer remains attached throughout this consented session.
    // Black letterboxing preserves the *entire* remote phone frame on any aspect ratio.
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            if (phase == RemoteAssistConsent.Phase.ACTIVE) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val video = remoteSize
                    val fitted = video?.let {
                        RemoteAssistFrameLayout.fit(maxWidth.value, maxHeight.value,
                            it.first, it.second)
                    }
                    // Only meaningful video aspect changes remeasure the SAME renderer.
                    // Never use "frame fresh" as a condition for attaching the surface.
                    val viewport = if (fitted != null)
                        Modifier.width(fitted.width.dp).height(fitted.height.dp)
                    else Modifier.fillMaxSize()
                    Box(viewport, contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { ctx ->
                                SurfaceViewRenderer(ctx).also { manager.attachRemoteRtcRenderer(it) }
                            },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { manager.detachRemoteRtcRenderer(it) }
                        )
                        Box(Modifier.fillMaxSize().pointerInput(sessionId, video) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val start = down.position
                                var last = start
                                var released = false
                                var multiTouch = false
                                var releasedAt = down.uptimeMillis
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } > 1) multiTouch = true
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    last = change.position
                                    releasedAt = change.uptimeMillis
                                    change.consume()
                                    if (!change.pressed) {
                                        released = true
                                        break
                                    }
                                }
                                if (released && !multiTouch) {
                                    val videoSize = touchVideoSize
                                    val container = size
                                    val startPoint = videoSize?.let {
                                        RemoteAssistTouchGeometry.map(start.x, start.y,
                                            container.width, container.height, it.first, it.second)
                                    }
                                    if (startPoint != null && videoSize != null) {
                                        val elapsed = (releasedAt - down.uptimeMillis).coerceAtLeast(0)
                                        val distance = hypot((last.x - start.x).toDouble(),
                                            (last.y - start.y).toDouble())
                                        if (distance > viewConfiguration.touchSlop) {
                                            val finishPoint = RemoteAssistTouchGeometry.map(
                                                last.x, last.y,
                                                container.width, container.height,
                                                videoSize.first, videoSize.second, clamp = true)
                                            if (finishPoint != null) command("滑动") {
                                                manager.sendRemoteAssistSwipe(
                                                    startPoint.x, startPoint.y,
                                                    finishPoint.x, finishPoint.y,
                                                    elapsed.toInt().coerceIn(120, 1000))
                                            }
                                        } else if (elapsed >= viewConfiguration.longPressTimeoutMillis) {
                                            command("长按") {
                                                manager.sendRemoteAssistSwipe(
                                                    startPoint.x, startPoint.y,
                                                    startPoint.x, startPoint.y, 650)
                                            }
                                        } else command("点击") {
                                            manager.sendRemoteAssistTap(startPoint.x, startPoint.y)
                                        }
                                    }
                                }
                            }
                        })
                    }
                }
            } else {
                Column(modifier = Modifier.align(Alignment.Center)
                    .padding(horizontal = 25.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                        modifier = Modifier.size(32.dp))
                    Text(text = when (phase) {
                        RemoteAssistConsent.Phase.WAITING_FOR_REPLY ->
                            "已发送请求，等待对方同意"
                        RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION ->
                            "对方已同意 · 正在等待远程画面"
                        else -> "等待远程屏幕共享"
                    },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                    if (phase == RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION &&
                        waitingSeconds >= 12L) {
                        Text("如果对方手机已经显示‘正在共享屏幕’，" +
                            "可能是移动网络阻止了 WebRTC 信令或视频直连，" +
                            "不一定是对方没有授权。",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            report = manager.remoteAssistDiagnosticReport()
                            diagnosticVisible = true
                        }) { Text("查看连接诊断", color = Color.White) }
                    }
                }
            }

            // Top tools are hidden by default: only this small triangle remains.
            // It overlays the video but does not change the video's measurements.
            Column(Modifier.align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(
                    onClick = { toolbarVisible = !toolbarVisible },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.70f))
                ) { Text(if (toolbarVisible) "▲" else "▼", color = Color.White) }
                if (toolbarVisible) {
                    Row(Modifier.fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.86f))
                        .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("远程协助", color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f).padding(start = 6.dp))
                        TextButton(onClick = {
                            report = manager.remoteAssistDiagnosticReport()
                            diagnosticVisible = true
                        }) { Text("诊断", color = Color.White) }
                        TextButton(onClick = onCollapse) { Text("收起", color = Color.White) }
                        TextButton(onClick = { confirmEnd = true }) {
                            Text("结束", color = Color.White)
                        }
                    }
                }
            }
        }

        // An opaque BLACK navigation strip below the video: the stream cannot
        // appear beneath, between or behind these Android-style action buttons.
        Row(
            modifier = Modifier.fillMaxWidth().height(70.dp)
                .background(Color.Black).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(modifier = Modifier.weight(1f),
                enabled = phase == RemoteAssistConsent.Phase.ACTIVE,
                onClick = { command("返回") { manager.sendRemoteAssistBack() } }) {
                Text("‹  返回", color = if (phase == RemoteAssistConsent.Phase.ACTIVE)
                    Color.White else Color.Gray)
            }
            TextButton(modifier = Modifier.weight(1f),
                enabled = phase == RemoteAssistConsent.Phase.ACTIVE,
                onClick = { command("主页") { manager.sendRemoteAssistHome() } }) {
                Text("○  主页", color = if (phase == RemoteAssistConsent.Phase.ACTIVE)
                    Color.White else Color.Gray)
            }
            TextButton(modifier = Modifier.weight(1f),
                enabled = phase == RemoteAssistConsent.Phase.ACTIVE,
                onClick = { command("最近任务") { manager.sendRemoteAssistRecents() } }) {
                Text("▢  多任务", color = if (phase == RemoteAssistConsent.Phase.ACTIVE)
                    Color.White else Color.Gray)
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("结束本次远程协助？") },
            text = { Text("将停止屏幕共享并撤销本次控制授权。") },
            confirmButton = { TextButton(onClick = {
                confirmEnd = false
                // Local grant must be revoked before Activity disposal cancels its
                // rememberCoroutineScope; never leave capture alive after "结束".
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { manager.stopRemoteAssist() }
                    }
                    onCollapse()
                }
            }) { Text("确定结束") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) {
                Text("继续协助")
            } }
        )
    }

    if (diagnosticVisible) {
        AlertDialog(
            onDismissRequest = { diagnosticVisible = false },
            title = { Text("WebRTC 远程协助诊断") },
            text = {
                Text(report,
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = { TextButton(onClick = {
                report = manager.remoteAssistDiagnosticReport()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenKinGuard 诊断", report))
                Toast.makeText(context, "诊断已复制", Toast.LENGTH_SHORT).show()
            }) { Text("刷新并复制") } },
            dismissButton = { TextButton(onClick = { diagnosticVisible = false }) {
                Text("关闭")
            } }
        )
    }
}
