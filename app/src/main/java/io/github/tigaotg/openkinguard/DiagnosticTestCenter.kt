package io.github.tigaotg.openkinguard

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val DIAGNOSTIC_SESSION_PREFS = "okg_diagnostic_session_v1"
private const val DIAGNOSTIC_DURATION_MS = 72L * 60L * 60L * 1000L

/**
 * A user-controlled 72-hour acceptance window over the existing background monitor and
 * seven-day connection history. It does not claim observation while Android has stopped
 * the service/process; those intervals remain explicit sampling gaps in the exported data.
 */
@Composable
internal fun DiagnosticTestCenter() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(DIAGNOSTIC_SESSION_PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var startedAt by remember { mutableLongStateOf(prefs.getLong("started_at", 0L)) }
    var finishedAt by remember { mutableLongStateOf(prefs.getLong("finished_at", 0L)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var feedback by remember { mutableStateOf("") }
    val elapsed = if (startedAt > 0L) (now - startedAt).coerceAtLeast(0L) else 0L
    val completed = startedAt > 0L && elapsed >= DIAGNOSTIC_DURATION_MS
    val running = startedAt > 0L && !completed && finishedAt == 0L

    LaunchedEffect(startedAt, finishedAt) {
        while (startedAt > 0L && finishedAt == 0L) {
            now = System.currentTimeMillis()
            if (now - startedAt >= DIAGNOSTIC_DURATION_MS) {
                finishedAt = startedAt + DIAGNOSTIC_DURATION_MS
                prefs.edit().putLong("finished_at", finishedAt).apply()
                feedback = "72小时观察窗口已结束，可以导出报告"
                break
            }
            delay(30_000L)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val generatedAt = System.currentTimeMillis()
                        val historyJson = ConnectionHistoryStore.export(context, true, generatedAt)
                        val historyTxt = ConnectionHistoryStore.export(context, false, generatedAt)
                        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        val metadata = JSONObject()
                            .put("format", "OpenKinGuard-device-diagnostic-v1")
                            .put("generatedAtMs", generatedAt)
                            .put("sessionStartedAtMs", startedAt)
                            .put("sessionFinishedAtMs", if (completed) startedAt + DIAGNOSTIC_DURATION_MS else finishedAt)
                            .put("requestedDurationMs", DIAGNOSTIC_DURATION_MS)
                            .put("elapsedMs", elapsed)
                            .put("durationComplete", completed)
                            .put("manufacturer", Build.MANUFACTURER.take(40))
                            .put("model", Build.MODEL.take(60))
                            .put("androidSdk", Build.VERSION.SDK_INT)
                            .put("packageName", context.packageName)
                            .put("versionName", packageInfo.versionName ?: "unknown")
                            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong())
                            .put("backgroundListenerEnabled", S1Runtime.isBackgroundListening())
                            .put("historyWindowDays", 7)
                            .put("limitations", "Connection history is captured by the existing background monitor. Android force-stop, power-off, or OS process termination can create unobserved gaps; see samplingGaps.")
                        context.contentResolver.openOutputStream(uri, "w")?.use { raw ->
                            ZipOutputStream(raw).use { zip ->
                                fun add(name: String, value: String) {
                                    zip.putNextEntry(ZipEntry(name))
                                    zip.write(value.toByteArray(Charsets.UTF_8))
                                    zip.closeEntry()
                                }
                                add("device-report.json", metadata.toString(2))
                                add("connection-history.json", historyJson)
                                add("connection-history.txt", historyTxt)
                                add("README.txt", "OpenKinGuard 72小时诊断包\n本包包含设备基本信息与最近7天脱敏连接历史。\n72小时窗口是用户启动的观察区间；不代表手机在进程被系统停止或断电期间仍可采样。\n请勿公开分享未经检查的诊断数据。\n")
                            }
                        } ?: error("无法写入所选文件")
                    }
                }
                feedback = if (result.isSuccess) "诊断 ZIP 已导出" else "导出失败：" + (result.exceptionOrNull()?.javaClass?.simpleName ?: "未知错误")
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("自动化测试中心", style = MaterialTheme.typography.titleLarge)
            Text("独立观察本机后台监听和连接历史，最长观察72小时。无需电脑或持续 ADB。",
                style = MaterialTheme.typography.bodyMedium)
            val listenerReady = S1Runtime.isBackgroundListening()
            Text("后台监听：" + if (listenerReady) "已开启" else "未开启",
                style = MaterialTheme.typography.bodyMedium)
            if (!listenerReady) {
                Text("开始前请先在“权限与功能检测”中开启后台监听。未运行监听时，连接历史可能出现较大监测空档。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (startedAt == 0L) {
                Text("尚未开始测试。启动后会保存测试起始时间，并使用现有后台连接历史作为观察数据。")
                Button(onClick = {
                    val stamp = System.currentTimeMillis()
                    startedAt = stamp
                    finishedAt = 0L
                    now = stamp
                    prefs.edit().putLong("started_at", stamp).putLong("finished_at", 0L).apply()
                    feedback = if (listenerReady) "72小时观察已开始" else "观察窗口已开始，但后台监听未开启"
                }, modifier = Modifier.fillMaxWidth()) { Text("开始72小时测试") }
            } else {
                val remain = (DIAGNOSTIC_DURATION_MS - elapsed).coerceAtLeast(0L)
                val hours = remain / 3_600_000L
                val minutes = (remain % 3_600_000L) / 60_000L
                Text(if (completed) "测试窗口已完成" else "测试进行中",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text("开始时间：" + Date(startedAt).toString())
                Text(if (completed) "观察窗口：72 / 72 小时" else "剩余时间：${hours}小时${minutes}分钟")
                Text("已观察：${(elapsed / 3_600_000.0).coerceAtMost(72.0).let { "%.1f".format(it) }} / 72 小时")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        finishedAt = System.currentTimeMillis()
                        prefs.edit().putLong("finished_at", finishedAt).apply()
                        feedback = "已提前结束观察，可导出阶段性报告"
                    }, enabled = running) { Text("提前结束") }
                    OutlinedButton(onClick = {
                        startedAt = 0L
                        finishedAt = 0L
                        prefs.edit().clear().apply()
                        feedback = "测试会话已重置；既有连接历史不会删除"
                    }) { Text("重置会话") }
                }
            }
            Button(onClick = {
                exportLauncher.launch("OpenKinGuard_72h_diagnostic_${System.currentTimeMillis()}.zip")
            }, modifier = Modifier.fillMaxWidth(), enabled = startedAt > 0L) {
                Text("一键导出诊断 ZIP")
            }
            Text("导出内容：设备与版本信息、测试窗口、最近7天脱敏连接历史 TXT/JSON。不会导出 IP、密钥或原始错误日志。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
        }
    }
}
