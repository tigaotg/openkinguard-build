package io.github.tigaotg.openkinguard

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Day-by-day history and explicit SAF exports. All recorded fields are redacted at collection. */
@Composable
internal fun ConnectionHistoryPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daysBack by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf("all") }
    var pageIndex by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var events by remember { mutableStateOf<List<ConnectionHistoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    fun exportTo(asJson: Boolean, uri: android.net.Uri?) {
        if (uri == null) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = ConnectionHistoryStore.export(context, asJson)
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        output.writer(Charsets.UTF_8).use { it.write(report) }
                    } ?: error("Cannot open selected document")
                }
            }
            feedback = if (result.isSuccess) "已保存七天脱敏连接历史，可上传给 ChatGPT 分析"
                else "导出失败：" + (result.exceptionOrNull()?.javaClass?.simpleName ?: "未知")
            Toast.makeText(context, feedback, Toast.LENGTH_LONG).show()
        }
    }

    val txtPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")) { uri -> exportTo(false, uri) }
    val jsonPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri -> exportTo(true, uri) }

    LaunchedEffect(daysBack, category, pageIndex, reload) {
        loading = true
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysBack)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val end = calendar.timeInMillis
        val cutoff = System.currentTimeMillis() - ConnectionHistoryPolicy.RETENTION_MS
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                ConnectionHistoryStore.read(context, maxOf(start, cutoff), category,
                    limit = 100, untilMillis = end, offset = pageIndex * 100)
            }
        }
        events = fetched.getOrElse {
            feedback = "历史查询失败：" + it.javaClass.simpleName
            emptyList()
        }
        loading = false
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近7天连接历史", style = MaterialTheme.typography.titleMedium)
            Text("状态变化时记录；正常运行每分钟最多保存一次快照。手机关闭应用或进程被系统终止时可能出现记录空档。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    daysBack = (daysBack + 1).coerceAtMost(6)
                    pageIndex = 0
                }, enabled = daysBack < 6) { Text("前一天") }
                Text(if (daysBack == 0) "今天" else "$daysBack 天前",
                    modifier = Modifier.padding(top = 10.dp))
                OutlinedButton(onClick = {
                    daysBack = (daysBack - 1).coerceAtLeast(0)
                    pageIndex = 0
                }, enabled = daysBack > 0) { Text("后一天") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "change" to "变化", "snapshot" to "快照").forEach { (value, label) ->
                    FilterChip(selected = category == value, onClick = {
                        category = value
                        pageIndex = 0
                    }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = { reload++ }) { Text("刷新") }
                OutlinedButton(onClick = {
                    txtPicker.launch("OpenKinGuard_七天连接历史.txt")
                }) { Text("保存 TXT") }
                OutlinedButton(onClick = {
                    jsonPicker.launch("OpenKinGuard_七天连接历史.json")
                }) { Text("保存 JSON") }
            }
            if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
            if (loading) Text("正在读取历史……")
            else if (events.isEmpty()) Text("暂无相应记录；无记录不代表设备始终在线。")
            events.forEach { event ->
                val time = DateFormat.getDateTimeInstance().format(Date(event.timeMillis))
                val label = historyLabel(event.path)
                val status = if (event.kind == "snapshot") {
                    val sample = runCatching { JSONObject(event.after) }.getOrNull()
                    if (sample == null) "快照内容不可用" else {
                        val names = sample.keys().asSequence().sorted().take(12).toList()
                        names.joinToString(" · ") {
                            historyLabel(it) + "=" + sample.optString(it)
                        } + if (sample.length() > 12) " · …" else ""
                    }
                } else {
                    event.before + " → " + event.after +
                        if (event.durationMillis > 0L)
                            "（上一状态持续" + (event.durationMillis / 1000L) + "秒）"
                        else ""
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(time + " · " + when (event.kind) {
                        "snapshot" -> "周期快照"
                        "initial" -> "初始状态"
                        else -> "状态变化"
                    }, style = MaterialTheme.typography.labelMedium)
                    Text("$label：$status", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                    enabled = pageIndex > 0) { Text("上一页") }
                Text("第${pageIndex + 1}页", modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { pageIndex++ }, enabled = events.size == 100) {
                    Text("下一页")
                }
            }
            Text("导出的记录只有时间、连接状态码和匿名设备代号，不包含 IP、路由器地址、完整设备指纹、私钥或原始错误日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun historyLabel(path: String): String = when {
    path == "network/type" -> "网络类型"
    path == "network/validated" -> "系统网络验证"
    path == "app/foreground" -> "APP前后台"
    path == "background/consent" -> "后台监听"
    path == "background/healthy" -> "后台健康"
    path == "tcp/listener" -> "TCP监听"
    path == "lan/listener" -> "局域网监听"
    path == "nostr/socket" -> "公共信令"
    path == "udp/socket" -> "UDP Socket"
    path.startsWith("relay/") -> "家庭中继 " + path.removePrefix("relay/")
    path.startsWith("peer/") -> "家人设备 " + path.removePrefix("peer/")
    path == "all" -> "状态集合"
    else -> path
}
