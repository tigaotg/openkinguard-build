package io.github.tigaotg.openkinguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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
    var toolbarVisible by remember { mutableStateOf(true) }

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
            phase = current.phase
            remoteSize = manager.remoteAssistRtcSize()
            delay(450)
        }
    }
    BackHandler { onCollapse() }

    // This Box NEVER changes its measurements when video resolution, fps or
    // navigation controls change. The native renderer stays attached.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (phase == RemoteAssistConsent.Phase.ACTIVE) {
            AndroidView(factory = { ctx ->
                SurfaceViewRenderer(ctx).also { manager.attachRemoteRtcRenderer(it) }
            }, modifier = Modifier.fillMaxSize(),
                onRelease = { manager.detachRemoteRtcRenderer(it) })
            // Pointer events are mapped through WebRTC SCALE_ASPECT_FIT letterboxing.
            Box(Modifier.fillMaxSize().pointerInput(sessionId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var last = start
                    var wasReleased = false
                    var multiTouch = false
                    var releasedAt = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) {
                            multiTouch = true
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        releasedAt = change.uptimeMillis
                        change.consume()
                        if (!change.pressed) {
                            wasReleased = true
                            break
                        }
                    }
                    if (wasReleased && !multiTouch) {
                        val video = touchVideoSize
                        val container = size
                        val startPoint = video?.let {
                            RemoteAssistTouchGeometry.map(start.x, start.y,
                                container.width, container.height, it.first, it.second)
                        }
                        if (startPoint != null && video != null) {
                            val elapsed = (releasedAt - down.uptimeMillis).coerceAtLeast(0)
                            val distance = hypot((last.x - start.x).toDouble(),
                                (last.y - start.y).toDouble())
                            val touchSlop = viewConfiguration.touchSlop.toDouble()
                            if (distance > touchSlop) {
                                val endPoint = RemoteAssistTouchGeometry.map(
                                    last.x, last.y, container.width, container.height,
                                    video.first, video.second, clamp = true)
                                if (endPoint != null) {
                                    command("滑动") {
                                        manager.sendRemoteAssistSwipe(
                                            startPoint.x, startPoint.y, endPoint.x, endPoint.y,
                                            elapsed.toInt().coerceIn(120, 1000))
                                    }
                                }
                            } else if (elapsed >= viewConfiguration.longPressTimeoutMillis) {
                                command("长按") {
                                    manager.sendRemoteAssistSwipe(
                                        startPoint.x, startPoint.y,
                                        startPoint.x, startPoint.y, 650)
                                }
                            } else {
                                command("点击") {
                                    manager.sendRemoteAssistTap(startPoint.x, startPoint.y)
                                }
                            }
                        }
                    }
                }
            })
        } else {
            Text(
                text = when (phase) {
                    RemoteAssistConsent.Phase.WAITING_FOR_REPLY ->
                        "已发送请求，等待对方同意"
                    RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION ->
                        "对方已同意，等待 Android 屏幕共享授权"
                    else -> "等待远程屏幕共享"
                },
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(28.dp))
        }

        // Overlay never participates in video sizing.
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("远程控制", color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 10.dp))
            TextButton(onClick = { toolbarVisible = !toolbarVisible }) {
                Text(if (toolbarVisible) "隐藏按键" else "显示按键")
            }
            TextButton(onClick = {
                report = manager.remoteAssistDiagnosticReport()
                diagnosticVisible = true
            }) { Text("诊断") }
            TextButton(onClick = onCollapse) { Text("收起") }
            TextButton(onClick = { confirmEnd = true }) { Text("结束") }
        }

        if (toolbarVisible) {
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.76f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (feedback.isNotBlank()) {
                    Text(feedback, style = MaterialTheme.typography.labelSmall,
                        color = Color.White, maxLines = 2)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(55.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(modifier = Modifier.weight(1f),
                        onClick = { command("返回") { manager.sendRemoteAssistBack() } }) {
                        Text("‹  返回", color = Color.White)
                    }
                    TextButton(modifier = Modifier.weight(1f),
                        onClick = { command("主页") { manager.sendRemoteAssistHome() } }) {
                        Text("○  主页", color = Color.White)
                    }
                    TextButton(modifier = Modifier.weight(1f),
                        onClick = { command("最近任务") { manager.sendRemoteAssistRecents() } }) {
                        Text("▢  多任务", color = Color.White)
                    }
                }
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
                command("结束协助") { manager.stopRemoteAssist() }
                onCollapse()
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
