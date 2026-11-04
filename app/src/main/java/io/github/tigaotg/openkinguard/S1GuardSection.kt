package io.github.tigaotg.openkinguard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Integrated S1: no manual IP, no cloud account, no automatic paid actions. */
@Composable
fun S1GuardSection(simpleMode: Boolean, onSimpleMode: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember { S1GuardManager(context.applicationContext) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var snapshot by remember { mutableStateOf(manager.snapshot()) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmSos by remember { mutableStateOf(false) }
    var confirmingClose by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showExtra by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    var contactNumber by remember { mutableStateOf("") }
    val notificationsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        result = if (granted) "演练通知已允许，请点“发送通知测试”验证通知栏"
            else "通知被拒绝，仍可在本应用内查看测试事件；可打开系统设置允许通知"
    }

    // Android 13+ installs start with notifications disabled. Request once when the
    // owner first opens this screen; never auto-request on every subsequent launch.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val settings = context.getSharedPreferences("okg.s1.ui", 0)
            if (!settings.getBoolean("notification_prompt_requested_v1", false)) {
                settings.edit().putBoolean("notification_prompt_requested_v1", true).apply()
                notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun refresh() { handler.post { snapshot = manager.snapshot() } }
    fun runTask(title: String, block: () -> String) {
        if (busy) return
        busy = true
        result = "$title…"
        Thread({
            val outcome = runCatching { block() }
            handler.post {
                result = outcome.fold({ it }, { "操作未成功：${it.message ?: it.javaClass.simpleName}" })
                busy = false
                snapshot = manager.snapshot()
            }
        }, "okg-s1-ui-action").start()
    }

    DisposableEffect(manager, lifecycleOwner) {
        val updated = {
            refresh()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    try { manager.start(updated) }
                    catch (e: Exception) { result = "局域网监听未开启：${e.message}" }
                }
                Lifecycle.Event.ON_STOP -> manager.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            try { manager.start(updated) }
            catch (e: Exception) { result = "局域网监听未开启：${e.message}" }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            manager.stop()
        }
    }

    LaunchedEffect(manager) {
        // Only while the app is in the foreground. All network I/O remains off the UI thread.
        while (true) {
            manager.probeAsync()
            withContext(Dispatchers.IO) { runCatching { manager.deliverPending() } }
            snapshot = manager.snapshot()
            delay(8000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("局域网互助演练", style = MaterialTheme.typography.headlineSmall)
        Text("此页面全部为测试报警，不会自动拨打电话、发送收费短信，也不能用于真实救援。",
            color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("简易界面（大按钮）")
            Switch(checked = simpleMode, onCheckedChange = onSimpleMode)
        }
        Text(if (snapshot.listening) "局域网服务：前台运行中，正在自动发现设备"
             else "局域网服务：未启动。需要打开本应用、接入同一 Wi-Fi。")
        if (snapshot.discoveryError.isNotBlank()) {
            Text("自动发现故障：${snapshot.discoveryError}", color = MaterialTheme.colorScheme.error)
        }
        Text("已发现 ${snapshot.nodes.size} 台 OpenKinGuard 局域网服务；" +
            "已通过公钥验签匹配 ${snapshot.verifiedRelations.size} 条帮助关系。")
        HorizontalDivider()
        Text("局域网免扫码绑定", style = MaterialTheme.typography.titleLarge)
        Text("两部手机都打开本页、连接同一 Wi-Fi。发现后由需要帮助的一方发出邀请，" +
            "另一方同意；随后两边核对相同的8位安全码并分别确认。陌生设备不会被自动授权。")
        val pendingPairing = manager.pendingPairing()
        if (pendingPairing == null) {
            if (snapshot.nodes.isEmpty()) Text("尚未发现其他手机，保持双方应用前台打开。")
            snapshot.nodes.forEach { node ->
                OutlinedButton(enabled = !busy, onClick = {
                    runTask("向附近设备发送绑定申请") {
                        manager.inviteOnLan(node)
                        "绑定邀请已发送，请在另一台手机确认"
                    }
                }) { Text("邀请设备 ${node.serviceName} 成为我的救助者") }
            }
        } else {
            val phase = pendingPairing.phase
            Text("绑定状态：${pairingPhaseName(phase)}")
            Text(if (pendingPairing.role == PairingRole.SUBJECT)
                "本轮：我是被救助者，邀请对方成为我的救助者"
                else "本轮：我是救助者，对方请求我的帮助")
            val safety = runCatching { manager.pairingSafetyCode() }.getOrNull()
            if (safety != null) {
                Text("两机核对安全码：$safety", style = MaterialTheme.typography.headlineSmall)
                Text("务必与另一台手机显示的8位数字逐位一致；不一致时取消，绝不能跳过。")
            }
            if (phase == PairingPhase.COMPLETE) {
                Text("已建立本机帮助关系，双方再次打开应用也会保存。")
                Button(enabled = !busy, onClick = {
                    runTask("保留现有关系") {
                        manager.cancelLanPairing()
                        "已保留帮助关系，可以由另一部手机发起反向绑定，实现双向互助"
                    }
                }) { Text("保留本次绑定，开始下一轮") }
            } else if (!manager.canContinueLanPairing()) {
                Text("局域网会话已中断，请取消本轮重新开始。已完成的原有关系不受影响。",
                    color = MaterialTheme.colorScheme.error)
            } else if (phase in listOf(PairingPhase.REVIEW_INVITE, PairingPhase.REVIEW_RESPONSE,
                    PairingPhase.REVIEW_GRANT)) {
                key(phase) {
                    var approved by remember { mutableStateOf(false) }
                    Row {
                        Checkbox(checked = approved, onCheckedChange = { approved = it })
                        Text(if (phase == PairingPhase.REVIEW_INVITE)
                            "我同意帮助这台设备；之后还会核对双方安全码"
                            else "我已当面或通过可信方式核对两台手机的8位安全码完全一致")
                    }
                    Button(enabled = approved && !busy, onClick = {
                        runTask("正在签名并发送下一步") {
                            manager.confirmLanPairing()
                            "本机已确认并发出签名应答，请检查另一台手机"
                        }
                    }) { Text("确认并继续安全绑定") }
                }
            } else if (phase == PairingPhase.ACK_TO_SHOW) {
                Button(enabled = !busy, onClick = {
                    runTask("完成帮助关系") {
                        manager.finishLanPairing()
                        "本轮绑定已完成；如需互相帮助，请由另一台手机再发起一轮反向绑定"
                    }
                }) { Text("对方已显示绑定完成，结束本轮") }
            }
            if (phase != PairingPhase.COMPLETE) {
                OutlinedButton(enabled = !busy, onClick = {
                    runTask("取消本轮") { manager.cancelLanPairing(); "本轮已取消，原有关系保留" }
                }) { Text("取消当前绑定") }
            }
        }
        HorizontalDivider()
        if (snapshot.nodes.isNotEmpty() && snapshot.verifiedRelations.isEmpty()) {
            Text("已发现设备，但尚未验证身份。因此SOS会留在本机等待，不会错误显示已送达。",
                color = MaterialTheme.colorScheme.error)
            snapshot.diagnostics.lastOrNull()?.let {
                Text("最近一次认证信息：$it", color = MaterialTheme.colorScheme.error)
            }
        }
        val active = manager.relations()
        active.forEach { peer ->
            val online = peer.relationId in snapshot.verifiedRelations
            Text("${if (peer.role == PairingRole.SUBJECT) "我是被救助者 · 这是我绑定的救助者" else "我是救助者 · 这是我帮助的被救助者"} · " +
                "关系${peer.relationId.take(8)} · ${peer.peerFingerprint.take(12)}… · " +
                if (online) "已认证在线" else "未认证")
        }
        if (active.isEmpty()) Text("尚未绑定设备。可以在上面的局域网列表中免扫码绑定，扫码仍作为备用方式。")
        if (active.isNotEmpty() && active.none { it.role == PairingRole.SUBJECT }) {
            Text("当前本机只负责帮助对方，尚无授权接收本机求救的人。如需双向求救，请由本机向对方再发起一轮免扫码绑定。")
        }
        Button(onClick = { manager.probeAsync(); refresh() }, enabled = snapshot.listening) {
            Text("重新验证已发现设备")
        }
        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
            Text(if (showDiagnostics) "收起高级信息" else "高级信息 · 握手记录与连接诊断")
        }
        if (showDiagnostics) {
            Text("最近握手记录（本机加密保存，最多120条；仅记公钥指纹摘要和步骤，不记私钥）")
            snapshot.history.handshakeLog.takeLast(40).asReversed().forEach { record ->
                Text("${timeText(record.time)} | ${record.direction} | ${record.stage} | " +
                    "${record.result} | 关系 ${record.relation.take(10)} | ${record.detail}")
            }
            if (snapshot.history.handshakeLog.isEmpty()) Text("暂无握手记录")
            Text("已发现的节点：${snapshot.nodes.joinToString { it.address + ":" + it.port }.ifEmpty { "无" }}")
            if (snapshot.diagnostics.isEmpty()) Text("尚无认证记录；保持两台应用前台打开，再点击重新验证。")
            snapshot.diagnostics.takeLast(8).asReversed().forEach { Text(it) }
            Text("请将两部手机这里的错误文字或截图发给开发者。遇到验签错误不可跳过认证。")
        }
        HorizontalDivider()
        Button(modifier = Modifier.fillMaxWidth().height(if (simpleMode) 76.dp else 58.dp)
            .semantics { contentDescription = "发起本机演练SOS，点击后需要再次确认" },
            onClick = { confirmSos = true }) {
            Text("发起测试 SOS", style = if (simpleMode) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.titleMedium)
        }
        Text("只会发送给已认证的帮助者；收到签名回执后才算设备送达。")
        Text("本机通知：${S1TestNotifier.status(context)}")
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Button(onClick = { notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("允许 SOS 演练通知（Android 13+ 必需）")
            }
        }
        if (!S1TestNotifier.status(context).startsWith("通知权限正常")) {
            OutlinedButton(onClick = { context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
                Text("打开本应用的系统通知设置")
            }
        }
        TextButton(onClick = { result = S1TestNotifier.show(context,
            "这是一条手动触发的通知栏测试，不是真实求救").ifEmpty {
                "测试通知已提交给系统，请下拉通知栏查看；是否弹窗取决于系统设置" } }) {
            Text("发送一条本机通知测试")
        }
        if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
        Text("我发起的演练", style = MaterialTheme.typography.titleLarge)
        val mine = snapshot.history.cases.filter { it.ownSos }.sortedByDescending { it.createdAt }
        if (mine.isEmpty()) Text("尚未创建演练事件")
        mine.take(15).forEach { event ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("测试SOS ${event.eventId.take(8)} · ${statusName(event.status)}")
                    Text("${timeText(event.createdAt)} · 已验证收到 ${event.deliveredRelations.size} 条关系的设备回执")
                    if (event.acceptedBy.isNotBlank()) Text("已由设备 ${event.acceptedBy.take(12)}… 确认接手（仅演练）")
                    if (event.status == ExerciseStatus.CLOSED) {
                        Text("关闭状态：${event.closedSyncedRelations.size} 台关系设备已签名确认")
                    }
                    OutlinedButton(enabled = !busy, onClick = {
                        runTask("重新尝试局域网送达") {
                            manager.retryOwn(event.eventId)
                            val failures = manager.deliverPending()
                            if (failures.isEmpty()) "已尝试同步；请查看签名回执计数"
                            else "仍有未确认状态：${failures.take(2).joinToString()}"
                        }
                    }) { Text("重新尝试送达/同步") }
                    if (event.status != ExerciseStatus.CLOSED) {
                        OutlinedButton(onClick = { confirmingClose = event.eventId }, enabled = !busy) {
                            Text("本人确认结束演练")
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("我收到的演练", style = MaterialTheme.typography.titleLarge)
        val received = snapshot.history.cases.filter { !it.ownSos }.sortedByDescending { it.createdAt }
        if (received.isEmpty()) Text("尚未接收到其他已授权设备的测试SOS")
        received.take(15).forEach { item ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("来自 ${item.subjectFingerprint.take(12)}… 的测试SOS")
                    Text("${timeText(item.createdAt)} · ${statusName(item.status)}")
                    if (item.status == ExerciseStatus.OPEN || item.status == ExerciseStatus.CLAIM_PENDING) {
                        Button(enabled = !busy, onClick = {
                            runTask("正在请求本人设备确认接手") {
                                when (manager.claim(item.eventId)) {
                                    io.github.tigaotg.openkinguard.security.protocol.S1CaseWire.ACCEPT ->
                                        "接手成功：对方设备已签名确认（演练）"
                                    else -> "未获接手：可能已由其他帮助者接手"
                                }
                            }
                        }) { Text("申请接手 · 等待对方设备确认") }
                    }
                }
            }
        }
        HorizontalDivider()
        OutlinedButton(onClick = { showExtra = !showExtra }) {
            Text(if (showExtra) "收起其他设置" else "历史保留与手动联系人设置")
        }
        if (showExtra) {
        Text("本地加密历史", style = MaterialTheme.typography.titleLarge)
        Text("AES-GCM 本机加密保存；只自动清理超过保留期且已经结案的记录。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (days in listOf(7, 30, 90)) {
                FilterChip(selected = snapshot.history.retentionDays == days,
                    onClick = { manager.setRetention(days); refresh() }, label = { Text("$days 天") })
            }
        }
        HorizontalDivider()
        Text("没有安装 App 的联系人（手动联系）", style = MaterialTheme.typography.titleLarge)
        Text("这里只打开系统拨号或短信编辑器，由你决定是否拨打/发送。短信不自动写入位置或健康信息。")
        snapshot.history.contacts.forEach { contact ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${contact.name} · ${contact.phone}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DIAL,
                            Uri.parse("tel:${contact.phone}")))
                    }) { Text("手动拨号") }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO,
                            Uri.parse("smsto:${contact.phone}")).putExtra("sms_body",
                            "我想请你联系我。这是一条由我手动编辑的消息。"))
                    }) { Text("编辑短信") }
                    TextButton(onClick = { manager.removeContact(contact.phone); refresh() }) {
                        Text("移除")
                    }
                }
            }
        }
        OutlinedTextField(contactName, { contactName = it.take(40) },
            label = { Text("联系人名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(contactNumber, { contactNumber = it.take(25) },
            label = { Text("联系电话") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(onClick = {
            result = runCatching {
                manager.addContact(contactName, contactNumber)
                contactName = ""; contactNumber = ""
                refresh()
                "联系人已安全存入本机"
            }.getOrElse { "保存失败：${it.message}" }
        }) { Text("保存联系人") }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL,
            Uri.parse("tel:120"))) }) { Text("紧急电话 120（仅打开系统拨号，由本人决定拨打）") }
        }
    }

    if (confirmSos) AlertDialog(onDismissRequest = { confirmSos = false },
        title = { Text("确认开始测试 SOS？") },
        text = { Text("仅发送已授权手机间的局域网测试报警。不是真实求救，不会自动发短信或打电话。") },
        confirmButton = { TextButton(onClick = {
            confirmSos = false
            runTask("正在创建测试SOS") {
                val event = manager.createOwnSos()
                runCatching { S1TestNotifier.show(context,
                    "已创建本机演练SOS，准备发送给已绑定帮助者") }
                val failures = manager.deliverPending()
                if (failures.isEmpty()) "测试SOS ${event.take(8)} 已建立；查看设备回执确认送达"
                else "测试SOS已保存在本机，部分送达待确认：${failures.take(2).joinToString()}"
            }
        }) { Text("确认演练") } },
        dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("取消") } })

    confirmingClose?.let { id ->
        AlertDialog(onDismissRequest = { confirmingClose = null },
            title = { Text("确认结束本次演练") },
            text = { Text("结束由本人主动决定；如果对方离线，远端记录可能稍后才能同步。") },
            confirmButton = { TextButton(onClick = {
                confirmingClose = null
                runTask("结束演练") {
                    val errors = manager.closeOwn(id)
                    if (errors.isEmpty()) "本机已结案，在线帮助者已确认同步"
                    else "本机已结案，但远端同步状态未知：${errors.take(2).joinToString()}"
                }
            }) { Text("确认结束") } },
            dismissButton = { TextButton(onClick = { confirmingClose = null }) { Text("返回") } })
    }
}

private fun statusName(status: ExerciseStatus) = when (status) {
    ExerciseStatus.OPEN -> "等待接手"
    ExerciseStatus.CLAIM_PENDING -> "申请确认中"
    ExerciseStatus.ACCEPTED -> "已确认接手"
    ExerciseStatus.CLOSED -> "已结案"
    ExerciseStatus.EXPIRED -> "超时归档"
}

private fun timeText(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))

internal fun pairingPhaseName(phase: PairingPhase): String = when (phase) {
    PairingPhase.WAIT_RESPONSE -> "邀请已发送，等待救助者回应"
    PairingPhase.REVIEW_INVITE -> "收到邀请，等待本人同意"
    PairingPhase.WAIT_GRANT -> "已回应邀请，等待对方核对安全码"
    PairingPhase.REVIEW_RESPONSE -> "收到回应，等待本人核对安全码"
    PairingPhase.WAIT_ACK -> "已授权，等待对方最后确认"
    PairingPhase.REVIEW_GRANT -> "收到授权，等待本人核对安全码"
    PairingPhase.ACK_TO_SHOW -> "已发送最后签名确认，等待对方显示完成"
    PairingPhase.COMPLETE -> "安全绑定已完成"
}
