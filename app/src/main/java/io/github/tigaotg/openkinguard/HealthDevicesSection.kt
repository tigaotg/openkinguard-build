package io.github.tigaotg.openkinguard

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.tigaotg.openkinguard.core.engine.HealthDataFreshness
import io.github.tigaotg.openkinguard.core.engine.HealthDataFreshnessPolicy
import io.github.tigaotg.openkinguard.core.engine.HeartRatePilotPhase
import io.github.tigaotg.openkinguard.core.engine.StandardHealthProfile
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private fun healthCsv(rows: List<HealthBleReading>): String = buildString {
    appendLine("time_utc_ms,metric,value,unit,secondary,device_name")
    rows.sortedBy { it.capturedAt }.forEach { record ->
        fun cell(value: String) = "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""
        appendLine(listOf(record.capturedAt.toString(), cell(record.metric.kind.label),
            record.metric.value.toString(), cell(record.metric.unit),
            record.metric.secondary?.toString() ?: "", cell(record.deviceName)).joinToString(","))
    }
}

@Composable
private fun HealthLatestReadings(state: HealthBleState, nowMillis: Long) {
    Text("最新测量", style = MaterialTheme.typography.titleLarge)
    val latest = state.readings.distinctBy { it.metric.kind }
    if (latest.isEmpty()) {
        Text("还没有读数。连接设备后，请等待手环实际测量并上报。",
            style = MaterialTheme.typography.bodySmall)
    } else latest.forEach { record ->
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(record.metric.kind.label, style = MaterialTheme.typography.titleMedium)
                Text("${record.metric.display} ${record.metric.unit}",
                    style = MaterialTheme.typography.headlineMedium)
                val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(record.capturedAt))
                val freshness = HealthDataFreshnessPolicy.assess(nowMillis, record.capturedAt)
                Text("${record.deviceName} · $time · " + when (freshness) {
                    HealthDataFreshness.RECENT -> "最近5分钟读数"
                    HealthDataFreshness.STALE -> "已过期的历史读数"
                    HealthDataFreshness.CLOCK_MISMATCH -> "时间异常，需核对手机时钟"
                    HealthDataFreshness.NO_READING -> "无记录"
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Separate screens share the same BLE monitor, but only show controls relevant to that screen. */
enum class WearableSectionMode { OVERVIEW, ALERTS, HISTORY, TECHNICAL }

@Composable
fun HealthDevicesSection(monitor: HealthBleMonitor,
    mode: WearableSectionMode = WearableSectionMode.OVERVIEW,
    onOpenHealthSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val state = monitor.state
    var exportMessage by remember { mutableStateOf("") }
    var showGattDetails by remember { mutableStateOf(false) }
    var showConnectionEvents by remember { mutableStateOf(false) }
    var expandedCandidateAddress by remember { mutableStateOf<String?>(null) }
    var showScanOptions by remember { mutableStateOf(false) }
    var showSessionTools by remember { mutableStateOf(false) }
    var showAllCandidates by remember { mutableStateOf(false) }
    var onlyBonded by remember { mutableStateOf(false) }
    var onlyNamed by remember { mutableStateOf(false) }
    var historyPreview by remember { mutableStateOf<List<HealthBleReading>>(emptyList()) }
    var historyCount by remember { mutableIntStateOf(-1) }
    var clearHistoryConfirm by remember { mutableStateOf(false) }
    var editingAliasAddress by remember { mutableStateOf<String?>(null) }
    var aliasDraft by remember { mutableStateOf("") }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            clockNow = System.currentTimeMillis()
        }
    }
    LaunchedEffect(monitor) {
        while (true) {
            monitor.refreshHeartPilot()
            delay(5_000L) // Foreground UI only: reveal an expired measurement promptly.
        }
    }
    DisposableEffect(monitor) {
        onDispose { monitor.stopScan() }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.isNotEmpty() && grants.values.all { it }) monitor.startScan()
        else exportMessage = "尚未允许蓝牙扫描或连接权限；不会尝试读取附近设备"
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            exportMessage = runCatching {
                val rows = state.readings.sortedBy { it.capturedAt }
                require(rows.isNotEmpty()) { "本次会话还没有收到真实测量值" }
                val csv = healthCsv(rows)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选文件")
                "已导出本次会话的 ${rows.size} 条真实测量记录；CSV 是未加密文件"
            }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
        }
    }
    val historyExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportMessage = runCatching {
            val records = monitor.historyReadings(5_000)
            require(records.isNotEmpty()) { "没有可导出的加密历史记录" }
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(healthCsv(records).toByteArray(Charsets.UTF_8))
            } ?: error("无法写入选择的文件")
            "已导出 ${records.size} 条真实读数（最多最近5000条）；导出的 CSV 不再加密"
        }.getOrElse { "导出历史失败：${it.message ?: it.javaClass.simpleName}" }
    }

    if (mode == WearableSectionMode.OVERVIEW) {
    Text("穿戴设备", style = MaterialTheme.typography.headlineMedium)
    FeatureHelp("只连接本机蓝牙健康设备，读取设备实际上报的测量值。" +
        "穿戴设备不是已绑定的家人手机，家属也不会自动获得这些数据。", label = "穿戴与家人设备有什么区别")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.connected) "● 已连接：${state.connectedName.ifBlank { "健康设备" }}" else
                "○ 尚未连接健康设备", style = MaterialTheme.typography.titleMedium)
            Text(state.status, style = MaterialTheme.typography.bodySmall)
            val latestFreshness = HealthDataFreshnessPolicy.assess(clockNow, state.lastMeasurementAt)
            Text(when (latestFreshness) {
                HealthDataFreshness.NO_READING -> "等待设备上报真实读数"
                HealthDataFreshness.RECENT -> "最近5分钟有新读数"
                HealthDataFreshness.STALE -> "最新读数已过期"
                HealthDataFreshness.CLOCK_MISMATCH -> "手机时间异常，读数时效未知"
            }, style = MaterialTheme.typography.bodySmall)
            state.batteryLevel?.let { Text("设备电量：$it%") }
        }
    }

    HealthLatestReadings(state, clockNow)

    Text("查找和连接", style = MaterialTheme.typography.titleLarge)
    FeatureHelp("会同时列出系统已配对/已建立 GATT 连接的设备与新扫描到的 BLE 设备。" +
        "系统昵称优先于广播名称；没有名称时可通过信号强度、设备类型及广播协议辅助区分。" +
        "你也可以为确认过的设备设置仅本机可见的备注，它会优先于系统昵称显示。" +
        "仅凭名称、信号或蓝牙标识不能确认设备身份。扫描持续 12 秒。",
        label = "如何找到自己的手环")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = state.available && !state.scanning, onClick = {
            exportMessage = ""
            if (monitor.hasPermission()) monitor.startScan()
            else permissionLauncher.launch(monitor.requiredPermissions())
        }) { Text("扫描附近设备") }
        OutlinedButton(enabled = state.scanning, onClick = { monitor.stopScan() }) {
            Text("停止扫描")
        }
    }
    if (!state.available) Text("当前手机未发现蓝牙适配器，不能使用 BLE 健康设备。",
        color = MaterialTheme.colorScheme.error)
    TextButton(onClick = { showScanOptions = !showScanOptions }) {
        Text(if (showScanOptions) "收起筛选和蓝牙设置" else "筛选和蓝牙设置 ▾")
    }
    if (showScanOptions) {
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }) { Text("打开系统蓝牙设置") }
        OutlinedButton(enabled = state.available, onClick = {
            if (monitor.hasPermission()) monitor.refreshSystemDevices()
            else permissionLauncher.launch(monitor.requiredPermissions())
        }) { Text("刷新系统已配对设备") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = onlyBonded, onClick = { onlyBonded = !onlyBonded },
                label = { Text("仅已配对") })
            FilterChip(selected = onlyNamed, onClick = { onlyNamed = !onlyNamed },
                label = { Text("有名称") })
        }
    }
    val filteredCandidates = state.candidates.filter { candidate ->
        (!onlyBonded || candidate.isBonded) &&
            (!onlyNamed || candidate.nameSource != "未提供名称")
    }.sortedWith(compareByDescending<HealthBleCandidate> { it.isBonded }
        .thenByDescending { it.nameSource != "未提供名称" }
        .thenByDescending { it.rssi ?: -200 })
    val visibleCandidates = if (showAllCandidates) filteredCandidates else filteredCandidates.take(8)
    Text("发现 ${filteredCandidates.size} 台设备" +
        if (filteredCandidates.size > 8 && !showAllCandidates) " · 优先显示前8台" else "",
        style = MaterialTheme.typography.bodySmall)
    if (filteredCandidates.size > 8) TextButton(onClick = {
        showAllCandidates = !showAllCandidates
    }) { Text(if (showAllCandidates) "只看前8台" else "显示全部设备") }

    if (visibleCandidates.isEmpty()) {
        Text(if (state.scanning) "正在搜索…" else if (state.candidates.isEmpty())
            "列表为空；请扫描附近设备或刷新系统已配对设备。" else
            "当前筛选条件下没有设备；可关闭筛选查看其他设备。",
            style = MaterialTheme.typography.bodyMedium)
    } else {
        visibleCandidates.forEach { candidate ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(candidate.name, style = MaterialTheme.typography.titleMedium)
                    Text("${if (candidate.isBonded) "系统已配对" else "未配对"} · " +
                        (candidate.rssi?.let { "信号 $it dBm" } ?: "信号未知"),
                        style = MaterialTheme.typography.bodySmall)
                    Button(enabled = candidate.bluetoothType != "经典蓝牙", onClick = {
                        monitor.connect(candidate.address)
                    }) { Text("连接设备") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        editingAliasAddress = candidate.address
                        aliasDraft = if (candidate.nameSource == "用户本机备注") candidate.name else ""
                    }) { Text(if (candidate.nameSource == "用户本机备注") "改备注" else "备注") }
                    TextButton(onClick = {
                        expandedCandidateAddress = if (expandedCandidateAddress == candidate.address)
                            null else candidate.address
                    }) { Text(if (expandedCandidateAddress == candidate.address) "收起详情" else "设备详情") }
                    }
                    if (expandedCandidateAddress == candidate.address) {
                        Text("名称来源：${candidate.nameSource} · ${candidate.bluetoothType}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (candidate.isSystemGattConnected)
                            "系统 GATT 已连接（不代表本应用已连接）" else "系统 GATT 未报告连接",
                            style = MaterialTheme.typography.bodySmall)
                        Text("蓝牙标识后五位：${candidate.address.takeLast(5)}",
                            style = MaterialTheme.typography.bodySmall)
                        if (candidate.manufacturerIds.isNotEmpty()) Text(
                            "厂商广播编号：${candidate.manufacturerIds.joinToString("、") { "0x%04X".format(it) }}" +
                                "（仅供识别线索，不能证明品牌）",
                            style = MaterialTheme.typography.bodySmall)
                        if (candidate.advertisedServiceUuids.isNotEmpty()) Text(
                            "广播服务 UUID：${candidate.advertisedServiceUuids.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (candidate.advertisedProfiles.isEmpty())
                            "广播中未声明标准健康服务；连接后会进一步检查" else
                            "广播协议：${candidate.advertisedProfiles.joinToString("、") { it.label }}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    editingAliasAddress?.let { address ->
        AlertDialog(onDismissRequest = { editingAliasAddress = null },
            title = { Text("本机设备备注") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("只会保存用户输入的备注与本机加盐索引，不保存明文蓝牙地址；" +
                        "设备若使用随机变化的蓝牙地址，未来可能无法自动匹配。请先通过近距离信号变化确认设备。")
                    OutlinedTextField(value = aliasDraft, onValueChange = { aliasDraft = it.take(32) },
                        label = { Text("例如：我的华为手环") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = {
                exportMessage = runCatching {
                    monitor.setLocalDeviceAlias(address, aliasDraft)
                    "本机备注已${if (aliasDraft.isBlank()) "移除" else "保存"}"
                }.getOrElse { "保存备注失败：${it.message ?: it.javaClass.simpleName}" }
                editingAliasAddress = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editingAliasAddress = null }) { Text("取消") } })
    }
    if (exportMessage.contains("备注")) InlineActionFeedback(exportMessage)
    FeatureHelp("如果华为运动健康已连接 Band 8，但这里仍没有昵称：手环可能只与华为 App 建立私有会话，" +
        "系统未向第三方应用提供名称或配对记录。不要为了测试而解绑或重置手环；" +
        "可以先关闭‘只看已配对’筛选，再对比手环靠近/远离手机时的信号变化，确认后可添加本机备注。",
        label = "找不到华为手环？")
    if (state.connected) OutlinedButton(onClick = { monitor.disconnect() }) {
        Text("断开健康设备")
    }
    OutlinedButton(onClick = onOpenHealthSettings) { Text("穿戴设置 →") }
    }

    if (mode == WearableSectionMode.ALERTS) {
    HorizontalDivider()
    Text("本机心率阈值预警 · 前台试验", style = MaterialTheme.typography.titleLarge)
    Text("只有真正收到蓝牙标准心率 0x2A37 的连续新数据，才会在本机尝试发送通知；" +
        "默认关闭，不读取华为手环私有 FE86 数据，不会自动发短信、打电话、创建求救事件或通知家属。" +
        "离开 App、锁屏、蓝牙断连或传感器停止上报时，不能依靠此功能监护。",
        style = MaterialTheme.typography.bodyMedium)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = monitor.heartPilotSettings.enabled,
                    onCheckedChange = { monitor.configureHeartPilot(enabled = it) })
                Text(if (monitor.heartPilotSettings.enabled) "实验预警：已自愿开启" else "实验预警：默认关闭")
            }
            val settings = monitor.heartPilotSettings
            Text("偏低阈值：低于 ${settings.lowBpm} 次/分")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(40, 50, 60).forEach { threshold ->
                    FilterChip(selected = settings.lowBpm == threshold, onClick = {
                        monitor.configureHeartPilot(lowBpm = threshold)
                    }, label = { Text("$threshold") })
                }
            }
            Text("偏高阈值：高于 ${settings.highBpm} 次/分")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(100, 120, 140).forEach { threshold ->
                    FilterChip(selected = settings.highBpm == threshold, onClick = {
                        monitor.configureHeartPilot(highBpm = threshold)
                    }, label = { Text("$threshold") })
                }
            }
            Text("必须持续异常的时间：${settings.durationMillis / 60_000} 分钟；" +
                "相邻真实读数若超过 90 秒未更新，连续计时清零")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 3, 5).forEach { minutes ->
                    FilterChip(selected = settings.durationMillis == minutes * 60_000L,
                        onClick = { monitor.configureHeartPilot(durationMillis = minutes * 60_000L) },
                        label = { Text("$minutes 分钟") })
                }
            }
            val status = monitor.heartPilotStatus
            val stateLabel = when (status.phase) {
                HeartRatePilotPhase.OFF -> "未启用：不会判断或通知"
                HeartRatePilotPhase.DISCONNECTED -> "设备未连接：监测不可用"
                HeartRatePilotPhase.NO_DATA -> "等待本次连接的真实心率数据"
                HeartRatePilotPhase.NORMAL -> "最新连续样本未越过设定阈值"
                HeartRatePilotPhase.COUNTING_LOW -> "已出现偏低读数：正在核验持续时间"
                HeartRatePilotPhase.COUNTING_HIGH -> "已出现偏高读数：正在核验持续时间"
                HeartRatePilotPhase.ALERT_LOW -> "连续偏低条件已满足，曾尝试本机通知"
                HeartRatePilotPhase.ALERT_HIGH -> "连续偏高条件已满足，曾尝试本机通知"
                HeartRatePilotPhase.STALE -> "心率样本已过期：连续判定中断，不能视作实时监测"
            }
            Text("当前判断：$stateLabel", style = MaterialTheme.typography.titleMedium)
            status.lastBpm?.let { Text("本次实验最近样本：$it 次/分；" +
                "本轮连续异常测量 ${status.consecutiveSamples} 次（不表示诊断）",
                style = MaterialTheme.typography.bodySmall) }
            Text("本次打开应用后触发：${monitor.heartPilotNoticeCount} 次；" +
                "${monitor.heartPilotLastNotice}", style = MaterialTheme.typography.bodySmall)
            Text("通知权限：${S1TestNotifier.heartPilotNotificationStatus(context)}",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { monitor.testHeartPilotNotification() }) {
                Text("仅测试本机通知（不需要心率数据）")
            }
            Text("设置发生变化会清空连续计时；通知提交不代表系统一定弹窗，更不代表任何人收到。" +
                "阈值仅供软件试验，不代表适合你或家人的医学界限。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    }

    if (mode == WearableSectionMode.TECHNICAL) {
    HorizontalDivider()
    Text("蓝牙接口与兼容情况", style = MaterialTheme.typography.titleLarge)
    if (state.connectedName.isNotBlank()) Text("设备：${state.connectedName}")
    if (state.connectedAt != null) Text("已建立 GATT 会话：" +
        DateFormat.getTimeInstance().format(Date(state.connectedAt)))
    if (state.profiles.isNotEmpty()) Text("已识别协议：" +
        state.profiles.joinToString("、") { it.label })
    if (state.huaweiServiceDetected) Text("发现华为 FE86 私有接口；尚未进行厂商身份验证。" +
        "可以读取公开信息，但无法据此直接启动后台心率。",
        style = MaterialTheme.typography.bodySmall)
    if (state.gattServices.isNotEmpty()) Text(
        if (state.standardHeartRateDetected)
            "已发现标准 0x180D/0x2A37 心率通道；是否有读数还取决于设备是否上报。"
        else "当前 GATT 清单没有标准 0x180D/0x2A37 心率通道；不能把私有通知解码成心率。",
        style = MaterialTheme.typography.bodySmall)
    FeatureHelp("这是开发者排查设备兼容性的技术信息。" +
        "普通使用只需要回到穿戴页面连接设备和查看读数。", label = "这些接口是什么意思")
    Text("新版自动按顺序读取公开设备名、厂商、型号、固件版本以及标准电量，" +
        "也能识别华为 FE86 服务。但不会写入华为私有特征、猜测认证密钥、" +
        "恢复出厂或修改手环配置。发现接口不代表已实现私有心率同步。",
        style = MaterialTheme.typography.bodyMedium)
    if (state.deviceProperties.isNotEmpty()) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("公开设备资料", style = MaterialTheme.typography.titleMedium)
                state.deviceProperties.forEach { property ->
                    Text("${property.title}：${property.value}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text("为保护隐私，程序不自动读取设备序列号。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (state.gattServices.isEmpty()) {
        Text("尚无蓝牙服务清单。关闭手环的心率广播后，可在不解绑手环的前提下尝试扫描并连接；" +
            "如蓝牙被华为运动健康占用，先不要强行重新配对。")
    } else {
        Text("已发现 ${state.gattServices.size} 个 GATT 服务。清单不包含蓝牙地址、健康测量值或账户认证信息。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showGattDetails = !showGattDetails }) {
                Text(if (showGattDetails) "收起接口详情" else "展开接口详情")
            }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenKinGuard GATT", monitor.gattDiagnosticText()))
                exportMessage = "接口清单已复制；可以粘贴到聊天里用于下一步适配"
            }) { Text("复制接口清单") }
        }
        if (exportMessage.startsWith("接口清单")) {
            Text(exportMessage, style = MaterialTheme.typography.bodySmall)
        }
        if (showGattDetails) {
            state.gattServices.forEach { service ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("服务：${service.uuid}", style = MaterialTheme.typography.bodyMedium)
                        service.characteristics.forEach { characteristic ->
                            Text("${characteristic.uuid} · ${characteristic.properties}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = { showConnectionEvents = !showConnectionEvents }) {
        Text(if (showConnectionEvents) "收起连接事件" else "显示连接事件（不含私有数据）")
    }
    if (showConnectionEvents) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenKinGuard BLE 事件",
                    monitor.connectionDiagnosticText()))
                exportMessage = "连接事件已复制（不含蓝牙 MAC 或测量值）"
            }) { Text("复制事件") }
        }
        state.diagnosticEvents.forEach { entry ->
            Text("${DateFormat.getTimeInstance().format(Date(entry.recordedAt))} · ${entry.message}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    }

    if (mode == WearableSectionMode.OVERVIEW) {
    TextButton(onClick = { showSessionTools = !showSessionTools }) {
        Text(if (showSessionTools) "收起读数操作" else "读数导出与清理 ▾")
    }
    if (showSessionTools) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = state.readings.isNotEmpty(), onClick = {
            exportLauncher.launch("OpenKinGuard_health_${System.currentTimeMillis()}.csv")
        }) { Text("导出 CSV") }
        OutlinedButton(enabled = state.readings.isNotEmpty(), onClick = {
            monitor.clearReadings()
            exportMessage = "会话读数已清空"
        }) { Text("清空会话读数") }
    }
    if (exportMessage.isNotBlank()) Text(exportMessage, style = MaterialTheme.typography.bodySmall)
    }
    }

    if (mode == WearableSectionMode.HISTORY) {
    HorizontalDivider()
    Text("加密健康历史 · 仅本机", style = MaterialTheme.typography.titleLarge)
    Text("只有设备真实上报的数据才能保存。默认关闭记录；开启后测量值与设备名称使用" +
        " Android Keystore AES-GCM 加密保存在本机，不上传服务器、不授予家属查看权限。" +
        "数据库会保留用于清理记录的时间戳。",
        style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = monitor.historyEnabled, onCheckedChange = { enabled ->
            monitor.enableHistory(enabled)
            exportMessage = if (enabled) "已开始加密保存此后新收到的实际测量值" else
                "已停止记录；原有加密历史仍保留，可单独清空"
        })
        Text(if (monitor.historyEnabled) "新读数保存：开启" else "新读数保存：关闭")
    }
    Text("自动保留：${monitor.historyRetentionDays} 天；过期记录在写入、查询或修改保留天数时清理。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 30, 90).forEach { days ->
            FilterChip(selected = monitor.historyRetentionDays == days,
                onClick = { monitor.setHistoryRetention(days) },
                label = { Text("$days 天") })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            runCatching {
                historyCount = monitor.historyCount()
                historyPreview = monitor.historyReadings(20)
            }.onFailure { exportMessage = "读取加密记录失败：${it.message ?: it.javaClass.simpleName}" }
        }) { Text("查看最近记录") }
        OutlinedButton(onClick = {
            historyExportLauncher.launch("OpenKinGuard_encrypted_history_${System.currentTimeMillis()}.csv")
        }) { Text("导出历史 CSV") }
    }
    if (historyCount >= 0) Text("本机加密记录：$historyCount 条（下方最多显示最近 20 条）",
        style = MaterialTheme.typography.bodySmall)
    historyPreview.forEach { record ->
        Text("${DateFormat.getDateTimeInstance().format(Date(record.capturedAt))} · " +
            "${record.metric.kind.label} ${record.metric.display} ${record.metric.unit} · ${record.deviceName}",
            style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = { clearHistoryConfirm = true }) { Text("清空本机加密历史…") }
    if (clearHistoryConfirm) AlertDialog(onDismissRequest = { clearHistoryConfirm = false },
        title = { Text("确定清空全部健康历史？") },
        text = { Text("这会永久删除本测试版保存的加密健康记录，不能恢复。" +
            "不会删除华为运动健康中的资料，也不会影响当前连接。") },
        confirmButton = { TextButton(onClick = {
            runCatching { monitor.clearHistory() }.onSuccess {
                historyCount = 0
                historyPreview = emptyList()
                exportMessage = "本机加密健康记录已清空"
            }.onFailure { exportMessage = "清空失败：${it.message ?: it.javaClass.simpleName}" }
            clearHistoryConfirm = false
        }) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = { clearHistoryConfirm = false }) { Text("取消") } })
    }

    if (mode == WearableSectionMode.TECHNICAL) {
    HorizontalDivider()
    Text("目前支持的标准接口", style = MaterialTheme.typography.titleLarge)
    StandardHealthProfile.entries.forEach { profile ->
        Text("• ${profile.label} — BLE GATT 0x${profile.serviceId.toString(16).uppercase()}" +
            "（需要设备真正公开标准测量特征）")
    }
    Text("待适配：部分血糖仪、智能手表、厂商私有蓝牙设备、USB/串口健康仪器。" +
        "本版尚未实现血糖数据解析或厂商私有协议。",
        style = MaterialTheme.typography.bodySmall)
    }

    if (mode == WearableSectionMode.OVERVIEW) FeatureHelp(
        "目前只支持前台连接标准蓝牙健康设备。蓝牙连接成功不代表已经收到心率读数；" +
            "离开应用或设备断连时不能依赖它报警。", label = "测量安全说明")
    if (mode == WearableSectionMode.ALERTS) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text("健康安全说明：当前仅提供前台 BLE 设备接入、真实测量显示，" +
            "以及用户自行开启的本机持续阈值通知试验。" +
            "没有后台持续监测、异常阈值自动求救、自动短信、云端或家属同步。" +
            "数值精度取决于传感器和使用条件，不作为医疗诊断依据；离开应用或设备断连后不能依赖它报警。",
            modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
    }
    if (mode == WearableSectionMode.HISTORY || mode == WearableSectionMode.TECHNICAL) {
        FeatureHelp("当前只支持前台 BLE 测量，不提供可靠的后台健康异常自动求救、" +
            "自动短信或家属同步。测量值取决于真实设备，不能当作医疗诊断。",
            label = "安全与使用限制")
    }
}
