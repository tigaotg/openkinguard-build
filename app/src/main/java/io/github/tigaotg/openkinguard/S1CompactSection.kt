package io.github.tigaotg.openkinguard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.provider.DocumentsContract
import android.widget.Toast
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tigaotg.openkinguard.core.engine.CommandFailureExplainer
import io.github.tigaotg.openkinguard.core.engine.CommandFailureExplanation
import io.github.tigaotg.openkinguard.core.engine.FriendlyElapsedTime
import io.github.tigaotg.openkinguard.core.engine.HomeDevicePolicy
import io.github.tigaotg.openkinguard.core.engine.HomeRolePresence
import io.github.tigaotg.openkinguard.security.protocol.CurrentEventWindow
import io.github.tigaotg.openkinguard.security.protocol.DeviceActionPolicy
import io.github.tigaotg.openkinguard.security.protocol.EventSlot
import io.github.tigaotg.openkinguard.security.protocol.LocationMapPolicy
import io.github.tigaotg.openkinguard.security.protocol.PeerPresence
import io.github.tigaotg.openkinguard.security.protocol.PeerPresenceLevel
import io.github.tigaotg.openkinguard.security.protocol.S3LocationWire
import io.github.tigaotg.openkinguard.security.protocol.SafetyZonePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.DateFormat
import java.util.Date

/** Compact Chinese UI. One manager is shared with the optional connected-device service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S1CompactSection(themes: GuardThemeRepository, widgetAction: String? = null,
    onWidgetActionConsumed: () -> Unit = {}, incomingRemoteInvite: String? = null,
    onRemoteInviteConsumed: () -> Unit = {}, incomingMedicalReminder: Boolean = false,
    onMedicalReminderConsumed: () -> Unit = {}, startupHealthRevision: Int = 0) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember { S1Runtime.manager(context.applicationContext) }
    val healthMonitor = remember { HealthBleMonitor(context.applicationContext) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var snapshot by remember { mutableStateOf(manager.snapshot()) }
    var assistSession by remember { mutableStateOf(manager.remoteAssistSession()) }
    var assistStartFeedback by remember { mutableStateOf("") }
    var assistTransferStatus by remember {
        mutableStateOf(RemoteAssistProjectionService.currentTransferStatus())
    }
    var showAssistControllerPanel by remember { mutableStateOf(false) }
    var showAssistDiagnostics by remember { mutableStateOf(false) }
    var assistDiagnosticText by remember { mutableStateOf("") }
    val sharePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        val permission = result.data
        val current = manager.remoteAssistSession()
        if (result.resultCode == Activity.RESULT_OK && permission != null &&
            current?.direction == RemoteAssistConsent.Direction.INCOMING &&
            current.phase == RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION) {
            val start = Intent(context, RemoteAssistProjectionService::class.java)
                .putExtra(RemoteAssistProjectionService.EXTRA_SESSION_ID, current.sessionId)
                .putExtra(RemoteAssistProjectionService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(RemoteAssistProjectionService.EXTRA_RESULT_DATA, permission)
            assistStartFeedback = runCatching {
                context.startForegroundService(start)
                "系统已授权，准备发送屏幕画面；可从通知栏随时停止"
            }.getOrElse { "开启共享失败：${it.message}" }
        } else {
            assistStartFeedback = "未授予屏幕共享权限，协助没有开始"
        }
    }
    var page by remember { mutableIntStateOf(0) }
    val pageHistory = remember { mutableStateListOf<Int>() }
    var previousBackTap by remember { mutableLongStateOf(0L) }
    var settingsSectionName by rememberSaveable { mutableStateOf(SettingsDestination.HOME.name) }
    val settingsSection = SettingsDestination.entries.firstOrNull {
        it.name == settingsSectionName
    } ?: SettingsDestination.HOME
    var settingsFilter by rememberSaveable { mutableStateOf("") }
    var expandedSettingsGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var showPairingNickname by rememberSaveable { mutableStateOf(false) }
    var showPairingRelations by rememberSaveable { mutableStateOf(false) }
    var showRemotePairingSetup by rememberSaveable { mutableStateOf(false) }
    var showFamilyRelayPairing by rememberSaveable { mutableStateOf(false) }
    var recordView by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var lanError by remember { mutableStateOf("") }
    val actionFeedback = remember { mutableStateMapOf<String, String>() }
    val deviceFeedback = remember { mutableStateMapOf<String, String>() }
    var sosFeedback by remember { mutableStateOf("") }
    var checkinFeedback by remember { mutableStateOf("") }
    var publicP2pFeedback by remember { mutableStateOf("") }
    var publicNodeFeedback by remember { mutableStateOf("") }
    var publicReport by remember { mutableStateOf("") }
    var showPublicReport by remember { mutableStateOf(false) }
    var showPublicAddresses by remember { mutableStateOf(false) }
    var publicReportFeedback by remember { mutableStateOf("") }
    var privateConfigFeedback by remember { mutableStateOf("") }
    var privateJoinFeedback by remember { mutableStateOf("") }
    var noticeFeedback by remember { mutableStateOf("") }
    var checkinNoticeFeedback by remember { mutableStateOf("") }
    var backgroundFeedback by remember { mutableStateOf("") }
    var noticePermissionFeedback by remember { mutableStateOf("") }
    var locationPermissionFeedback by remember { mutableStateOf("") }
    var cameraPermissionFeedback by remember { mutableStateOf("") }
    var bluetoothPermissionFeedback by remember { mutableStateOf("") }
    var mapFeedback by remember { mutableStateOf("") }
    var medicalFeedback by remember { mutableStateOf("") }
    var exportedFeedback by remember { mutableStateOf("") }
    var lossThresholdFeedback by remember { mutableStateOf("") }
    var findPortalFeedback by remember { mutableStateOf("") }
    var showRelayManual by rememberSaveable { mutableStateOf(false) }
    var editingRelayRemark by rememberSaveable { mutableStateOf<String?>(null) }
    var relayRemarkDraft by rememberSaveable { mutableStateOf("") }
    var relayRemarkFeedback by remember { mutableStateOf("") }
    val expandedHomeLocations = remember { mutableStateMapOf<String, Boolean>() }
    val expandedHomeCapabilities = remember { mutableStateMapOf<String, Boolean>() }
    val expandedHomeNetworkDetails = remember { mutableStateMapOf<String, Boolean>() }
    val expandedLocationDetails = remember { mutableStateMapOf<String, Boolean>() }
    var pendingLocationAuthorization by remember { mutableStateOf<SavedPeer?>(null) }
    var inspectingRemoteAssistPermission by remember { mutableStateOf<SavedPeer?>(null) }
    var showPrivateRelayDetails by rememberSaveable { mutableStateOf(false) }
    var confirmSos by remember { mutableStateOf(false) }
    var confirmCheckin by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf<String?>(null) }
    var exportWarning by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showOfflineDevices by rememberSaveable { mutableStateOf(false) }
    var remoteInviteDraft by rememberSaveable { mutableStateOf("") }
    var remoteInviteFeedback by remember { mutableStateOf("") }
    var showPublicNodeDiagnostics by remember { mutableStateOf(false) }
    var readinessReport by remember { mutableStateOf("") }
    var batteryReport by remember { mutableStateOf("") }
    var requestingDevice by remember { mutableStateOf<String?>(null) }
    val lastLocationRequestAt = remember { mutableStateMapOf<String, Long>() }
    val pageScroll = rememberScrollState()
    var editingPeer by remember { mutableStateOf<String?>(null) }
    var peerDraft by remember { mutableStateOf("") }
    var ownDraft by remember { mutableStateOf(manager.ownNickname()) }
    var relayDraft by remember { mutableStateOf(manager.snapshot().history.relayEndpoint) }
    var relayJoinInvite by remember { mutableStateOf<RelayJoinInvite?>(null) }
    var relayJoinSocket by remember { mutableStateOf<RelayEnrollmentHandle?>(null) }
    var relayJoinAttempt by remember { mutableIntStateOf(0) }
    var relayJoinInProgress by remember { mutableStateOf(false) }
    var nostrDraft by remember { mutableStateOf(manager.snapshot().history.nostrRelayEndpoints) }
    var contactName by remember { mutableStateOf("") }
    var contactNumber by remember { mutableStateOf("") }
    var identityInfo by remember { mutableStateOf("") }
    var backgroundListening by remember { mutableStateOf(S1Runtime.isBackgroundListening()) }
    var backgroundReadiness by remember { mutableStateOf<BackgroundReadiness?>(null) }
    var startupAuditRevision by remember { mutableIntStateOf(0) }
    var hideStartupWarning by remember { mutableStateOf(false) }
    var liveLocationRelation by remember { mutableStateOf<String?>(null) }
    var liveLocationStatus by remember { mutableStateOf("") }
    var forgetLocationConfirm by remember { mutableStateOf(false) }
    var showFindPortals by remember { mutableStateOf(false) }
    var selectedFindPortal by remember { mutableStateOf<OfficialDeviceFindPortal?>(null) }
    var portalPickerFingerprint by remember { mutableStateOf<String?>(null) }
    var mapPickerExpanded by remember { mutableStateOf(false) }
    var mapAppsRevision by remember { mutableIntStateOf(0) }
    var pendingHistoricalMapFix by remember { mutableStateOf<ReceivedLocationFix?>(null) }
    // null = existing map chooser; false = Google; true = preferred domestic map.
    var pendingHistoricalMapDomestic by remember { mutableStateOf<Boolean?>(null) }
    var mapChoiceFix by remember { mutableStateOf<ReceivedLocationFix?>(null) }
    var zoneRadiusPickerFingerprint by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var keepLegacyRelation by remember { mutableStateOf<SavedPeer?>(null) }
    var medicalFolderOpen by remember { mutableStateOf(false) }
    var medicalFolderName by remember { mutableStateOf("") }
    var medicalFiles by remember { mutableStateOf<List<PersonalMedicalEntry>>(emptyList()) }
    var medicalLoadError by remember { mutableStateOf("") }
    var medicalFolderLoading by remember { mutableStateOf(false) }
    var medicalReload by remember { mutableIntStateOf(0) }
    var removeMedicalFolderConfirm by remember { mutableStateOf(false) }
    val medicalPath = remember { mutableStateListOf<Pair<String, String>>() }

    // All sections start at their own header; reusing the same ScrollState no longer
    // strands an older phone halfway down a newly opened settings screen.
    LaunchedEffect(page, settingsSectionName) { pageScroll.scrollTo(0) }

    // Launch the separate fullscreen controller once per new outgoing session.
    // Closing it does not revoke authorization; it can be reopened manually.
    LaunchedEffect(assistSession?.sessionId) {
        showAssistControllerPanel =
            assistSession?.direction == RemoteAssistConsent.Direction.OUTGOING
    }
    LaunchedEffect(showAssistControllerPanel, assistSession?.sessionId) {
        val session = assistSession?.takeIf {
            it.direction == RemoteAssistConsent.Direction.OUTGOING
        }
        if (showAssistControllerPanel && session != null) {
            showAssistControllerPanel = false
            runCatching {
                context.startActivity(Intent(context, RemoteAssistControllerActivity::class.java)
                    .putExtra(RemoteAssistControllerActivity.EXTRA_SESSION_ID, session.sessionId))
            }.onFailure {
                Toast.makeText(context, "无法打开远程控制：${it.javaClass.simpleName}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    // One lightweight in-memory refresh per second while assisting; never poll
    // the encrypted SOS/history snapshot for every JPEG frame on older phones.
    LaunchedEffect(manager, assistSession?.sessionId) {
        while (true) {
            val current = manager.remoteAssistSession()
            assistSession = current
            assistTransferStatus = RemoteAssistProjectionService.currentTransferStatus()
            if (current == null) break
            delay(500)
        }
    }

    fun navigateTo(destination: Int) {
        if (destination == 0) {
            pageHistory.clear()
            page = 0
        } else if (destination != page) {
            if (pageHistory.size >= 24) pageHistory.removeAt(0)
            pageHistory.add(page)
            page = destination
        }
        if (destination == 3) settingsSectionName = SettingsDestination.HOME.name
        previousBackTap = 0L
    }

    fun navigateBack() {
        if (page == 3 && settingsSection != SettingsDestination.HOME) {
            settingsSectionName = SettingsDestination.HOME.name
        } else if (pageHistory.isNotEmpty()) {
            page = pageHistory.removeAt(pageHistory.lastIndex)
            if (page == 3) settingsSectionName = SettingsDestination.HOME.name
        } else if (page != 0) {
            page = 0
        } else {
            // Rapid Back presses must not silently close the socket owner while
            // the other person still believes an assistance session is alive.
            if (assistSession != null) {
                if (assistSession?.direction == RemoteAssistConsent.Direction.OUTGOING) {
                    showAssistControllerPanel = true
                }
                previousBackTap = 0L
                Toast.makeText(context, "远程协助尚未结束，请先点击“结束协助”再退出",
                    Toast.LENGTH_SHORT).show()
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - previousBackTap < 2_000L) (context as? Activity)?.finish()
            else {
                previousBackTap = now
                Toast.makeText(context, "再按一次返回键退出 OpenKinGuard", Toast.LENGTH_SHORT).show()
            }
            return
        }
        previousBackTap = 0L
    }

    fun returnToSettings() {
        if (pageHistory.lastOrNull() == 3) {
            pageHistory.removeAt(pageHistory.lastIndex)
            page = 3
            settingsSectionName = SettingsDestination.HOME.name
        } else navigateTo(3)
    }

    LaunchedEffect(incomingMedicalReminder) {
        if (incomingMedicalReminder) {
            navigateTo(2)
            onMedicalReminderConsumed()
        }
    }

    BackHandler {
        when {
            showAssistControllerPanel -> showAssistControllerPanel = false
            confirmSos -> confirmSos = false
            confirmCheckin -> confirmCheckin = false
            closing != null -> closing = null
            revoking != null -> revoking = null
            exportWarning != null -> exportWarning = null
            selectedFindPortal != null -> selectedFindPortal = null
            keepLegacyRelation != null -> keepLegacyRelation = null
            removeMedicalFolderConfirm -> removeMedicalFolderConfirm = false
            forgetLocationConfirm -> forgetLocationConfirm = false
            pendingHistoricalMapFix != null -> {
                pendingHistoricalMapFix = null
                pendingHistoricalMapDomestic = null
            }
            mapChoiceFix != null -> mapChoiceFix = null
            mapPickerExpanded -> mapPickerExpanded = false
            else -> navigateBack()
        }
    }

    LaunchedEffect(widgetAction) {
        when (widgetAction) {
            GuardWidgetPublisher.ACTION_SOS -> {
                navigateTo(0)
                confirmSos = true
                onWidgetActionConsumed()
            }
            GuardWidgetPublisher.ACTION_CHECKIN -> {
                navigateTo(0)
                confirmCheckin = true
                onWidgetActionConsumed()
            }
        }
    }
    LaunchedEffect(incomingRemoteInvite) {
        if (!incomingRemoteInvite.isNullOrBlank()) {
            remoteInviteDraft = incomingRemoteInvite
            navigateTo(1)
            remoteInviteFeedback = "收到远程邀请链接，请核对邀请来源，再点击接受。"
            onRemoteInviteConsumed()
        }
    }

    LaunchedEffect(page, settingsSectionName) {
        pageScroll.scrollTo(0)
        if (page != 1) liveLocationRelation = null
        if (page != 5) healthMonitor.stopScan()
        if (page != 3) settingsSectionName = SettingsDestination.HOME.name
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        noticePermissionFeedback = if (granted) "已允许通知，可发送一条本机测试通知" else
            "通知尚未允许，可在设置页重新授权"
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationPermissionFeedback = if (result.values.any { it })
            "系统已允许定位。是否让指定救助者查看，还需要你单独授权。" else
            "未获得定位权限；不会发送任何位置。你可以在系统应用权限中允许。"
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionFeedback = if (granted) "相机已允许，只在主动扫描二维码时使用。" else
            "未获得相机权限；扫码可能不可用，可稍后从系统权限页授权。"
    }
    val bluetoothPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        bluetoothPermissionFeedback = if (result.values.all { it })
            "附近设备权限已允许；只在你主动扫描穿戴设备时使用。" else
            "部分蓝牙权限未获得；穿戴设备扫描可能受限。"
    }
    val relayJoinScanner = rememberLauncherForActivityResult(ScanContract()) { scan ->
        if (scan.contents != null) {
            privateJoinFeedback = runCatching {
                relayJoinInvite = RelayJoinInvite.fromQr(scan.contents)
                "请核对路由器节点的公钥指纹后确认申请"
            }.getOrElse { "二维码无法用于家庭节点：${it.message ?: "格式错误"}" }
        }
    }
    val medicalFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            val prior = snapshot.history.medicalFolderTreeUri
            medicalFeedback = runCatching {
                context.contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
                manager.setMedicalFolderTreeUri(tree.toString())
                if (prior.isNotBlank() && prior != tree.toString()) runCatching {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(prior),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                medicalPath.clear()
                medicalFolderOpen = true
                medicalReload++
                "已关联病历文件夹；仅本机打开，不上传其中的文件"
            }.getOrElse { "关联文件夹失败：${CommandFailureExplainer.explain(it).message}" }
            snapshot = manager.snapshot()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            val prefs = context.getSharedPreferences("okg.s1.ui", 0)
            if (!prefs.getBoolean("notification_prompt_requested_v1", false)) {
                prefs.edit().putBoolean("notification_prompt_requested_v1", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val jsonExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportedFeedback = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(manager.exportHistoryJson().toByteArray(Charsets.UTF_8))
            } ?: error("无法打开选定文件")
            "JSON 历史已导出；导出文件未加密，请妥善保管"
        }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
    }
    val csvExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportedFeedback = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(manager.exportHistoryCsv().toByteArray(Charsets.UTF_8))
            } ?: error("无法打开选定文件")
            "CSV 历史已导出；导出文件未加密，请妥善保管"
        }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
    }
    val gpxExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) exportedFeedback = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(manager.exportReceivedLocationsGpx().toByteArray(Charsets.UTF_8))
            } ?: error("无法写入选定文件")
            "GPX 定位轨迹已导出（明文文件，请注意隐私）"
        }.getOrElse { "GPX 导出失败：${it.message ?: it.javaClass.simpleName}" }
    }
    val readinessExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportedFeedback = runCatching {
            require(readinessReport.isNotBlank()) { "请先执行就绪自检" }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(readinessReport.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选文件")
            "自检报告已保存；报告不含精确坐标、私钥或联系人号码"
        }.getOrElse { "保存自检报告失败：${it.message ?: it.javaClass.simpleName}" }
    }
    val publicReportExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) publicReportFeedback = runCatching {
            require(publicReport.isNotBlank()) { "请先生成公共节点诊断报告" }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(publicReport.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选文件")
            "诊断报告已保存。分享前请检查是否包含了公网 IP。"
        }.getOrElse { "诊断保存失败：${it.message ?: it.javaClass.simpleName}" }
    }

    fun refresh() { handler.post {
        snapshot = manager.snapshot()
        assistSession = manager.remoteAssistSession()
        assistTransferStatus = RemoteAssistProjectionService.currentTransferStatus()
        GuardWidgetPublisher.publishFromManager(context, manager)
    } }
    if (relayJoinInvite != null) {
        val invite = requireNotNull(relayJoinInvite)
        AlertDialog(onDismissRequest = { relayJoinInvite = null },
            title = { Text("确认加入这台家庭路由器？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请对照路由器管理页面，核对以下节点公钥指纹。二维码本身不表示已获授权。")
                    Text(invite.url.substringAfter("#spki="),
                        style = MaterialTheme.typography.bodySmall)
                    Text("核对一致后，手机会提交申请；仍需路由器主人批准。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    relayJoinInvite = null
                    relayJoinAttempt += 1
                    val attempt = relayJoinAttempt
                    relayJoinSocket?.cancel()
                    relayJoinSocket = null
                    relayJoinInProgress = true
                    privateJoinFeedback = "正在连接家庭路由器并核对设备身份…"
                    val result = runCatching {
                        S2RelayEnrollment.begin(invite, AndroidDeviceKeyStore(),
                            nickname = manager.ownNickname(),
                            model = listOf(Build.MANUFACTURER, Build.MODEL)
                                .filter { it.isNotBlank() }.distinct().joinToString(" "),
                            progress = { status -> handler.post {
                                // Previous requests, including after an explicit Cancel,
                                // must never overwrite the current approval result.
                                if (!RelayJoinUiPolicy.shouldApply(relayJoinAttempt, attempt)) return@post
                                privateJoinFeedback = status
                                if (RelayJoinUiPolicy.terminal(status)) {
                                    relayJoinInProgress = false
                                    relayJoinSocket = null
                                }
                                refresh()
                            } },
                            approved = {
                                // Read the actual saved addresses, not an older UI draft
                                // captured before router approval arrived.
                                val previous = manager.snapshot().history.relayEndpoint.lines()
                                    .map { it.trim() }.filter { it.isNotBlank() }
                                val combined = if (invite.url in previous) previous
                                    else previous + invite.url
                                require(combined.size <= 3) {
                                    "最多3个私人中继；请先删除一个旧节点再核验本机授权"
                                }
                                val updated = combined.joinToString("\n")
                                manager.configureRelay(updated, true)
                                handler.post { relayDraft = updated; refresh() }
                            })
                    }
                    relayJoinSocket = result.getOrNull()
                    result.onFailure { error ->
                        relayJoinInProgress = false
                        privateJoinFeedback = "无法开始核验家庭节点：" +
                            (error.message?.take(90) ?: error.javaClass.simpleName)
                    }
                }) { Text("指纹一致，提交申请") }
            }, dismissButton = { TextButton(onClick = { relayJoinInvite = null }) {
                Text("取消")
            } })
    }
    fun launchMapLocation(fix: ReceivedLocationFix, domestic: Boolean) {
        mapChoiceFix = null
        mapFeedback = runCatching {
            if (domestic) ThirdPartyMapLauncher.openDomestic(context, fix,
                snapshot.history.preferredMapHandler)
            else ThirdPartyMapLauncher.openGoogle(context, fix)
        }.getOrElse { error ->
            val reason = when (error) {
                is android.content.ActivityNotFoundException ->
                    "没有可打开此地图链接的应用，请安装地图软件或网页浏览器。"
                else -> "打开地图失败：${error.message ?: error.javaClass.simpleName}"
            }
            manager.recordCommandResult("在第三方地图查看定位", "失败", "MAP_OPEN_FAILED", reason)
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            reason
        }
    }
    fun openMapLocation(fix: ReceivedLocationFix, domestic: Boolean? = null,
                        confirmedHistorical: Boolean = false) {
        if (!confirmedHistorical && LocationMapPolicy.isHistorical(fix.capturedAtMillis,
                System.currentTimeMillis())) {
            pendingHistoricalMapFix = fix
            pendingHistoricalMapDomestic = domestic
            return
        }
        if (domestic == null) mapChoiceFix = fix
        else launchMapLocation(fix, domestic)
    }
    fun runTask(label: String, inlineResult: ((String) -> Unit)? = null,
                friendlyFailure: ((CommandFailureExplanation) -> String)? = null,
                onFailureDetail: ((String) -> Unit)? = null,
                action: () -> String) {
        if (busy) return
        busy = true
        if (inlineResult != null) inlineResult("$label…")
        else actionFeedback[label] = "$label…"
        Thread({
            val output = runCatching(action)
            var technicalFailure = ""
            val resultMessage = output.fold(onSuccess = { result ->
                val waiting = listOf("等待确认", "未确认", "未获对方确认", "仍有待", "尚有待", "仍待确认")
                    .any { result.contains(it) }
                runCatching { manager.recordCommandResult(label,
                    if (waiting) "待确认" else "完成",
                    if (waiting) "PENDING_ACK" else "OK",
                    if (waiting) "已执行本机操作，但远端回执或后续处理尚未确认" else
                        "本机操作已完成；涉及远端操作时仍以签名回执为准") }
                result
            }, onFailure = { error ->
                val explanation = CommandFailureExplainer.explain(error)
                technicalFailure = "${explanation.code}：${explanation.message}"
                runCatching { manager.recordCommandResult(label, "失败",
                    explanation.code, explanation.message) }
                friendlyFailure?.invoke(explanation) ?: "$label 失败：${explanation.message}"
            })
            handler.post {
                onFailureDetail?.invoke(technicalFailure)
                if (inlineResult != null) inlineResult(resultMessage)
                else actionFeedback[label] = resultMessage
                busy = false
                requestingDevice = null
                refreshing = false
                snapshot = manager.snapshot()
                assistSession = manager.remoteAssistSession()
                assistTransferStatus = RemoteAssistProjectionService.currentTransferStatus()
            }
        }, "okg-s1-task").start()
    }

    DisposableEffect(manager, lifecycleOwner) {
        val updated = { refresh() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> runCatching { S1Runtime.attachUi(context, updated) }
                    .onSuccess { lanError = "" }.onFailure {
                    lanError = "局域网未能启动：${it.message}"
                }
                Lifecycle.Event.ON_STOP -> {
                    liveLocationRelation = null
                    healthMonitor.close()
                    S1Runtime.detachUi()
                    if (!S1Runtime.isBackgroundListening() && !S1Runtime.isUiVisible())
                        GuardWidgetPublisher.markInactive(context)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching { S1Runtime.attachUi(context, updated) }
                .onSuccess { lanError = "" }.onFailure {
                lanError = "局域网未能启动：${it.message}"
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            healthMonitor.close()
            S1Runtime.detachUi()
            if (!S1Runtime.isBackgroundListening() && !S1Runtime.isUiVisible())
                        GuardWidgetPublisher.markInactive(context)
        }
    }
    LaunchedEffect(manager) {
        while (true) {
            if (!S1Runtime.isBackgroundListening()) {
                manager.probeAsync()
                withContext(Dispatchers.IO) {
                    runCatching { manager.expireOldIncoming() }
                    runCatching { manager.deliverPending() }
                }
            }
            snapshot = manager.snapshot()
            assistSession = manager.remoteAssistSession()
            assistTransferStatus = RemoteAssistProjectionService.currentTransferStatus()
            GuardWidgetPublisher.publishFromManager(context, manager)
            backgroundListening = S1Runtime.isBackgroundListening()
            // Signed events also push updates immediately. Avoid excessive Compose/JSON work
            // on old Android 10 hardware while leaving request feedback responsive.
            delay(if (requestingDevice != null || liveLocationRelation != null) 5_000 else 12_000)
        }
    }
    LaunchedEffect(startupHealthRevision, startupAuditRevision) {
        if (startupHealthRevision == 0 && startupAuditRevision == 0) return@LaunchedEffect
        hideStartupWarning = false
        var status = withContext(Dispatchers.IO) { BackgroundStartupCheck.inspect(context) }
        backgroundReadiness = status
        if (BackgroundMonitorPolicy.mayAutoRecover(status.consent,
                status.phase == BackgroundPhase.RUNNING)) {
            backgroundReadiness = status.copy(phase = BackgroundPhase.RECOVERING)
            withContext(Dispatchers.IO) { S1LanService.requestAutoRecovery(context) }
            // startForegroundService() only requests startup; wait for a real scheduler tick.
            repeat(16) {
                delay(500)
                status = withContext(Dispatchers.IO) { BackgroundStartupCheck.inspect(context) }
                backgroundReadiness = status
                if (status.phase == BackgroundPhase.RUNNING ||
                    (status.phase != BackgroundPhase.RECOVERING &&
                        S1LanService.lastStartupFailure() != null)) return@LaunchedEffect
            }
            backgroundReadiness = withContext(Dispatchers.IO) {
                BackgroundStartupCheck.inspect(context)
            }
        }
    }
    // Android starts/stops foreground services asynchronously. Show the actual runtime
    // state rather than claiming success immediately after startForegroundService().
    LaunchedEffect(backgroundFeedback) {
        if (backgroundFeedback.startsWith("正在开启持续监听")) {
            repeat(16) {
                delay(300)
                backgroundListening = S1Runtime.isBackgroundListening()
                if (backgroundListening) {
                    backgroundFeedback = "持续监听已开启；可在通知栏停止"
                    return@LaunchedEffect
                }
                val error = S1LanService.lastStartupFailure()
                if (!error.isNullOrBlank()) {
                    backgroundFeedback = error
                    return@LaunchedEffect
                }
            }
            backgroundFeedback = "尚未确认启动成功；请检查通知栏和系统后台限制"
        } else if (backgroundFeedback.startsWith("正在停止持续监听")) {
            repeat(16) {
                delay(300)
                backgroundListening = S1Runtime.isBackgroundListening()
                if (!backgroundListening) {
                    backgroundFeedback = "持续监听已停止；打开应用时仍可通信"
                    return@LaunchedEffect
                }
            }
            backgroundFeedback = "系统尚未确认停止；请检查通知栏"
        }
    }
    val medicalTree = snapshot.history.medicalFolderTreeUri
    LaunchedEffect(medicalFolderOpen, medicalTree, medicalPath.lastOrNull()?.first, medicalReload) {
        if (medicalFolderOpen && medicalTree.isNotBlank()) {
            medicalFolderLoading = true
            medicalLoadError = ""
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(medicalTree)
                    val id = medicalPath.lastOrNull()?.first ?: PersonalMedicalFolder.rootId(uri)
                    PersonalMedicalFolder.title(context, uri) to
                        PersonalMedicalFolder.children(context, uri, id)
                }
            }
            result.fold(onSuccess = { (title, files) ->
                medicalFolderName = title
                medicalFiles = files
            }, onFailure = {
                medicalFiles = emptyList()
                medicalLoadError = CommandFailureExplainer.explain(it).message
            })
            medicalFolderLoading = false
        }
    }
    LaunchedEffect(liveLocationRelation, page) {
        val relationId = liveLocationRelation
        if (page == 1 && relationId != null) {
            var previousErrorCode: String? = null
            while (S1Runtime.isUiVisible() && liveLocationRelation == relationId) {
                val response = withContext(Dispatchers.IO) {
                    runCatching { manager.requestRemoteLocation(relationId) }
                }
                liveLocationStatus = response.fold(
                    onSuccess = { fix ->
                        if (previousErrorCode != null) {
                            withContext(Dispatchers.IO) {
                                manager.recordCommandResult("连续查看定位", "完成", "RECOVERED",
                                    "此前的通信或定位异常已恢复，收到新的验签坐标")
                            }
                            previousErrorCode = null
                        }
                        "最新验签定位：${formatLocation(fix)}"
                    },
                    onFailure = { error ->
                        val failure = CommandFailureExplainer.explain(error)
                        if (previousErrorCode != failure.code) {
                            withContext(Dispatchers.IO) {
                                manager.recordCommandResult("连续查看定位", "失败",
                                    failure.code, failure.message)
                            }
                            previousErrorCode = failure.code
                        }
                        "本次定位失败：${failure.message}"
                    })
                snapshot = manager.snapshot()
                delay(10_000)
            }
        }
    }

    val relations = manager.relations()
    val homeNow = System.currentTimeMillis()
    fun roleOnline(peer: SavedPeer): Boolean =
        PeerPresence.level(snapshot.history.lastVerifiedAt[peer.relationId], homeNow,
            snapshot.history.lossWarningMinutes,
            maxOf(snapshot.history.lossWarningMinutes + 1,
                snapshot.history.lossCriticalMinutes)) ==
            PeerPresenceLevel.RECENT && (peer.relationId in snapshot.verifiedRelations ||
            peer.relationId in snapshot.relayVerifiedRelations ||
            peer.relationId in snapshot.directVerifiedRelations)
    val homeDevices = HomeDevicePolicy.collapse(relations.map { peer ->
        HomeRolePresence(peer.peerFingerprint, peer.relationId, peer.role.name, roleOnline(peer))
    })
    val onlineHomeDevices = homeDevices.filter { it.online }
    val offlineHomeDevices = homeDevices.filterNot { it.online }
    fun peerName(relationId: String, fingerprint: String): String =
        snapshot.history.peerNicknames[relationId].orEmpty().ifBlank {
            snapshot.history.verifiedPeerNicknames[relationId].orEmpty()
        }.ifBlank {
            snapshot.history.verifiedDeviceNicknames[fingerprint].orEmpty()
        }.ifBlank {
            "设备 ${fingerprint.take(8)}"
        }
    pendingLocationAuthorization?.let { peer ->
        AlertDialog(onDismissRequest = { pendingLocationAuthorization = null },
            title = { Text("允许这位家人查看我的位置？") },
            text = { Text("${peerName(peer.relationId, peer.peerFingerprint)} 将可以在你打开 OpenKinGuard 时主动请求一次定位。" +
                "这不是永久后台定位，你可以随时在“连接家人”中撤销授权。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    pendingLocationAuthorization = null
                    runTask("授权家人查看位置") {
                        manager.allowLocationToHelper(peer.relationId, true)
                        "已授权该家人在本机应用打开时请求单次定位"
                    }
                }) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLocationAuthorization = null }) { Text("取消") }
            })
    }
    inspectingRemoteAssistPermission?.let { peer ->
        val permitted = manager.remoteAssistRequestsAllowed(peer.relationId)
        val accessibilityReady = RemoteAssistGestureService.enabled()
        val active = manager.remoteAssistSession()?.let {
            it.relationId == peer.relationId &&
                it.direction == RemoteAssistConsent.Direction.INCOMING &&
                it.phase == RemoteAssistConsent.Phase.ACTIVE
        } == true
        AlertDialog(onDismissRequest = { inspectingRemoteAssistPermission = null },
            title = { Text("远程协助权限检查") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("家人：${peerName(peer.relationId, peer.peerFingerprint)}")
                    Text(if (permitted) "● 应用内权限：允许这位家人提出协助请求"
                        else "○ 应用内权限：已禁止这位家人提出协助请求")
                    Text(if (active) "● 本次屏幕共享：正在进行"
                        else "○ 本次屏幕共享：尚未开启；每次都需本人点击同意和安卓系统授权")
                    Text(if (accessibilityReady)
                        "● 远程操作：本机辅助功能已启用"
                        else "○ 远程操作：本机辅助功能未启用，可能只能看屏幕，无法点击操作")
                    Text("取消应用内权限不会关闭手机系统的辅助功能，但会拒绝这位家人以后发起的协助。",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    inspectingRemoteAssistPermission = null
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("辅助功能设置") }
            },
            dismissButton = {
                TextButton(onClick = { inspectingRemoteAssistPermission = null }) {
                    Text("关闭")
                }
            })
    }
    val activeCaseIds = CurrentEventWindow.currentIds(snapshot.history.cases.map { case ->
        EventSlot(case.eventId, case.subjectFingerprint, case.createdAt,
            case.supersededBy,
            case.status == ExerciseStatus.CLOSED || case.status == ExerciseStatus.EXPIRED)
    })
    val currentCases = snapshot.history.cases.filter { it.eventId in activeCaseIds }
        .sortedByDescending { it.createdAt }
    val archivedCases = snapshot.history.cases.filter { it.eventId !in activeCaseIds }
        .sortedByDescending { it.createdAt }
    val currentCheckinIds = CurrentEventWindow.currentIds(snapshot.history.checkins.map { checkin ->
        EventSlot(checkin.eventId, checkin.fingerprint, checkin.timestamp,
            checkin.supersededBy)
    })
    val currentCheckins = snapshot.history.checkins.filter { it.eventId in currentCheckinIds }
        .sortedByDescending { it.timestamp }
    val archivedCheckins = snapshot.history.checkins.filter { it.eventId !in currentCheckinIds }
        .sortedByDescending { it.timestamp }
    val unreadCheckins = currentCheckins.filter {
        !it.own && it.timestamp > snapshot.history.lastReadCheckinTimestamp
    }

    fun refreshByGesture() {
        if (busy) return
        refreshing = true
        runTask("刷新应用状态") {
            val failures = manager.refreshNow()
            if (failures.isEmpty())
                "本机状态已更新，已重新请求局域网认证；远端状态将随签名回复更新"
            else "已刷新本机状态，部分远端操作仍待确认：${failures.joinToString("；").take(320)}"
        }
    }

    assistSession?.takeIf { it.direction == RemoteAssistConsent.Direction.INCOMING &&
        it.phase == RemoteAssistConsent.Phase.WAITING_FOR_PERSON }?.let { invitation ->
        AlertDialog(onDismissRequest = { /* A deliberate choice is required. */ },
            title = { Text("家人请求远程协助") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${peerName(invitation.relationId, invitation.peerFingerprint)} 请求帮助操作您的手机。")
                    Text("只有您点击同意后才可以申请屏幕共享。之后 Android 将再次询问您；" +
                        "若您希望家人能够点击操作，还需要在系统辅助功能中手动开启本应用。" +
                        "任何时候都能结束协助。",
                        style = MaterialTheme.typography.bodySmall)
                    InlineActionFeedback(actionFeedback["远程协助确认"])
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    runTask("远程协助确认") { manager.answerRemoteAssist(true) }
                }) { Text("同意协助") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    runTask("远程协助确认") { manager.answerRemoteAssist(false) }
                }) { Text("拒绝") }
            })
    }

    assistSession?.takeIf { it.direction == RemoteAssistConsent.Direction.INCOMING &&
        it.phase == RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION }?.let { session ->
        AlertDialog(onDismissRequest = { /* Explicit stop or screen authorization required. */ },
            title = { Text("准备远程协助") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("您已同意 ${peerName(session.relationId, session.peerFingerprint)} 协助。" +
                        "点击“开始共享”后，安卓系统还会显示一次屏幕录制确认。")
                    Text(if (RemoteAssistGestureService.enabled())
                        "远程点击权限已开启。" else
                        "目前尚未开启辅助功能，家人只能查看画面，不能点击。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("开启远程点击的方法：\n" +
                        "① 点击下方按钮打开手机的‘无障碍／辅助功能’设置。\n" +
                        "② 找到‘已下载的应用’、‘已安装的服务’或类似入口。\n" +
                        "③ 选择‘OpenKinGuard 远程协助（手动开启）’，打开开关并确认系统提示。\n" +
                        "④ 返回本软件，确认显示‘远程点击权限已开启’，再点击‘开始共享屏幕’。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("① 打开无障碍设置，按照上述步骤开启") }
                    InlineActionFeedback(assistStartFeedback)
                }
            },
            confirmButton = { TextButton(onClick = {
                val projection = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                sharePermissionLauncher.launch(projection.createScreenCaptureIntent())
            }) { Text("开始共享屏幕") } },
            dismissButton = { TextButton(onClick = {
                runTask("结束远程协助") { manager.stopRemoteAssist() }
            }) { Text("取消协助") } })
    }

    // Fullscreen rendering is owned by RemoteAssistControllerActivity.
    // Keeping its renderer outside this frequently recomposed home/settings UI
    // avoids the old dialog's resize and SurfaceView recreation jitter.

    if (showAssistDiagnostics) {
        AlertDialog(onDismissRequest = { showAssistDiagnostics = false },
            title = { Text("远程画面卡顿诊断") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())) {
                    Text("这是实际采样数据，不是设置中的目标帧率。保持共享约15秒后刷新并复制。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(assistDiagnosticText,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                        "OpenKinGuard 远程卡顿诊断", assistDiagnosticText))
                    Toast.makeText(context, "诊断信息已复制，可粘贴发给我分析",
                        Toast.LENGTH_LONG).show()
                }) { Text("复制全部信息") }
            },
            dismissButton = {
                TextButton(onClick = {
                    assistDiagnosticText = manager.remoteAssistDiagnosticReport()
                }) { Text("刷新数据") }
                TextButton(onClick = { showAssistDiagnostics = false }) { Text("关闭") }
            })
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp) {
            val itemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant)
            NavigationBarItem(selected = page == 0, onClick = { navigateTo(0) },
                icon = { Text("♥", fontSize = 22.sp) }, label = { Text("求助") }, colors = itemColors)
            NavigationBarItem(selected = page == 2, onClick = { navigateTo(2) },
                icon = { Text("✚", fontSize = 22.sp) }, label = { Text("健康档案") }, colors = itemColors)
            NavigationBarItem(selected = page == 5, onClick = { navigateTo(5) },
                icon = { Text("◈", fontSize = 22.sp) }, label = { Text("穿戴") }, colors = itemColors)
            NavigationBarItem(selected = page == 3 || page == 4 || page == 1, onClick = {
                    navigateTo(3)
                },
                icon = { Text("⚙", fontSize = 22.sp) }, label = { Text("设置") }, colors = itemColors)
        }
    }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing && page == 0,
            onRefresh = { if (page == 0) refreshByGesture() },
            modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize()
            .verticalScroll(pageScroll).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            assistSession?.takeIf { it.direction == RemoteAssistConsent.Direction.INCOMING &&
                it.phase == RemoteAssistConsent.Phase.ACTIVE }?.let {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("远程协助进行中 · 您的屏幕正在共享",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error)
                        Text("仅此次家人协助有效；关闭应用、点击下方按钮，" +
                            "或点击通知栏“立即停止协助”都可以终止。")
                        Text("画面状态：$assistTransferStatus",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            assistDiagnosticText = manager.remoteAssistDiagnosticReport()
                            showAssistDiagnostics = true
                        }) { Text("查看 / 复制画面诊断") }
                        Button(onClick = { runTask("结束远程协助") {
                            manager.stopRemoteAssist()
                        } }) { Text("立即结束远程协助") }
                    }
                }
            }
            assistSession?.takeIf {
                it.direction == RemoteAssistConsent.Direction.OUTGOING
            }?.let { session ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("远程协助请求", style = MaterialTheme.typography.titleMedium)
                            Text(when (session.phase) {
                                RemoteAssistConsent.Phase.WAITING_FOR_REPLY -> "等待对方同意"
                                RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION ->
                                    "对方已同意，等待系统屏幕共享授权"
                                RemoteAssistConsent.Phase.ACTIVE -> "已连接，点击查看控制画面"
                                else -> "等待对方确认"
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { showAssistControllerPanel = true }) {
                            Text("查看协助")
                        }
                    }
                }
            }
            if (page == 0 && !hideStartupWarning) {
                backgroundReadiness?.takeIf { it.needsAttention &&
                    it.phase != BackgroundPhase.RECOVERING }?.let { check ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(if (check.phase == BackgroundPhase.RUNNING)
                                "有应用权限需要检查" else "后台监护未正常运行",
                                style = MaterialTheme.typography.titleMedium)
                            Text(when (check.phase) {
                                BackgroundPhase.USER_DISABLED, BackgroundPhase.NOT_ENABLED ->
                                    "后台监听尚未获您同意或已由您关闭。离开应用后，家人消息接收和在线探测可能中断。"
                                BackgroundPhase.RUNNING ->
                                    "监听服务已启动，但部分系统权限或设置可能影响提醒和监护功能。"
                                else -> "OpenKinGuard 未能自动恢复后台监听。进入后台后，" +
                                    "家人消息接收和在线探测可能中断，请检查系统后台运行设置。"
                            }, style = MaterialTheme.typography.bodySmall)
                            check.advice().take(4).forEach { issue ->
                                Text("• $issue", style = MaterialTheme.typography.bodySmall)
                            }
                            if (check.phase != BackgroundPhase.RUNNING) {
                                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                                    backgroundFeedback = S1LanService.requestStart(context)
                                    startupAuditRevision++
                                }) { Text(if (check.consent == BackgroundConsent.ENABLED)
                                    "重试自动启动" else "立即开启") }
                            }
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                BackgroundStartupCheck.openBackgroundSettings(context)
                            }) { Text("检查后台运行设置") }
                            TextButton(onClick = { hideStartupWarning = true }) {
                                Text("稍后处理")
                            }
                        }
                    }
                }
            }
            if (page == 0) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("守护", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        Text(manager.ownNickname(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("${onlineHomeDevices.size} 台在线",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("紧急操作", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Button(modifier = Modifier.fillMaxWidth().height(60.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError),
                            onClick = { confirmSos = true }) {
                            Text("发起演练 SOS", style = MaterialTheme.typography.titleMedium)
                        }
                        InlineActionFeedback(sosFeedback)
                        if (sosFeedback.contains("失败") || sosFeedback.contains("待确认") ||
                            sosFeedback.contains("尚未") || sosFeedback.contains("等待")) {
                            FeatureHelp("确认已绑定救助者、双方网络可用且应用正在监听；" +
                                "查看最新签名回执。超时不代表对方拒绝，也不能当作已送达。",
                                label = "查看未送达的排查建议")
                        }
                        OutlinedButton(modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = !busy, onClick = {
                                runTask("正在发送报平安", inlineResult = { checkinFeedback = it }) {
                                    val id = manager.createOwnCheckin()
                                    val errors = manager.deliverPending()
                                    if (errors.isEmpty())
                                        "报平安 ${id.take(8)} 已创建；请在记录页核对对方签名回执"
                                    else "报平安已保存在本机，等待确认送达：${errors.joinToString("；").take(320)}"
                                }
                            }) { Text("我平安 · 通知救助者") }
                        InlineActionFeedback(checkinFeedback)
                        FeatureHelp("SOS 和报平安仅发送给已绑定的家人，必须查看签名回执。" +
                            "演练 SOS 不会自动拨打电话或发送短信；" +
                            "真实危险请立即使用当地急救渠道。", label = "操作说明与安全提示")
                    }
                }
            }
            if (page == 0 && unreadCheckins.isNotEmpty()) {
                val latest = unreadCheckins.first()
                ElevatedCard(colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("收到 ${unreadCheckins.size} 条新报平安",
                            style = MaterialTheme.typography.titleLarge)
                        Text("${peerName(latest.relationId, latest.fingerprint)} 已报平安 · " +
                            compactTime(latest.timestamp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { navigateTo(4) }) { Text("查看报平安") }
                            TextButton(enabled = !busy, onClick = {
                                runTask("确认已读") {
                                    manager.markIncomingCheckinsRead()
                                    "已标记为已读，记录仍保留"
                                }
                            }) { Text("标记已读") }
                        }
                        InlineActionFeedback(actionFeedback["确认已读"])
                    }
                }
            }
            if (page == 0) {
                val topCase = currentCases.firstOrNull {
                    !it.ownSos && it.status == ExerciseStatus.OPEN
                } ?: currentCases.firstOrNull {
                    it.ownSos && it.status == ExerciseStatus.ACCEPTED
                } ?: currentCases.firstOrNull()
                if (topCase != null) {
                    Text("当前求助", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    ElevatedCard(modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (topCase.status == ExerciseStatus.ACCEPTED &&
                                topCase.ownSos) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(if (topCase.ownSos) "我发出的演练 SOS" else
                                "${peerName(topCase.relationId, topCase.subjectFingerprint)} 发来演练 SOS",
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (topCase.ownSos && topCase.status == ExerciseStatus.ACCEPTED) {
                                val helper = relations.firstOrNull {
                                    it.peerFingerprint == topCase.acceptedBy }
                                "${helper?.let { peerName(it.relationId, it.peerFingerprint) } ?: "已验证救助者"} 已接手"
                            } else if (topCase.ownSos) {
                                if (topCase.deliveredRelations.isEmpty())
                                    "${compactStatus(topCase.status)} · 未收到签名回执，不能认定已送达"
                                else "${compactStatus(topCase.status)} · 已有 ${topCase.deliveredRelations.size} 条签名回执，仍需实际接手"
                            } else compactStatus(topCase.status),
                                style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(compactTime(topCase.createdAt),
                                    style = MaterialTheme.typography.labelSmall)
                                if (topCase.ownSos && topCase.status != ExerciseStatus.CLOSED) {
                                    TextButton(onClick = { closing = topCase.eventId }) {
                                        Text("结束演练")
                                    }
                                } else if (!topCase.ownSos &&
                                    topCase.status == ExerciseStatus.OPEN) {
                                    Button(enabled = !busy, onClick = {
                                        runTask("请求接手", inlineResult = { sosFeedback = it }) {
                                            if (manager.claim(topCase.eventId) ==
                                                io.github.tigaotg.openkinguard.security.protocol.S1CaseWire.ACCEPT)
                                                "已签名确认接手，对方将收到提醒" else "未获对方确认"
                                        }
                                    }) { Text("确认接手") }
                                }
                            }
                            if (!topCase.ownSos && topCase.status == ExerciseStatus.OPEN)
                                InlineActionFeedback(sosFeedback)
                        }
                    }
                    if (currentCases.size > 1) TextButton(onClick = {
                        recordView = 0; navigateTo(4)
                    }) { Text("查看其余 ${currentCases.size - 1} 条请求") }
                }
            }
            if (page == 0) {
                manager.remoteAssistExitMessage()?.let { message ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { manager.dismissRemoteAssistExitMessage() }) {
                                Text("知道了")
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("在线家人", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { refreshByGesture() }, enabled = !busy) {
                        Text("↻ 刷新")
                    }
                }
                InlineActionFeedback(actionFeedback["刷新应用状态"])
                InlineActionFeedback(lanError)
                if (relations.isEmpty()) {
                    Text("还没有连接家人手机。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { navigateTo(1) }) { Text("连接家人手机") }
                }
                else if (onlineHomeDevices.isEmpty())
                    Text("暂无已确认在线的设备。可刷新状态，或点开上方的未在线设备列表。")
                onlineHomeDevices.forEach { device ->
                    val members = relations.filter { it.peerFingerprint == device.fingerprint }
                    val helperPeer = members.firstOrNull { it.role == PairingRole.HELPER }
                    val subjectPeer = members.firstOrNull { it.role == PairingRole.SUBJECT }
                    val displayPeer = helperPeer ?: subjectPeer ?: members.first()
                    val portal = manager.deviceFindPortal(device.fingerprint)
                    val relayOnline = device.relations.any {
                        it.relationId in snapshot.relayVerifiedRelations }
                    val directOnline = device.relations.any {
                        it.relationId in snapshot.directVerifiedRelations }
                    val latestFix = snapshot.history.receivedLocations.lastOrNull {
                        helperPeer != null &&
                        it.senderFingerprint == device.fingerprint }
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(peerName(displayPeer.relationId, device.fingerprint),
                                style = MaterialTheme.typography.titleMedium)
                            Text("● 在线 · ${when {
                                directOnline -> "公网UDP直连"
                                relayOnline -> "私人中继"
                                else -> "局域网"
                            }}已验签 · " +
                                when {
                                    helperPeer != null && subjectPeer != null ->
                                        "双向互助"
                                    helperPeer != null -> "我的被救助者"
                                    else -> "我的救助者"
                                }, color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                            if (latestFix != null) Text(
                                "最近位置 · ${FriendlyElapsedTime.since(latestFix.capturedAtMillis, homeNow)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("可用操作", style = MaterialTheme.typography.titleSmall)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (helperPeer != null) {
                                    OutlinedButton(enabled = !busy && roleOnline(helperPeer) &&
                                        assistSession == null,
                                        modifier = Modifier.fillMaxWidth(), onClick = {
                                        runTask("申请远程协助", inlineResult = {
                                            deviceFeedback["${device.fingerprint}:assist"] = it
                                        }) { manager.requestRemoteAssist(helperPeer.relationId) }
                                    }) { Text("申请远程协助") }
                                    InlineActionFeedback(deviceFeedback["${device.fingerprint}:assist"])
                                    assistSession?.takeIf {
                                        it.peerFingerprint == device.fingerprint
                                    }?.let { session ->
                                        Text(when (session.phase) {
                                            RemoteAssistConsent.Phase.WAITING_FOR_REPLY ->
                                                "等待家人主动同意；超过60秒自动失效"
                                            RemoteAssistConsent.Phase.WAITING_FOR_PERSON ->
                                                "等待本机确认"
                                            RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION ->
                                                "老人已同意，正在等待安卓屏幕共享授权"
                                            RemoteAssistConsent.Phase.ACTIVE ->
                                                "对方正在共享屏幕；点击画面可协助操作"
                                        }, style = MaterialTheme.typography.bodySmall)
                                        if (session.direction == RemoteAssistConsent.Direction.OUTGOING &&
                                            session.phase == RemoteAssistConsent.Phase.ACTIVE) {
                                            OutlinedButton(onClick = {
                                                showAssistControllerPanel = true
                                            }) { Text("打开 WebRTC 远程画面") }
                                        }
                                        TextButton(enabled = !busy, onClick = {
                                            runTask("结束远程协助") {
                                                manager.stopRemoteAssist()
                                            }
                                        }) { Text("结束远程协助") }
                                    }
                                    OutlinedButton(enabled = !busy && roleOnline(helperPeer),
                                        modifier = Modifier.fillMaxWidth(), onClick = {
                                        lastLocationRequestAt[device.fingerprint] =
                                            System.currentTimeMillis()
                                        requestingDevice = device.fingerprint
                                        runTask("请求对方的位置", inlineResult = {
                                            deviceFeedback["${device.fingerprint}:location"] = it
                                        }, friendlyFailure = { explanation ->
                                            LocationActionPresentation.message(explanation.code)
                                        }, onFailureDetail = { detail ->
                                            deviceFeedback["${device.fingerprint}:location:technical"] = detail
                                            expandedLocationDetails[device.fingerprint] = false
                                        }) {
                                            val fix = manager.requestRemoteLocation(helperPeer.relationId)
                                            "${peerName(helperPeer.relationId, device.fingerprint)}：${formatLocation(fix)}"
                                        }
                                    }) { Text("请求对方位置") }
                                    InlineActionFeedback(deviceFeedback["${device.fingerprint}:location"])
                                    deviceFeedback["${device.fingerprint}:location:technical"]
                                        ?.takeIf { it.isNotBlank() }?.let { technical ->
                                            TextButton(onClick = {
                                                expandedLocationDetails[device.fingerprint] =
                                                    expandedLocationDetails[device.fingerprint] != true
                                            }) { Text(if (expandedLocationDetails[device.fingerprint] == true)
                                                "收起高级信息 ↑" else "高级：查看详细原因 ↓") }
                                            if (expandedLocationDetails[device.fingerprint] == true)
                                                Text(technical, style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                }
                                if (subjectPeer != null) {
                                    OutlinedButton(enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(), onClick = {
                                        runTask("向这台手机报平安", inlineResult = {
                                            deviceFeedback["${device.fingerprint}:checkin"] = it
                                        }) {
                                            val id = manager.createOwnCheckin(subjectPeer.relationId)
                                            val errors = manager.deliverPending()
                                            if (errors.isEmpty()) "已向本设备发出报平安 ${id.take(8)}；请查验签名回执"
                                            else "已在本机保存，仅向该设备待送达：${errors.joinToString("；").take(200)}"
                                        }
                                    }) { Text("向对方报平安") }
                                    InlineActionFeedback(deviceFeedback["${device.fingerprint}:checkin"])
                                }
                                if (!snapshot.history.simpleHomeEnabled) {
                                    TextButton(onClick = {
                                        if (portal == null) {
                                            navigateTo(1)
                                            findPortalFeedback = "可在设备与授权中选择该手机的官方查找服务"
                                        } else selectedFindPortal = portal
                                    }) { Text(if (portal == null) "设置找手机入口" else "用厂商服务找手机") }
                                }
                                }
                            if (requestingDevice == device.fingerprint)
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            lastLocationRequestAt[device.fingerprint]?.let { requested ->
                                Text("上次发起定位请求：${FriendlyElapsedTime.since(requested, homeNow)}" +
                                    if (requestingDevice == device.fingerprint) " · 等待对方回复" else "",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (latestFix != null) {
                                TextButton(onClick = {
                                    expandedHomeLocations[device.fingerprint] =
                                        expandedHomeLocations[device.fingerprint] != true
                                }) { Text(if (expandedHomeLocations[device.fingerprint] == true)
                                    "收起历史位置 ↑" else "查看历史位置和地图 ↓") }
                            }
                            if (latestFix != null && expandedHomeLocations[device.fingerprint] == true) {
                                val historical = LocationMapPolicy.isHistorical(
                                    latestFix.capturedAtMillis, homeNow)
                                Text("上次获取的位置：${FriendlyElapsedTime.since(
                                    latestFix.capturedAtMillis, homeNow)} · " +
                                    "${if (latestFix.provider == "G") "GPS卫星" else "网络定位"} · " +
                                    "精度约 ${latestFix.accuracyCm / 100.0} 米" +
                                    if (historical) " · 已过时" else "",
                                    style = MaterialTheme.typography.bodySmall)
                                if (latestFix.accuracyCm >= 10_000) Text(
                                    S3FixQuality.description(latestFix.provider,
                                        latestFix.accuracyCm / 100f),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                                if (historical) Text("历史位置，打开地图前需再次确认",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(modifier = Modifier.weight(1f),
                                        onClick = { openMapLocation(latestFix, domestic = false) }) {
                                        Text("Google 地图")
                                    }
                                    OutlinedButton(modifier = Modifier.weight(1f),
                                        onClick = { openMapLocation(latestFix, domestic = true) }) {
                                        Text("国内地图")
                                    }
                                }
                                InlineActionFeedback(mapFeedback)
                            }
                            TextButton(onClick = {
                                expandedHomeCapabilities[device.fingerprint] =
                                    expandedHomeCapabilities[device.fingerprint] != true
                            }) { Text(if (expandedHomeCapabilities[device.fingerprint] == true)
                                "收起互助能力 ↑" else "对方能帮我什么？ ↓") }
                            if (expandedHomeCapabilities[device.fingerprint] == true) {
                                Surface(shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (subjectPeer == null) {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("○ 对方还不是我的救助者，需先建立双向互助关系。",
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodySmall)
                                                TextButton(onClick = {
                                                    showPairingRelations = true
                                                    navigateTo(1)
                                                }) { Text("去绑定") }
                                            }
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(if (roleOnline(subjectPeer))
                                                    "● 求助与报平安：可发送，送达需对方确认。"
                                                    else "○ 求助与报平安：这条帮助关系还未确认在线。",
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodySmall)
                                                if (!roleOnline(subjectPeer)) TextButton(onClick = {
                                                    navigateTo(3)
                                                    settingsSectionName = SettingsDestination.PRIVATE.name
                                                }) { Text("检查连接") }
                                            }
                                            val locationAllowed = subjectPeer.relationId in
                                                snapshot.history.locationSharingRelations
                                            val localGpsAllowed = manager.locationPermissionGranted()
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(when {
                                                    !localGpsAllowed ->
                                                        "○ 查看我的位置：本机尚未获得定位权限。"
                                                    !locationAllowed ->
                                                        "○ 查看我的位置：我尚未授权这位家人。"
                                                    else ->
                                                        "● 查看我的位置：已授权，对方可在我打开应用时申请。"
                                                }, modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodySmall)
                                                Column(horizontalAlignment = Alignment.End) {
                                                    if (!locationAllowed || !localGpsAllowed)
                                                        TextButton(enabled = !busy, onClick = {
                                                            if (!localGpsAllowed) locationPermission.launch(arrayOf(
                                                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                                                Manifest.permission.ACCESS_FINE_LOCATION))
                                                            else pendingLocationAuthorization = subjectPeer
                                                        }) { Text("获取权限") }
                                                    if (locationAllowed) TextButton(enabled = !busy,
                                                        onClick = {
                                                            runTask("撤销家人定位权限") {
                                                                manager.allowLocationToHelper(subjectPeer.relationId, false)
                                                                "已撤销这位家人查看我位置的授权"
                                                            }
                                                        }) { Text("撤销权限") }
                                                }
                                            }
                                            if (locationPermissionFeedback.isNotBlank() && !localGpsAllowed)
                                                InlineActionFeedback(locationPermissionFeedback)
                                            InlineActionFeedback(actionFeedback["授权家人查看位置"])
                                            InlineActionFeedback(actionFeedback["撤销家人定位权限"])
                                            val activeIncomingAssist = assistSession?.let {
                                                it.relationId == subjectPeer.relationId &&
                                                    it.direction == RemoteAssistConsent.Direction.INCOMING &&
                                                    it.phase in setOf(
                                                        RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION,
                                                        RemoteAssistConsent.Phase.ACTIVE)
                                            } == true
                                            val assistAllowed = subjectPeer.relationId !in
                                                snapshot.history.remoteAssistBlockedRelations
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(if (!assistAllowed)
                                                    "○ 远程协助：已禁止这位家人申请。"
                                                    else if (activeIncomingAssist)
                                                        "● 远程协助：这位家人已获得本次同意。"
                                                    else "● 远程协助：允许申请，每次仍需本人和安卓授权。",
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodySmall)
                                                Column(horizontalAlignment = Alignment.End) {
                                                    TextButton(onClick = {
                                                        inspectingRemoteAssistPermission = subjectPeer
                                                    }) { Text("检查权限") }
                                                    TextButton(enabled = !busy, onClick = {
                                                        runTask("更改远程协助权限") {
                                                            manager.setRemoteAssistRequestsAllowed(
                                                                subjectPeer.relationId, !assistAllowed)
                                                            if (assistAllowed)
                                                                "已取消这位家人的远程协助权限；正在进行的共享也已结束"
                                                            else "已恢复允许申请；每次仍需本人同意与系统授权"
                                                        }
                                                    }) { Text(if (assistAllowed) "取消权限" else "恢复允许") }
                                                }
                                            }
                                            if (activeIncomingAssist && assistAllowed) TextButton(
                                                enabled = !busy, onClick = {
                                                    runTask("撤销本次远程协助") {
                                                        manager.stopRemoteAssist()
                                                        "已撤销本次远程协助，屏幕共享已停止"
                                                    }
                                                }) { Text("仅结束本次协助") }
                                            InlineActionFeedback(actionFeedback["更改远程协助权限"])
                                            InlineActionFeedback(actionFeedback["撤销本次远程协助"])
                                        }
                                        if (subjectPeer != null) TextButton(onClick = {
                                            showPairingRelations = true
                                            navigateTo(1)
                                        }) { Text("管理此家人的更多权限") }
                                        if (helperPeer != null) Text(
                                            "我也可以帮助对方：申请定位或远程协助，仍需对方明确授权。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = {
                                            expandedHomeNetworkDetails[device.fingerprint] =
                                                expandedHomeNetworkDetails[device.fingerprint] != true
                                        }) { Text(if (expandedHomeNetworkDetails[device.fingerprint] == true)
                                            "收起网络详情 ↑" else "高级：IP 地址和连接详情 ↓") }
                                        if (expandedHomeNetworkDetails[device.fingerprint] == true) {
                                            Text(when {
                                                directOnline -> "已完成双向 UDP 直连验证。双方可能看到用于连接的网络地址。"
                                                relayOnline -> "当前经家庭节点通信，不需要先获取对方的公网 IP。尚未确认两机 UDP 直连。"
                                                else -> "当前通过局域网联系，对方可能看到通信使用的局域网地址。"
                                            } + "Android 没有单独的‘IP 权限’；公网直连是否可用，取决于网络地址、NAT 和防火墙。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Keep offline relatives below the online list; collapse them by default.
                // The source is the saved pairing relation list, never unpaired LAN discoveries.
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth()
                            .clickable { showOfflineDevices = !showOfflineDevices }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("未在线家人（${offlineHomeDevices.size} 台）",
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Text(if (showOfflineDevices) "收起 ↑" else "查看 ›",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (showOfflineDevices) {
                            HorizontalDivider()
                            if (offlineHomeDevices.isEmpty()) {
                                Text("当前没有未在线的已绑定设备。",
                                    modifier = Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            offlineHomeDevices.forEachIndexed { index, device ->
                                if (index > 0) HorizontalDivider()
                                val members = relations.filter {
                                    it.peerFingerprint == device.fingerprint }
                                val peer = members.first()
                                val lastSeen = members.mapNotNull {
                                    snapshot.history.lastVerifiedAt[it.relationId]
                                }.maxOrNull()
                                val roles = members.map { it.role }.toSet()
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp,
                                    vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(peerName(peer.relationId, device.fingerprint),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("○ 当前未确认在线 · " + when {
                                        PairingRole.HELPER in roles && PairingRole.SUBJECT in roles ->
                                            "双向互助"
                                        PairingRole.HELPER in roles -> "我的被救助者"
                                        else -> "我的救助者"
                                    }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(lastSeen?.let {
                                        "上次验签在线：${FriendlyElapsedTime.since(it, homeNow)}"
                                    } ?: "尚无签名在线记录",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                if (!snapshot.listening) InlineActionFeedback(
                    "当前本机没有启动监听，可能无法接收家人的请求")
            }

            if (page == 5) {
                HealthDevicesSection(healthMonitor, WearableSectionMode.OVERVIEW) {
                    navigateTo(3)
                    expandedSettingsGroup = "穿戴和健康"
                }
            }

            if (page == 1) {
                TextButton(onClick = { returnToSettings() }) { Text("← 返回设置") }
                Text("连接家人", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Text("${relations.size} 条已建立的帮助关系 · 新绑定需双方核对安全码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showPairingNickname = !showPairingNickname }) {
                    Text(if (showPairingNickname) "收起本机昵称" else "修改本机昵称 ▾")
                }
                if (showPairingNickname) {
                OutlinedTextField(value = ownDraft, onValueChange = { ownDraft = it.take(18) },
                    label = { Text("本机昵称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text(if (manager.usesSystemNickname()) "当前沿用手机系统设置的设备名称。"
                    else "当前使用手动昵称；可以随时恢复手机系统名称。",
                    style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy, onClick = {
                    runTask("保存本机昵称") {
                        manager.renameOwnDevice(ownDraft)
                        "昵称已保存；正在更新局域网广播，已绑定设备会通过签名消息同步新昵称"
                    }
                }) { Text("保存昵称") }
                if (!manager.usesSystemNickname()) TextButton(enabled = !busy, onClick = {
                    runTask("恢复系统昵称") {
                        manager.restoreSystemNickname()
                        handler.post { ownDraft = manager.ownNickname() }
                        "已恢复跟随手机系统名称。"
                    }
                }) { Text("恢复系统名称") }
                InlineActionFeedback(actionFeedback["保存本机昵称"])
                InlineActionFeedback(actionFeedback["恢复系统昵称"])
                }
                TextButton(onClick = { showPairingRelations = !showPairingRelations }) {
                    Text(if (showPairingRelations) "收起已绑定设备" else "管理已绑定设备（${relations.size}） ▾")
                }
                if (showPairingRelations) {
                Text("已绑定的家人", style = MaterialTheme.typography.titleMedium)
                val legacyDuplicates = manager.legacyDuplicateRelations()
                if (legacyDuplicates.isNotEmpty()) {
                    ElevatedCard(colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("发现旧版重复绑定", style = MaterialTheme.typography.titleMedium)
                            Text("同一设备指纹在同一方向存在多条旧关系。请选一条仍有效的关系保留；" +
                                "其余仅停用，历史不会被删除。另一台手机的关系不会被自动修改。")
                            legacyDuplicates.forEach { group ->
                                val peer = group.first()
                                Text("设备 ${peer.peerFingerprint.take(12)} · " +
                                    if (peer.role == PairingRole.SUBJECT) "我的救助者" else "我的被救助者")
                                group.forEach { candidate ->
                                    Text("关系 ${candidate.relationId.take(8)} · " +
                                        "最近验签：${snapshot.history.lastVerifiedAt[candidate.relationId]?.let {
                                            compactTime(it)
                                        } ?: "尚无"}")
                                    OutlinedButton(onClick = { keepLegacyRelation = candidate },
                                        enabled = !busy) { Text("保留这条，停用同组其他关系") }
                                }
                            }
                        }
                    }
                }
                if (relations.isEmpty()) Text("尚无帮助关系。可在下方发现设备后安全绑定。")
                relations.forEach { peer ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(peerName(peer.relationId, peer.peerFingerprint),
                                style = MaterialTheme.typography.titleMedium)
                            Text(if (peer.role == PairingRole.SUBJECT)
                                "我是被救助者 · 这是我的救助者" else
                                "我是救助者 · 这是我帮助的人")
                            val signedName = snapshot.history.verifiedPeerNicknames[peer.relationId]
                                .orEmpty().ifBlank {
                                    snapshot.history.verifiedDeviceNicknames[peer.peerFingerprint].orEmpty()
                                }
                            if (signedName.isNotBlank() &&
                                snapshot.history.peerNicknames[peer.relationId] != signedName) {
                                Text("对方已验签昵称：$signedName")
                            }
                            val currentPortal = manager.deviceFindPortal(peer.peerFingerprint)
                            Text("对应的厂商查找：${currentPortal?.name ?: "尚未指定"}")
                            Box {
                                OutlinedButton(onClick = {
                                    portalPickerFingerprint = peer.peerFingerprint
                                }) { Text("指定此设备的厂商查找入口") }
                                DropdownMenu(expanded = portalPickerFingerprint == peer.peerFingerprint,
                                    onDismissRequest = { portalPickerFingerprint = null }) {
                                    OfficialDeviceFindPortals.entries.forEach { portal ->
                                        DropdownMenuItem(text = { Text(portal.name) }, onClick = {
                                            manager.setDeviceFindPortal(peer.peerFingerprint, portal.url)
                                            portalPickerFingerprint = null
                                            refresh()
                                        })
                                    }
                                    DropdownMenuItem(text = { Text("不关联厂商入口") }, onClick = {
                                        manager.setDeviceFindPortal(peer.peerFingerprint, null)
                                        portalPickerFingerprint = null
                                        refresh()
                                    })
                                }
                            }
                            val presence = PeerPresence.level(
                                snapshot.history.lastVerifiedAt[peer.relationId],
                                System.currentTimeMillis(),
                                snapshot.history.lossWarningMinutes,
                                maxOf(snapshot.history.lossWarningMinutes + 1,
                                    snapshot.history.lossCriticalMinutes))
                            Text(when (presence) {
                                PeerPresenceLevel.NEVER_SEEN -> "尚未完成过签名心跳"
                                PeerPresenceLevel.RECENT -> "签名心跳正常（局域网）"
                                PeerPresenceLevel.WAITING -> "等待新的签名心跳（局域网）"
                                PeerPresenceLevel.WARNING ->
                                    "超过${snapshot.history.lossWarningMinutes}分钟未验签连接"
                                PeerPresenceLevel.CRITICAL ->
                                    "超过${snapshot.history.lossCriticalMinutes}分钟未验签连接"
                            })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    editingPeer = peer.relationId
                                    peerDraft = snapshot.history.peerNicknames[peer.relationId].orEmpty()
                                }) { Text("改昵称") }
                                TextButton(onClick = { revoking = peer.relationId }) {
                                    Text("撤销关系")
                                }
                            }
                            if (editingPeer == peer.relationId) {
                                OutlinedTextField(value = peerDraft,
                                    onValueChange = { peerDraft = it.take(18) },
                                    label = { Text("这台设备的备注名") }, singleLine = true)
                                Button(enabled = !busy, onClick = {
                                    runTask("保存备注") {
                                        manager.renamePeer(peer.relationId, peerDraft)
                                        handler.post { editingPeer = null }
                                        "备注已保存在本机，不改变公钥身份和对方设备昵称"
                                    }
                                }) { Text("保存备注") }
                                InlineActionFeedback(actionFeedback["保存备注"])
                            }
                            if (peer.role == PairingRole.SUBJECT) {
                                val allowed = peer.relationId in
                                    snapshot.history.locationSharingRelations
                                Text(if (allowed) "定位权限：我已允许这位救助者在我打开应用时查询"
                                    else "定位权限：未允许这位救助者查询我的位置")
                                OutlinedButton(enabled = !busy,
                                    onClick = {
                                        if (!allowed && !manager.locationPermissionGranted()) {
                                            locationPermission.launch(arrayOf(
                                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                                Manifest.permission.ACCESS_FINE_LOCATION))
                                        } else runTask("更新定位授权") {
                                            manager.allowLocationToHelper(peer.relationId, !allowed)
                                            if (allowed) "已撤销此关系的定位授权"
                                            else "仅允许此救助者在我打开应用时请求单次定位"
                                        }
                                    }) { Text(if (allowed) "撤销定位授权" else "授权救助者查看定位") }
                                InlineActionFeedback(actionFeedback["更新定位授权"])
                            } else {
                                Text("仅对方本人明确授权后，才能请求其位置。",
                                    style = MaterialTheme.typography.bodySmall)
                                Button(enabled = !busy && liveLocationRelation == null,
                                    onClick = {
                                        runTask("正在请求对方位置") {
                                            val fix = manager.requestRemoteLocation(peer.relationId)
                                            "${peerName(peer.relationId, peer.peerFingerprint)}：${formatLocation(fix)}"
                                        }
                                    }) { Text("请求一次定位") }
                                InlineActionFeedback(actionFeedback["正在请求对方位置"])
                                if (liveLocationRelation == peer.relationId) {
                                    Text(liveLocationStatus)
                                    OutlinedButton(onClick = { liveLocationRelation = null }) {
                                        Text("停止连续查看")
                                    }
                                } else {
                                    OutlinedButton(enabled = !busy && liveLocationRelation == null,
                                        onClick = {
                                            liveLocationStatus = "正在请求最新位置…"
                                            liveLocationRelation = peer.relationId
                                        }) { Text("连续查看（约每10秒请求）") }
                                }
                                val latest = snapshot.history.receivedLocations.lastOrNull {
                                    it.relationId == peer.relationId
                                }
                                if (latest != null) {
                                    Text("最近记录：${compactTime(latest.capturedAtMillis)} · " +
                                        "纬度 ${latest.latitudeE7 / 1e7} · 经度 ${latest.longitudeE7 / 1e7} · " +
                                        "约 ${latest.accuracyCm / 100.0} 米")
                                    TextButton(onClick = { openMapLocation(latest) }) {
                                        Text("使用已设置的地图查看定位")
                                    }
                                }
                                HorizontalDivider()
                                Text("安全区域（本机设置）", style = MaterialTheme.typography.titleMedium)
                                Text("只有已经得到对方授权的定位才能设定中心。之后每次查询时评估；" +
                                    "两次可靠结果才确认变化，离开或返回时给本机发演练提醒。")
                                val zone = snapshot.history.safetyZones[peer.peerFingerprint]
                                if (zone == null) {
                                    OutlinedButton(enabled = !busy && latest != null, onClick = {
                                        runTask("建立安全区域") {
                                            manager.configureSafetyZone(peer.relationId, 300)
                                            "以最近验签定位为中心设置300米区域；仅后续手动请求时判断"
                                        }
                                    }) { Text("以最新位置为中心 · 建立300米安全区域") }
                                    InlineActionFeedback(actionFeedback["建立安全区域"])
                                } else {
                                    Text("半径 ${zone.radiusMeters} 米 · " + when (zone.position) {
                                        SafetyZonePolicy.Position.INSIDE -> "上次确认位于区域内"
                                        SafetyZonePolicy.Position.OUTSIDE -> "上次确认位于区域外"
                                        SafetyZonePolicy.Position.UNKNOWN -> "尚未获得足够准确的两次新定位"
                                    })
                                    if (zone.candidate != SafetyZonePolicy.Position.UNKNOWN)
                                        Text("正在等待第二次可靠定位确认，暂不触发提醒。",
                                            style = MaterialTheme.typography.bodySmall)
                                    Box {
                                        OutlinedButton(onClick = {
                                            zoneRadiusPickerFingerprint = peer.peerFingerprint
                                        }) { Text("更改安全区域半径") }
                                        DropdownMenu(expanded = zoneRadiusPickerFingerprint ==
                                            peer.peerFingerprint,
                                            onDismissRequest = { zoneRadiusPickerFingerprint = null }) {
                                            SafetyZonePolicy.allowedRadiiMeters.sorted().forEach { radius ->
                                                DropdownMenuItem(text = { Text("$radius 米") }, onClick = {
                                                    zoneRadiusPickerFingerprint = null
                                                    runTask("调整安全区域半径") {
                                                        manager.updateSafetyZoneRadius(peer.relationId, radius)
                                                        "半径已调整为 $radius 米；等待两次新定位重新确认区域状态"
                                                    }
                                                })
                                            }
                                        }
                                    }
                                    OutlinedButton(enabled = !busy && latest != null, onClick = {
                                        runTask("重设安全区域中心") {
                                            manager.configureSafetyZone(peer.relationId, zone.radiusMeters)
                                            "已用最近验签定位重新设置区域中心；之前的区域判断已重置"
                                        }
                                    }) { Text("用最新定位重新设置中心") }
                                    InlineActionFeedback(actionFeedback["重设安全区域中心"])
                                    TextButton(enabled = !busy, onClick = {
                                        runTask("关闭安全区域") {
                                            manager.removeSafetyZone(peer.relationId)
                                            "已关闭此设备的本机安全区域；之前的历史记录仍保留"
                                        }
                                    }) { Text("关闭此设备的安全区域") }
                                    InlineActionFeedback(actionFeedback["关闭安全区域"])
                                }
                            }
                        }
                    }
                }
                }
                HorizontalDivider()
                Text("建立新关系", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                FeatureHelp("同一个 Wi-Fi 下发现对方后，点击邀请；对方只需同意一次。" +
                    "两台手机核对同一个8位安全码，由被救助者确认后，后续签名自动完成。" +
                    "远程加入家庭中继与建立互助关系仍是两种独立授权。", label = "怎么连接家人的手机")
                TextButton(onClick = { showFamilyRelayPairing = !showFamilyRelayPairing }) {
                    Text(if (showFamilyRelayPairing) "同家庭中继添加家人 · 收起 ▴" else
                        "同家庭中继添加家人（无需同一 Wi-Fi） ▾")
                }
                if (showFamilyRelayPairing) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("通过同一个家庭中继查找设备",
                                style = MaterialTheme.typography.titleMedium)
                            Text("两部手机须分别扫码加入并经路由器主人批准。这里只显示同节点在线的设备指纹；" +
                                "连接节点不代表已经成为家人，也不会自动开放求助、定位或远程协助权限。" +
                                "对方须亲自同意，并通过通话核对8位安全码。",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && manager.familyRelayReady(), onClick = {
                                    runTask("查找同节点家人") {
                                        val count = manager.discoverFamilyRelayPeers()
                                        "查找到 $count 台同家庭中继在线设备；设备指纹不是姓名"
                                    }
                                }) { Text("查找同节点在线设备") }
                            InlineActionFeedback(actionFeedback["查找同节点家人"])
                            if (!manager.familyRelayReady()) Text(
                                "尚未连接支持此功能的家庭中继。先到设置 → 网络 → 私人节点连接；" +
                                    "路由器端也需要更新中继插件。",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            if (manager.familyRelayReady() && manager.familyRelayPeers().isEmpty())
                                Text("请点击查找；如仍为空，请让家人的 App 保持联网。")
                            manager.familyRelayPeers().forEach { peer ->
                                val already = relations.any { it.peerFingerprint == peer.fingerprint &&
                                    it.role == PairingRole.SUBJECT }
                                Text("设备指纹：${peer.fingerprint.take(12)}… · 家庭节点${peer.slot + 1}")
                                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy && !already && manager.pendingPairing() == null &&
                                        manager.remotePairingSession() == null,
                                    onClick = {
                                        runTask("同节点邀请家人") {
                                            manager.inviteOnFamilyRelay(peer)
                                            "已发送签名邀请，等待对方同意；请在两台手机上核对安全码"
                                        }
                                    }) { Text(if (already) "已是我的救助者" else "邀请这台设备成为我的救助者") }
                                InlineActionFeedback(actionFeedback["同节点邀请家人"])
                            }
                            Text("如果需要反向帮助关系，请在另一台手机上发起邀请。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val remoteSession = manager.remotePairingSession()
                TextButton(onClick = { showRemotePairingSetup = !showRemotePairingSetup }) {
                    Text(if (showRemotePairingSetup || remoteSession != null)
                        "跨网邀请 · 收起说明" else "跨网邀请家人（不在同一 Wi-Fi） ▾")
                }
                if (showRemotePairingSetup || remoteSession != null ||
                    remoteInviteDraft.isNotBlank()) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("通过公共节点远程邀请", style = MaterialTheme.typography.titleMedium)
                        Text("双方不用加入家庭中继，但必须有至少一个相同且正常连接的公共节点。" +
                            "邀请有效10分钟；邀请链接不包含位置或 SOS 数据。",
                            style = MaterialTheme.typography.bodySmall)
                        if (remoteSession == null) {
                            Button(modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && manager.publicRelayReady(), onClick = {
                                    runTask("生成远程邀请") {
                                        manager.createRemotePairingInvite()
                                        "邀请链接已生成，请用下方复制或分享按钮发送给家人"
                                    }
                                }) { Text("生成远程邀请链接 · 我需要对方帮助") }
                            InlineActionFeedback(actionFeedback["生成远程邀请"])
                            OutlinedTextField(value = remoteInviteDraft,
                                onValueChange = { remoteInviteDraft = it.take(8_000) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("粘贴家人发来的邀请链接") },
                                minLines = 2, maxLines = 4)
                            OutlinedButton(modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && remoteInviteDraft.isNotBlank(), onClick = {
                                    runTask("接收远程邀请") {
                                        val sender = manager.acceptRemotePairingInvite(remoteInviteDraft)
                                        "已验证邀请签名（${sender}…），请明确同意成为对方的救助者"
                                    }
                                }) { Text("验证邀请链接") }
                            InlineActionFeedback(actionFeedback["接收远程邀请"])
                            if (!manager.publicRelayReady()) Text(
                                "公共节点尚未就绪。先前往设置 → 网络 → 公共节点与 P2P 开启连接。",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            val remotePairing = manager.pendingPairing()
                            val remoteCode = runCatching { manager.pairingSafetyCode() }.getOrNull()
                            Text(if (remoteSession.role == PairingRole.SUBJECT)
                                "本轮我是被救助者（邀请者）" else
                                "本轮我是救助者（接受邀请者）")
                            Text("当前进度：${remotePairing?.phase?.let(::pairingPhaseName) ?: "等待恢复"}")
                            if (remoteSession.role == PairingRole.SUBJECT) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        val clipboard = context.getSystemService(
                                            Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                            "OpenKinGuard 一次性远程邀请", remoteSession.link))
                                        remoteInviteFeedback = "已复制一次性邀请链接，10分钟后过期"
                                    }) { Text("复制邀请链接") }
                                    OutlinedButton(onClick = {
                                        val sharing = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, remoteSession.link)
                                        }
                                        runCatching { context.startActivity(Intent.createChooser(
                                            sharing, "发送 OpenKinGuard 邀请给家人")) }
                                            .onFailure { remoteInviteFeedback =
                                                "系统无法打开分享界面：${it.message}" }
                                    }) { Text("分享链接") }
                                }
                            }
                            if (remoteCode != null) {
                                Text("安全码：$remoteCode",
                                    style = MaterialTheme.typography.headlineMedium)
                                Text("务必通过语音通话等独立方式逐位核对两机的8位码",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (System.currentTimeMillis() >= remoteSession.expiresAt) {
                                Text("邀请已经过期，请取消后重新生成。",
                                    color = MaterialTheme.colorScheme.error)
                            } else when (remotePairing?.phase) {
                                PairingPhase.REVIEW_INVITE -> {
                                    Button(enabled = !busy, onClick = {
                                        runTask("同意远程邀请") { manager.confirmRemotePairing() }
                                    }) { Text("同意成为对方的救助者") }
                                    InlineActionFeedback(actionFeedback["同意远程邀请"])
                                }
                                PairingPhase.REVIEW_RESPONSE -> {
                                    key(remotePairing.phase) {
                                        var checked by remember { mutableStateOf(false) }
                                        Row {
                                            Checkbox(checked = checked && remoteCode != null,
                                                onCheckedChange = {
                                                    checked = it && remoteCode != null
                                                })
                                            Text("我已与对方逐位核对8位安全码，完全一致")
                                        }
                                        Button(enabled = checked && remoteCode != null && !busy,
                                            onClick = {
                                                runTask("确认远程安全码") {
                                                    manager.confirmRemotePairing()
                                                }
                                            }) { Text("安全码一致 · 完成远程授权") }
                                        InlineActionFeedback(actionFeedback["确认远程安全码"])
                                    }
                                }
                                PairingPhase.REVIEW_GRANT -> {
                                    Text("正在自动发送最后签名回执。如长时间无变化，可点重发。")
                                    OutlinedButton(enabled = !busy, onClick = {
                                        runTask("续接远程签名") { manager.confirmRemotePairing() }
                                    }) { Text("继续签名") }
                                }
                                PairingPhase.COMPLETE -> {
                                    val finished = remoteSession.role == PairingRole.HELPER ||
                                        remoteSession.peerFinished
                                    Text(if (finished) "远程帮助关系已在双方完成签名确认。" else
                                        "本机已保存关系，等待对方保存完成的签名确认。请暂时保持在线。")
                                    Button(enabled = !busy && finished, onClick = {
                                        runTask("结束远程绑定") {
                                            manager.finishRemotePairing()
                                            "已保留帮助关系，现可继续添加其他家人"
                                        }
                                    }) { Text(if (finished) "完成 · 保留关系" else "等待对方完成") }
                                }
                                else -> Text("等待对方操作或签名回执，请保持双方应用在前台。")
                            }
                            if (remotePairing?.phase in listOf(PairingPhase.WAIT_GRANT,
                                    PairingPhase.WAIT_ACK, PairingPhase.ACK_TO_SHOW,
                                    PairingPhase.COMPLETE)) {
                                OutlinedButton(enabled = !busy &&
                                    System.currentTimeMillis() < remoteSession.expiresAt,
                                    onClick = {
                                        runTask("重发远程签名") { manager.retryRemotePairing() }
                                    }) { Text("对方没收到？重发签名消息") }
                                InlineActionFeedback(actionFeedback["重发远程签名"])
                            }
                            if (remotePairing?.phase != PairingPhase.COMPLETE) {
                                TextButton(enabled = !busy, onClick = {
                                    runTask("取消远程邀请") {
                                        manager.cancelRemotePairing()
                                        "本机已取消；对方仍可能看到旧邀请，过期后自动失效"
                                    }
                                }) { Text("取消这次远程邀请") }
                                InlineActionFeedback(actionFeedback["取消远程邀请"])
                            }
                        }
                        InlineActionFeedback(remoteInviteFeedback)
                    }
                }
                }
                val pairing = manager.pendingPairing()
                val pendingOffer = manager.pendingHelpOffer()
                if (remoteSession != null) {
                    Text("远程邀请正在进行；如需改用同一 Wi-Fi 绑定，请先取消远程邀请。")
                } else if (pairing == null && pendingOffer != null) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (pendingOffer.incoming) {
                                Text("${pendingOffer.nickname} 希望成为我的救助者",
                                    style = MaterialTheme.typography.titleMedium)
                                Text("附近广播昵称尚未经身份验证。同意后会显示安全码，" +
                                    "双方核对一致才能最终绑定。")
                                Button(enabled = !busy, onClick = {
                                    runTask("接受帮助邀请") {
                                        manager.acceptHelpOffer()
                                        "已同意邀请，正在交换签名，稍后核对安全码"
                                    }
                                }) { Text("同意让对方成为我的救助者") }
                                InlineActionFeedback(actionFeedback["接受帮助邀请"])
                            } else {
                                Text("正在邀请 ${pendingOffer.nickname} 成为我的被救助者",
                                    style = MaterialTheme.typography.titleMedium)
                                Text("等待对方同意，然后核对同一个8位安全码。")
                                OutlinedButton(enabled = !busy, onClick = {
                                    runTask("重新发送邀请") {
                                        manager.retryHelpOffer()
                                        "已重新提醒对方，未额外创建关系"
                                    }
                                }) { Text("对方没反应？重发邀请") }
                                InlineActionFeedback(actionFeedback["重新发送邀请"])
                            }
                            TextButton(enabled = !busy, onClick = {
                                runTask("撤回邀请") {
                                    val both = manager.cancelLanPairing()
                                    if (both) "双方已退出当前邀请" else
                                        "本机已退出，对方目前离线；请让对方重新打开应用确认"
                                }
                            }) { Text(if (pendingOffer.incoming) "拒绝邀请" else "撤回邀请") }
                            InlineActionFeedback(actionFeedback["撤回邀请"])
                        }
                    }
                } else if (pairing == null) {
                    if (snapshot.nodes.isEmpty()) Text("未发现其他设备，请保持双方应用处于前台")
                    snapshot.nodes.forEach { node ->
                        val trusted = relations.firstOrNull {
                            snapshot.verifiedNodeServices[it.relationId] == node.serviceName
                        }
                        val alreadyHelper = manager.hasVerifiedRoleWithNode(node, PairingRole.SUBJECT)
                        val alreadySubject = manager.hasVerifiedRoleWithNode(node, PairingRole.HELPER)
                        // Discovery names are untrusted and may be refreshed independently of
                        // a user-saved local alias. Never let an old alias mask a new broadcast.
                        val presentedName = DeviceActionPolicy.discoveryLabel(
                            node.advertisedNickname,
                            trusted?.let {
                                snapshot.history.verifiedPeerNicknames[it.relationId].orEmpty()
                            }.orEmpty(),
                            trusted?.let { peerName(it.relationId, it.peerFingerprint) }.orEmpty(),
                            node.serviceName)
                        Text(presentedName, style = MaterialTheme.typography.titleMedium)
                        if (trusted != null) {
                            val signed = snapshot.history.verifiedPeerNicknames[trusted.relationId]
                            if (!signed.isNullOrBlank()) Text("最近验签昵称：$signed",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("设备名称未经验证，首次绑定必须核对8位安全码。",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && !alreadyHelper,
                            onClick = {
                                runTask("发起绑定邀请") {
                                    manager.inviteOnLan(node)
                                    "我将成为被救助者，已邀请对方成为我的救助者"
                                }
                            }) {
                            Text(if (alreadyHelper) "此方向已绑定 · 我的救助者" else
                                "邀请对方成为我的救助者")
                        }
                        InlineActionFeedback(actionFeedback["发起绑定邀请"])
                        OutlinedButton(modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && !alreadySubject,
                            onClick = {
                                runTask("请求成为救助者") {
                                    manager.offerHelpOnLan(node)
                                    "已请求成为对方的救助者，等待对方同意"
                                }
                        }) { Text(if (alreadySubject) "此方向已绑定 · 我的被救助者" else
                            "邀请对方成为我的被救助者") }
                        InlineActionFeedback(actionFeedback["请求成为救助者"])
                        Text(if (trusted != null) "已验证过此设备的公钥" else
                            "新设备未验签，双方必须核对安全码")
                    }
                } else {
                    val usingFamilyRelay = manager.canContinueFamilyPairing()
                    if (usingFamilyRelay) Text("正在通过家庭中继交换签名邀请（需双方联网）")
                    Text("绑定进度：${pairingPhaseName(pairing.phase)}")
                    Text(if (pairing.role == PairingRole.SUBJECT)
                        "本轮我是被救助者" else "本轮我是救助者")
                    val code = runCatching { manager.pairingSafetyCode() }.getOrNull()
                    if (code != null) {
                        Text("安全码：$code", style = MaterialTheme.typography.headlineMedium)
                        Text("两机必须逐位核对，不能跳过")
                    }
                    if (pairing.phase == PairingPhase.COMPLETE) {
                        Text("帮助关系已经保存")
                        Button(enabled = !busy, onClick = {
                            runTask("完成本轮绑定") {
                                if (usingFamilyRelay) manager.cancelFamilyPairing()
                                else manager.cancelLanPairing()
                                "关系已保留。若要双向求助，请由另一台手机反向再绑定一次"
                            }
                        }) { Text("保留关系 · 开始下一轮") }
                        InlineActionFeedback(actionFeedback["完成本轮绑定"])
                    } else if (!manager.canContinueLanPairing() && !usingFamilyRelay) {
                        Text("绑定会话已中断，可取消后重新开始")
                    } else if (pairing.phase == PairingPhase.REVIEW_INVITE) {
                        Text("对方邀请你成为救助者。确认认识此人后，同意邀请；" +
                            "稍后两部手机还要核对一次安全码。")
                        Button(enabled = !busy, onClick = {
                            runTask("同意局域网邀请") {
                                if (usingFamilyRelay) manager.confirmFamilyPairing()
                                else manager.confirmLanPairing()
                                "已同意邀请，等待对方核对安全码"
                            }
                        }) { Text("同意成为对方的救助者") }
                        InlineActionFeedback(actionFeedback["同意局域网邀请"])
                    } else if (pairing.phase == PairingPhase.REVIEW_RESPONSE) {
                        key(pairing.phase) {
                            var checked by remember { mutableStateOf(false) }
                            Row {
                                Checkbox(checked = checked && code != null,
                                    onCheckedChange = { checked = it && code != null })
                                Text("我已与对方核对8位安全码，逐位完全一致")
                            }
                            Button(enabled = checked && code != null && !busy, onClick = {
                                runTask("核对安全码并绑定") {
                                    if (usingFamilyRelay) manager.confirmFamilyPairing()
                                    else manager.confirmLanPairing()
                                    "安全码已核对，正在自动交换最终签名与回执"
                                }
                            }) { Text("安全码一致 · 完成绑定") }
                            InlineActionFeedback(actionFeedback["核对安全码并绑定"])
                        }
                    } else if (pairing.phase == PairingPhase.REVIEW_GRANT) {
                        Text("已收到对方授权，正在自动生成最终签名回执。")
                        OutlinedButton(enabled = !busy, onClick = {
                            runTask("恢复自动签名") {
                                if (usingFamilyRelay) manager.confirmFamilyPairing()
                                else manager.confirmLanPairing()
                                "已完成最终签名回执"
                            }
                        }) { Text("如果长时间停留，点此继续") }
                        InlineActionFeedback(actionFeedback["恢复自动签名"])
                    } else if (pairing.phase == PairingPhase.ACK_TO_SHOW) {
                        Button(enabled = !busy, onClick = {
                            runTask("完成绑定") {
                                if (usingFamilyRelay) manager.retryFamilyPairing()
                                else manager.finishLanPairing()
                                "本轮已完成，请检查双方帮助关系"
                            }
                        }) { Text("对方显示完成 · 结束本轮") }
                        InlineActionFeedback(actionFeedback["完成绑定"])
                    }
                    if (pairing.phase in listOf(PairingPhase.WAIT_RESPONSE,
                            PairingPhase.WAIT_GRANT, PairingPhase.WAIT_ACK,
                            PairingPhase.ACK_TO_SHOW) &&
                        (manager.canContinueLanPairing() || usingFamilyRelay)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            runTask("重发签名消息") {
                                if (usingFamilyRelay) manager.retryFamilyPairing()
                                else manager.retryLanPairingStep()
                                "同一条已签名消息已重发，没有跳过任何确认步骤"
                            }
                        }) { Text("对方没反应？重发上一步") }
                        InlineActionFeedback(actionFeedback["重发签名消息"])
                    }
                    if (pairing.phase != PairingPhase.COMPLETE) {
                        TextButton(enabled = !busy, onClick = {
                            runTask("取消本轮配对") {
                                val both = if (usingFamilyRelay) {
                                    manager.cancelFamilyPairing()
                                    false
                                } else manager.cancelLanPairing()
                                if (both) "本轮双方都已退出；此前有效关系不受影响" else
                                    "本机已退出，请让对方也取消未完成的邀请；此前有效关系不受影响"
                            }
                        }) { Text("取消当前配对") }
                        InlineActionFeedback(actionFeedback["取消本轮配对"])
                    }
                }
                if (remoteSession == null) {
                    TextButton(onClick = { showQr = !showQr }) {
                        Text(if (showQr) "收起扫码备用方式" else "无法局域网绑定？使用扫码备用方式")
                    }
                    if (showQr) PairingSection()
                }
            }

            if (page == 2) {
                MedicalHealthSection(pageScroll)
            }

            if (page == 4) {
                Text("记录", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("当前请求", "历史请求", "报平安", "定位", "操作").forEachIndexed { index, label ->
                        FilterChip(selected = recordView == index,
                            onClick = { recordView = index }, label = { Text(label) })
                    }
                }
                when (recordView) {
                    0 -> {
                        FeatureHelp("每台设备只保留一条当前 SOS 请求；新请求替代旧请求。",
                            label = "当前请求说明")
                        if (currentCases.isEmpty()) Text("暂无当前求助请求")
                        currentCases.forEach { item ->
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (item.ownSos) "我发出的测试 SOS" else
                                        "${peerName(item.relationId, item.subjectFingerprint)} 发出的测试 SOS",
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("${compactTime(item.createdAt)} · ${compactStatus(item.status)}")
                                    if (item.ownSos) {
                                        if (item.status == ExerciseStatus.ACCEPTED) {
                                            val helper = relations.firstOrNull { it.peerFingerprint == item.acceptedBy }
                                            Text("${helper?.let { peerName(it.relationId, it.peerFingerprint) } ?: "已验证救助者"} 已接手",
                                                color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text("签名送达回执：${item.deliveredRelations.size} 个")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(enabled = !busy, onClick = {
                                                runTask("重试当前求助") {
                                                    manager.retryOwn(item.eventId)
                                                    val errs = manager.deliverPending()
                                                    if (errs.isEmpty()) "已重试当前请求" else
                                                        "重试后仍待确认：${errs.joinToString("；").take(320)}"
                                                }
                                            }) { Text("重试送达") }
                                            TextButton(onClick = { closing = item.eventId }) {
                                                Text("结束演练")
                                            }
                                        }
                                        InlineActionFeedback(actionFeedback["重试当前求助"])
                                    } else if (item.status == ExerciseStatus.OPEN) {
                                        Button(enabled = !busy, onClick = {
                                            runTask("申请接手") {
                                                if (manager.claim(item.eventId) ==
                                                    io.github.tigaotg.openkinguard.security.protocol.S1CaseWire.ACCEPT)
                                                    "已接手，对方将收到提醒" else "未获对方确认接手"
                                            }
                                        }) { Text("确认接手") }
                                        InlineActionFeedback(actionFeedback["申请接手"])
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        FeatureHelp("历史请求只供查看，不会再次提醒或允许接手。")
                        if (archivedCases.isEmpty() && archivedCheckins.isEmpty()) {
                            Text("暂无历史请求")
                        }
                        archivedCases.forEach { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (item.ownSos) "我之前发出的测试 SOS" else
                                        "${peerName(item.relationId, item.subjectFingerprint)} 的历史 SOS")
                                    Text("${compactTime(item.createdAt)} · " +
                                        if (item.supersededBy.isNotBlank()) "已被新请求替代"
                                        else compactStatus(item.status))
                                    Text("事件编号：${item.eventId.take(12)}")
                                    if (item.ownSos && item.status == ExerciseStatus.CLOSED &&
                                        item.supersededBy.isBlank()) {
                                        val activeHelpers = relations.filter {
                                            it.role == PairingRole.SUBJECT
                                        }
                                        val confirmed = activeHelpers.count {
                                            it.relationId in item.closedSyncedRelations
                                        }
                                        Text(if (confirmed == activeHelpers.size)
                                            "远端结案已同步" else
                                            "远端结案同步中：$confirmed/${activeHelpers.size} · 上线后自动重试")
                                    }
                                }
                            }
                        }
                        archivedCheckins.forEach { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (item.own) "我之前的报平安" else
                                        "${peerName(item.relationId, item.fingerprint)} 之前的报平安")
                                    Text("${compactTime(item.timestamp)} · 已被新报平安替代")
                                }
                            }
                        }
                    }
                    2 -> {
                        Text("每台设备最新一次报平安", style = MaterialTheme.typography.titleMedium)
                        if (currentCheckins.isEmpty()) Text("暂无报平安，可从求助页发送")
                        currentCheckins.forEach { record ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (record.own) "我发送的报平安" else
                                        "${peerName(record.relationId, record.fingerprint)} 已报平安")
                                    Text(compactTime(record.timestamp))
                                    if (!record.own &&
                                        record.timestamp > snapshot.history.lastReadCheckinTimestamp) {
                                        Text("未读", color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (record.own) {
                                        Text("签名送达回执：${record.deliveredRelations.size} 个")
                                        if (record.deliveredRelations.isEmpty()) {
                                            TextButton(enabled = !busy, onClick = {
                                                runTask("重试报平安") {
                                                    manager.retryCheckin(record.eventId)
                                                    val errs = manager.deliverPending()
                                                    if (errs.isEmpty()) "已重试，查看回执数" else
                                                        "重试后仍待确认：${errs.joinToString("；").take(320)}"
                                                }
                                            }) { Text("重试送达") }
                                            InlineActionFeedback(actionFeedback["重试报平安"])
                                        }
                                    }
                                }
                            }
                        }
                        if (unreadCheckins.isNotEmpty()) {
                            OutlinedButton(enabled = !busy, onClick = {
                                runTask("确认已读") {
                                    manager.markIncomingCheckinsRead()
                                    "已标记为已读，历史记录仍保留"
                                }
                            }) { Text("全部报平安已查看") }
                            InlineActionFeedback(actionFeedback["确认已读"])
                        }
                    }
                    3 -> {
                        FeatureHelp("只有主动查询并通过签名验证的位置会被本机加密记录。" +
                            "记录不代表当前实时位置；最多保留最近200条。")
                        if (snapshot.history.safetyZoneEvents.isNotEmpty()) {
                            Text("安全区域历史（仅前台手动定位得出的结果）",
                                style = MaterialTheme.typography.titleMedium)
                            snapshot.history.safetyZoneEvents.takeLast(20).asReversed().forEach { item ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(peerName(item.relationId, item.senderFingerprint) +
                                            if (item.transition == SafetyZonePolicy.Transition.LEFT)
                                                " · 离开安全区域（待人工核实）" else
                                                " · 返回安全区域")
                                        Text("${compactTime(item.capturedAtMillis)} · " +
                                            "距本机设定中心约 ${item.distanceMeters} 米",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (snapshot.history.receivedLocations.isEmpty()) Text("暂无定位记录")
                        snapshot.history.receivedLocations.takeLast(30).asReversed().forEach { fix ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(peerName(fix.relationId, fix.senderFingerprint))
                                    Text("${compactTime(fix.capturedAtMillis)} · " +
                                        "纬度 ${fix.latitudeE7 / 1e7}，经度 ${fix.longitudeE7 / 1e7}")
                                    Text("定位精度约 ${fix.accuracyCm / 100.0} 米")
                                    TextButton(onClick = { openMapLocation(fix) }) {
                                        Text("在所选地图查看这条历史位置")
                                    }
                                }
                            }
                        }
                        if (snapshot.history.receivedLocations.isNotEmpty()) {
                            OutlinedButton(onClick = { exportWarning = "gpx" }) {
                                Text("导出定位轨迹 GPX")
                            }
                            InlineActionFeedback(exportedFeedback)
                            TextButton(onClick = { forgetLocationConfirm = true }) {
                                Text("清空本机已收到的定位记录")
                            }
                        }
                    }
                    4 -> {
                        FeatureHelp("最近的操作结果与失败原因保存在本机；" +
                            "网络超时不能证明对方拒绝。")
                        if (snapshot.history.commandRecords.isEmpty()) Text("暂无操作记录")
                        snapshot.history.commandRecords.takeLast(50).asReversed().forEach { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${item.action} · ${item.outcome}",
                                        color = if (item.outcome == "失败")
                                            MaterialTheme.colorScheme.error else
                                            MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(compactTime(item.time))
                                    Text(item.detail)
                                    if (item.outcome != "完成") {
                                        Text("原因类别：${item.code}",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("导出历史", style = MaterialTheme.typography.titleMedium)
                FeatureHelp("导出前需要你的确认，不包括私钥和手动联系人的电话号码。",
                    label = "导出范围")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportWarning = "json" }) { Text("导出 JSON") }
                    OutlinedButton(onClick = { exportWarning = "csv" }) { Text("导出 CSV") }
                }
                InlineActionFeedback(exportedFeedback)
            }

            if (page == 3) {
                if (settingsSection == SettingsDestination.HOME) {
                    Text("设置", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = settingsFilter,
                        onValueChange = { settingsFilter = it.take(40) },
                        label = { Text("查找设置") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (settingsFilter.isBlank()) {
                        // Direct entry: no category expansion required for an elderly user.
                        SettingsMenuEntry(SettingsDestination.REMOTE_ASSIST) {
                            settingsSectionName = SettingsDestination.REMOTE_ASSIST.name
                        }
                        SettingsMenuLayout.groups.forEach { group ->
                            val expanded = expandedSettingsGroup == group.title
                            SettingsGroupEntry(group, expanded) {
                                expandedSettingsGroup = if (expanded) null else group.title
                            }
                            if (expanded) group.destinations.forEach { target ->
                                SettingsMenuEntry(target) {
                                    if (target == SettingsDestination.PAIRING) navigateTo(1)
                                    else if (target == SettingsDestination.RECORDS) navigateTo(4)
                                    else settingsSectionName = target.name
                                }
                            }
                        }
                        // Always visible at the very bottom, regardless of expanded groups.
                        SettingsMenuEntry(SettingsDestination.ABOUT) {
                            settingsSectionName = SettingsDestination.ABOUT.name
                        }
                    } else {
                        SettingsMenuLayout.search(settingsFilter).forEach { target ->
                                SettingsMenuEntry(target) {
                                    if (target == SettingsDestination.PAIRING) navigateTo(1)
                                    else if (target == SettingsDestination.RECORDS) navigateTo(4)
                                    else settingsSectionName = target.name
                                }
                        }
                        if (SettingsMenuLayout.search(settingsFilter).isEmpty()) {
                            Text("没有找到这项设置，试试“网络”“权限”或“穿戴”。")
                        }
                    }
                } else {
                    TextButton(onClick = { settingsSectionName = SettingsDestination.HOME.name }) {
                        Text("‹ 返回设置")
                    }
                    Text("设置 / ${settingsSection.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(settingsSection.label, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                if (settingsSection == SettingsDestination.QUICK) {
                    Text("这里仅介绍应用功能并带你进入对应页面，不执行任何检测或发送操作。",
                        style = MaterialTheme.typography.bodySmall)
                    SettingsGuideLink("求助与报平安",
                        "进入求助首页；给家人发送演练消息，查看签名回执。") {
                        navigateTo(0)
                    }
                    SettingsGuideLink("连接家人",
                        "进入绑定页面，核对安全码并管理现有帮助关系。") {
                        navigateTo(1)
                    }
                    SettingsGuideLink("应用所需权限与功能检测",
                        "在专门页面检查通知、定位、GPS、电量与网络。") {
                        settingsSectionName = SettingsDestination.SELF_CHECK.name
                    }
                    SettingsGuideLink("网络与通信",
                        "展开网络分类，直接选择私人中继、公共节点或后台连接。") {
                        settingsFilter = ""
                        expandedSettingsGroup = "网络"
                        settingsSectionName = SettingsDestination.HOME.name
                    }
                    SettingsGuideLink("穿戴设备",
                        "进入蓝牙穿戴设备页面，不是家人的手机列表。") {
                        navigateTo(5)
                    }
                    SettingsGuideLink("历史与操作记录",
                        "查看求助、报平安、定位记录和失败原因。") {
                        navigateTo(4)
                    }
                    FeatureHelp("建议首次使用时先检查本机权限、连接家人的手机，" +
                        "再设置家庭中继，最后回到求助页测试。" +
                        "真实紧急情况请使用正常的求救渠道。", label = "推荐设置顺序")
                }
                if (settingsSection == SettingsDestination.SELF_CHECK) {
                    Text("应用所需权限与功能检测", style = MaterialTheme.typography.titleLarge)
                    Text("只有使用对应功能时才需要授权；授权通过也不代表手机一定能联网或定位。",
                        style = MaterialTheme.typography.bodySmall)
                    backgroundReadiness?.let { check ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("后台监听状态", style = MaterialTheme.typography.titleMedium)
                                Text(check.statusLabel)
                                Text("前台服务：" + check.health.serviceState.displayLabel,
                                    style = MaterialTheme.typography.bodySmall)
                                Text("本机调度器：" + check.health.schedulerState.displayLabel,
                                    style = MaterialTheme.typography.bodySmall)
                                Text("本机 TCP 监听：" + check.health.tcpListenerState.displayLabel,
                                    style = MaterialTheme.typography.bodySmall)
                                Text("系统网络：" + check.health.networkState.displayLabel,
                                    style = MaterialTheme.typography.bodySmall)
                                Text("最近恢复触发：" + check.health.lastRecoveryTrigger.displayLabel,
                                    style = MaterialTheme.typography.bodySmall)
                                Text("上次进程退出原因：" + check.previousProcessExit.displayLabel,
                                    style = MaterialTheme.typography.bodySmall)
                                check.previousProcessExit.detail?.takeIf { it.isNotBlank() }?.let {
                                    Text("系统退出说明：" + it, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("远端家属可达性：本阶段不检测；本机服务和网络状态不能证明家属在线。",
                                    style = MaterialTheme.typography.bodySmall)
                                if (check.health.recoveryAttemptCount > 0) Text(
                                    "本次进程自动恢复尝试：${check.health.recoveryAttemptCount}/${BackgroundRecoveryPolicy.MAX_AUTOMATIC_ATTEMPTS}",
                                    style = MaterialTheme.typography.bodySmall)
                                Text("电池优化：" + if (check.batteryExempt) "已获系统豁免" else
                                    "仍受系统省电规则管理", style = MaterialTheme.typography.bodySmall)
                                Text("后台执行：" + if (check.backgroundRestricted)
                                    "系统报告存在后台限制" else "未报告后台受限",
                                    style = MaterialTheme.typography.bodySmall)
                                Text("厂商自启动与后台清理：无法自动判断，需要人工检查。" +
                                    "已开启监听也不代表系统保证始终在线。",
                                    style = MaterialTheme.typography.bodySmall)
                                if (check.medicineAlarmRelevant) Text("服药精确提醒：" +
                                    if (check.exactAlarmAllowed) "精确闹钟可用" else
                                        "尚未允许精确闹钟，提醒可能延后",
                                    style = MaterialTheme.typography.bodySmall)
                                if (check.locationRelevant) {
                                    Text("前台定位权限：" + if (check.locationGranted) "已授予" else "尚未授予",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("后台定位权限：" + if (check.backgroundLocationGranted) "已授予" else "尚未授予",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                if (check.bluetoothRelevant) Text("已开启心率试验所需权限：" +
                                    if (check.bluetoothGranted) "已授予" else "尚未授予",
                                    style = MaterialTheme.typography.bodySmall)
                                if (check.locationRelevant && !check.backgroundLocationGranted) {
                                    OutlinedButton(onClick = {
                                        BackgroundStartupCheck.openAppDetailsSettings(context)
                                    }) { Text("设置后台定位权限") }
                                }
                                if (check.medicineAlarmRelevant && !check.exactAlarmAllowed) {
                                    OutlinedButton(onClick = {
                                        BackgroundStartupCheck.openExactAlarmSettings(context)
                                    }) { Text("设置精确闹钟权限") }
                                }
                                Text(check.serviceDetail, style = MaterialTheme.typography.bodySmall)
                                if (check.phase != BackgroundPhase.RUNNING &&
                                    check.phase != BackgroundPhase.RECOVERING) {
                                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                                        backgroundFeedback = S1LanService.requestStart(context)
                                        startupAuditRevision++
                                    }) { Text(if (check.consent == BackgroundConsent.ENABLED)
                                        "重试启动监听" else "同意并开启后台监听") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { startupAuditRevision++ }) {
                                        Text("重新检查")
                                    }
                                    OutlinedButton(onClick = {
                                        BackgroundStartupCheck.openBackgroundSettings(context)
                                    }) { Text("系统后台设置") }
                                }
                            }
                        }
                    }
                    Text("通知：用于 SOS、报平安和连接提醒，不用于广告。",
                        style = MaterialTheme.typography.titleSmall)
                    Text(S1TestNotifier.status(context), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("检查或设置通知权限") }
                    InlineActionFeedback(noticePermissionFeedback)
                    HorizontalDivider()
                    Text("位置：只在你主动操作，或允许指定家人查看位置时读取 GPS。" +
                        "Android 10 的蓝牙扫描也可能依赖系统位置开关。",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (manager.locationPermissionGranted()) "定位权限：已允许" else
                        "定位权限：尚未允许", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION))
                    }) { Text("检查或申请定位权限") }
                    InlineActionFeedback(locationPermissionFeedback)
                    OutlinedButton(enabled = !busy, onClick = {
                        runTask("本机GPS与位置自检") { manager.selfTestOwnGps() }
                    }) { Text("检测本机 GPS 和当前位置（最多 10 秒）") }
                    InlineActionFeedback(actionFeedback["本机GPS与位置自检"])
                    FeatureHelp("GPS 检测结果只在本机显示，不会自动发送给已绑定的人。")
                    HorizontalDivider()
                    Text("远程协助：每次屏幕共享都需要本人同意并通过安卓系统弹窗确认。",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (RemoteAssistGestureService.enabled())
                        "辅助功能已启用：本机可在本次同意后响应远程点击操作。"
                        else "辅助功能未启用：可能只能共享画面，无法接受对方点击操作。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("检查远程操作辅助功能") }
                    FeatureHelp("安卓屏幕共享许可不能长期预授权；即使辅助功能已开启，" +
                        "未取得这一次共享许可的家人也无法查看或控制你的屏幕。")
                    HorizontalDivider()
                    Text("相机：只用于你主动扫描手机绑定和家庭节点二维码。",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) "相机：已授权" else "相机：尚未授权",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }) { Text("检查或申请扫码相机权限") }
                    InlineActionFeedback(cameraPermissionFeedback)
                    HorizontalDivider()
                    Text("蓝牙与附近设备：用于前台连接你佩戴的手表和心率设备。",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (Build.VERSION.SDK_INT >= 31)
                        "Android 12 及更新系统需要单独授权附近设备。" else
                        "Android 10 的扫描通常依赖位置授权，不会偷偷配对其他设备。",
                        style = MaterialTheme.typography.bodySmall)
                    if (Build.VERSION.SDK_INT >= 31) {
                        OutlinedButton(onClick = {
                            bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT))
                        }) { Text("检查或申请附近设备权限") }
                    }
                    InlineActionFeedback(bluetoothPermissionFeedback)
                    HorizontalDivider()
                    Text("网络：用于局域网发现、公共节点交换连接信息和已绑定手机通信。" +
                        "联网权限在安装时授予，没有单独的系统弹窗。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("后台连接：" + (backgroundReadiness?.statusLabel ?: "正在检测"),
                        style = MaterialTheme.typography.bodySmall)
                    Text("首次需要本人同意；同意后，意外停止会在下次打开应用时尝试恢复。" +
                        "主动关闭后绝不会自动开启；本版不申请后台定位权限。",
                        style = MaterialTheme.typography.bodySmall)
                    if (backgroundReadiness?.consent == BackgroundConsent.ENABLED) {
                        OutlinedButton(onClick = {
                            backgroundFeedback = S1LanService.requestStop(context)
                            startupAuditRevision++
                        }) { Text("停止持续监听") }
                    } else {
                        Button(onClick = {
                            backgroundFeedback = S1LanService.requestStart(context)
                            startupAuditRevision++
                        }) { Text("开启持续监听") }
                    }
                    InlineActionFeedback(backgroundFeedback)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    }) { Text("打开系统应用权限总页") }
                    HorizontalDivider()
                    Text("综合就绪检测", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        onClick = { runTask("设备就绪自检") {
                            val result = manager.readinessReport()
                            handler.post { readinessReport = result }
                            "本机就绪检测完成，请查看下方报告；通过不等于求救一定送达。"
                        } }) { Text("一键检测：通知、网络、定位、后台、电量") }
                    InlineActionFeedback(actionFeedback["设备就绪自检"])
                    if (readinessReport.isNotBlank()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("本机检测报告", style = MaterialTheme.typography.titleMedium)
                                Text(readinessReport, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = {
                                    readinessExport.launch("OpenKinGuard_本机就绪自检.txt")
                                }) { Text("保存本机检测报告") }
                                InlineActionFeedback(exportedFeedback)
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        batteryReport = S4ReadinessDiagnostics.battery(context).label(
                            snapshot.history.batteryWarningPercent,
                            snapshot.history.batteryCriticalPercent)
                    }) { Text("查看当前电量") }
                    InlineActionFeedback(batteryReport)
                }
                if (settingsSection == SettingsDestination.REMOTE_ASSIST) {
                    Text("远程协助由已绑定的家人主动申请。老人每次必须亲自同意，" +
                        "Android 还会再次询问是否共享屏幕；任何时候都能结束。")
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("本机远程协助状态", style = MaterialTheme.typography.titleMedium)
                            Text("远程点击（无障碍）：" +
                                if (RemoteAssistGestureService.enabled()) "已开启" else "未开启")
                            Text("屏幕共享：必须在每一次协助中由本人通过系统弹窗授权，" +
                                "无法在设置页提前永久授权。")
                            if (assistSession?.direction == RemoteAssistConsent.Direction.INCOMING)
                                Text("本机画面状态：$assistTransferStatus",
                                    style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = {
                                assistDiagnosticText = manager.remoteAssistDiagnosticReport()
                                showAssistDiagnostics = true
                            }) { Text("查看 / 复制远程协助诊断") }
                            Text("当前会话：" + when (assistSession?.phase) {
                                RemoteAssistConsent.Phase.WAITING_FOR_PERSON -> "收到申请，等待本人同意"
                                RemoteAssistConsent.Phase.WAITING_FOR_REPLY -> "已发出申请，等待对方同意"
                                RemoteAssistConsent.Phase.AWAITING_SCREEN_PERMISSION ->
                                    "已同意，等待被控制端授权屏幕共享"
                                RemoteAssistConsent.Phase.ACTIVE -> "正在远程协助"
                                null -> "没有正在进行的协助"
                            })
                            if (assistSession?.direction == RemoteAssistConsent.Direction.OUTGOING) {
                                OutlinedButton(onClick = { showAssistControllerPanel = true }) {
                                    Text("打开控制界面")
                                }
                            }
                            if (assistSession != null) {
                                OutlinedButton(onClick = { runTask("结束远程协助") {
                                    manager.stopRemoteAssist()
                                } }) { Text("立即结束当前协助") }
                            }
                        }
                    }
                    Text("老人开启远程点击权限的步骤", style = MaterialTheme.typography.titleMedium)
                    Text("① 打开下面的系统无障碍设置。\n" +
                        "② 在‘已下载的应用’或‘已安装的服务’中寻找" +
                        "‘OpenKinGuard 远程协助（手动开启）’。\n" +
                        "③ 点击该服务，打开开关并确认。\n" +
                        "④ 返回应用，检查上方状态是否为‘已开启’。\n" +
                        "⑤ 家人申请时再点击同意，并在系统弹窗中选择共享屏幕。")
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("打开系统无障碍设置") }
                    Text("此服务不用于自动远程操控；没有正在进行的已授权屏幕共享会话时，" +
                        "远程输入指令一律拒绝。")
                    OutlinedButton(onClick = { navigateTo(0) }) {
                        Text("返回求助页，查看已绑定家人")
                    }
                }
                if (settingsSection == SettingsDestination.DAILY) {
                Text("失联与低电量提醒", style = MaterialTheme.typography.titleMedium)
                Text("失联提醒依据已绑定设备的签名心跳；不代表人员真正遇险。",
                    style = MaterialTheme.typography.bodySmall)
                Text("当前：${snapshot.history.lossWarningMinutes} 分钟提醒，" +
                    "${snapshot.history.lossCriticalMinutes} 分钟严重提醒")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3 to 10, 5 to 15, 10 to 30).forEach { (warning, critical) ->
                        FilterChip(selected = snapshot.history.lossWarningMinutes == warning &&
                            snapshot.history.lossCriticalMinutes == critical,
                            onClick = {
                                manager.setLossThresholds(warning, critical)
                                refresh()
                                lossThresholdFeedback = "已采用 $warning / $critical 分钟失联提醒；" +
                                    "仅监听启动后有新签名数据，才可能及时提醒。"
                            }, label = { Text("$warning / $critical 分钟") })
                    }
                }
                InlineActionFeedback(lossThresholdFeedback)
                Text("低电量提示仅在本机显示，不会擅自向家人发送电量或位置。",
                    style = MaterialTheme.typography.bodySmall)
                Text("当前：${snapshot.history.batteryWarningPercent}% 偏低，" +
                    "${snapshot.history.batteryCriticalPercent}% 严重偏低")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20 to 10, 30 to 10, 30 to 5).forEach { (warning, critical) ->
                        FilterChip(selected = snapshot.history.batteryWarningPercent == warning &&
                            snapshot.history.batteryCriticalPercent == critical,
                            onClick = {
                                manager.setBatteryThresholds(warning, critical)
                                refresh()
                            }, label = { Text("$warning% / $critical%") })
                    }
                }
                }
                if (settingsSection == SettingsDestination.PUBLIC) {
                val signalEnabled = snapshot.history.nostrRelayEnabled
                val signalReady = manager.publicRelayReady()
                val directActive = manager.directActive()
                val advice = PublicP2pDiagnostics.explain(signalEnabled, signalReady,
                    directActive, manager.directStatus())
                FeatureHelp("公共节点帮已绑定的手机交换连接地址，P2P 尝试直接传消息。" +
                    "节点连上不等于两台手机连通，手机可能被运营商 NAT 限制。",
                    label = "公共节点和直连有什么不同")
                Surface(modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("① 公共信令：${if (!signalEnabled) "未开启" else if (signalReady) "节点已确认" else "连接中或未确认"}",
                            style = MaterialTheme.typography.titleMedium)
                        Text("② P2P 直连：${advice.title}",
                            style = MaterialTheme.typography.titleMedium)
                        FeatureHelp(advice.detail, label = "查看当前网络判断依据")
                    }
                }
                Text("公共信令开关 · 两部手机都需要开启",
                    style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        publicNodeFeedback = runCatching {
                            manager.configureNostrRelays(nostrDraft, true)
                            refresh()
                            "正在连接公共节点，请让对方手机也开启。"
                        }.getOrElse { "公共信令启用失败：${it.message ?: "节点地址无效"}" }
                    }) { Text(if (signalEnabled) "保存并重连" else "开启公共信令") }
                    OutlinedButton(enabled = !busy && signalEnabled, onClick = {
                        manager.stopPublicDirectTest()
                        manager.configureNostrRelays(nostrDraft, false)
                        refresh()
                        publicNodeFeedback = "公共信令和 P2P 已关闭，已绑定家人不会被删除。"
                    }) { Text("关闭信令") }
                }
                InlineActionFeedback(publicNodeFeedback)
                HorizontalDivider()
                Text("P2P 直连测试", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && signalReady && !directActive, onClick = {
                        publicP2pFeedback = runCatching {
                            manager.startPublicDirectTest()
                            manager.probeAsync()
                            refresh()
                            "已开始尝试直连。两部手机都要开启，稍后查看状态。"
                        }.getOrElse { "启动失败：${it.message ?: "未知原因"}" }
                    }) { Text("开始 P2P 测试") }
                    OutlinedButton(enabled = directActive, onClick = {
                        manager.stopPublicDirectTest()
                        refresh()
                        publicP2pFeedback = "P2P 测试已停止，UDP 端口已关闭。"
                    }) { Text("停止测试") }
                }
                InlineActionFeedback(publicP2pFeedback)
                FeatureHelp("两部手机必须至少有一个共同、可访问的公共节点，" +
                    "并且双方都开启 P2P 测试。" +
                    "开始后自动检测地址、交换候选并尝试签名握手；" +
                    "检测到 IP 不等于真正连通。", label = "P2P 测试说明")
                TextButton(onClick = {
                    showPublicNodeDiagnostics = !showPublicNodeDiagnostics
                }) {
                    Text(if (showPublicNodeDiagnostics) "收起高级设置 ↑"
                        else "高级：网络诊断与节点地址 ↓")
                }
                if (showPublicNodeDiagnostics) {
                HorizontalDivider()
                Text("网络诊断", style = MaterialTheme.typography.titleMedium)
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                    val includeIp = showPublicAddresses
                    runTask("公共节点完整诊断", inlineResult = { result ->
                        publicReport = result
                        showPublicReport = true
                    }) {
                        val stun = runCatching { manager.selfTestPublicUdpMapping() }
                            .getOrElse { "STUN 失败：${it.message ?: it.javaClass.simpleName}" }
                        PublicP2pDiagnostics.build(context,
                            manager.snapshot().history.nostrRelayEnabled,
                            manager.publicRelayReady(), manager.directActive(),
                            manager.directStatus(), manager.publicRelayStatus(),
                            manager.relations().size,
                            manager.snapshot().directVerifiedRelations.size,
                            stun, includeIp)
                    }
                }) { Text("生成完整诊断（包括 NAT 检测）") }
                if (publicReport.isNotBlank()) {
                    TextButton(onClick = { showPublicReport = !showPublicReport }) {
                        Text(if (showPublicReport) "收起诊断报告" else "展开上次诊断报告")
                    }
                    if (showPublicReport) {
                        Surface(shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(publicReport, Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                "OpenKinGuard 连接诊断", publicReport))
                            publicReportFeedback = "已复制诊断；分享前请检查是否包含网络地址。"
                        }) { Text("复制诊断") }
                        OutlinedButton(onClick = {
                            publicReportExport.launch("OpenKinGuard_公共节点诊断.txt")
                        }) { Text("保存 TXT") }
                    }
                }
                InlineActionFeedback(publicReportFeedback)
                    Text("节点连接、订阅与公告", style = MaterialTheme.typography.titleSmall)
                    Text(manager.publicRelayStatus(), style = MaterialTheme.typography.bodySmall)
                    Text("UDP 端口与双端候选", style = MaterialTheme.typography.titleSmall)
                    Text(manager.directStatus(), style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("诊断报告包含 STUN 公网 IP",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        Switch(checked = showPublicAddresses, onCheckedChange = {
                            showPublicAddresses = it
                            publicReportFeedback = "选项已修改，请重新生成报告后生效。"
                        })
                    }
                    Text("更换节点 · 每行一个，最多 3 个。双方需至少有一个相同且可用的节点。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        nostrDraft = S2NostrTransport.DEFAULT_RELAYS
                        publicNodeFeedback = "已填入候选；点击上方“保存并重连”才会生效。"
                    }) { Text("填入默认候选节点") }
                    OutlinedButton(onClick = {
                        nostrDraft = "wss://relay.primal.net\nwss://nos.lol"
                        publicNodeFeedback = "已填入最初试验节点，请保存并重连。"
                    }) { Text("填入早期测试节点") }
                    OutlinedTextField(value = nostrDraft, onValueChange = { nostrDraft = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = false,
                        minLines = 2, maxLines = 3,
                        label = { Text("公共 Nostr 节点地址") },
                        placeholder = { Text(S2NostrTransport.DEFAULT_RELAYS) })
                    FeatureHelp("节点可观察连接时间和公开身份信息；STUN 服务器可以看到公网 IP。" +
                        "公共节点不转发求助正文，直连失败可能由 CGNAT、网络防火墙或网络切换导致。" +
                        "即使 P2P 握手成功，业务是否送达仍要看接收方签名回执。",
                        label = "隐私和失败原因")
                }
                }
                if (settingsSection == SettingsDestination.PRIVATE) {
                Text("扫码即可添加或重新验证家庭节点。",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !relayJoinInProgress, onClick = {
                        relayJoinScanner.launch(ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setCaptureActivity(PortraitQrCaptureActivity::class.java)
                            .setOrientationLocked(true)
                            .setPrompt("扫描自己路由器上的 OpenKinGuard 加入二维码")
                            .setBeepEnabled(false))
                    }) { Text("扫描家庭节点二维码") }
                    if (relayJoinInProgress) {
                        OutlinedButton(onClick = {
                            relayJoinAttempt += 1
                            relayJoinSocket?.cancel()
                            relayJoinSocket = null
                            relayJoinInProgress = false
                            privateJoinFeedback = "已取消手机端的等待；路由器仍可能保留以前的申请或批准。"
                        }) { Text("取消等待") }
                    }
                }
                InlineActionFeedback(privateJoinFeedback)
                val routerRevokedThisDevice = snapshot.relayStatus.contains("路由器端未绑定您")
                val relayBanner = FamilyConnectionPresentation.banner(
                    saved = snapshot.history.relayEndpoint.isNotBlank(),
                    enabled = snapshot.history.relayEnabled,
                    listening = snapshot.listening,
                    connected = snapshot.relayConnected,
                    revoked = routerRevokedThisDevice)
                Surface(shape = MaterialTheme.shapes.large,
                    color = if (routerRevokedThisDevice) MaterialTheme.colorScheme.errorContainer
                        else if (snapshot.relayConnected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (relayBanner.attempting) CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(relayBanner.title, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(relayBanner.instruction, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            showPrivateRelayDetails = !showPrivateRelayDetails
                        }) { Text(if (showPrivateRelayDetails) "收起连接详情 ↑"
                            else "高级：查看连接详情 ↓") }
                        if (showPrivateRelayDetails) Text(snapshot.relayStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val savedPrivateRelays = snapshot.history.relayEndpoint.lines()
                    .filter { it.isNotBlank() }
                if (savedPrivateRelays.isNotEmpty()) {
                    Text("已保存的家庭节点", style = MaterialTheme.typography.titleMedium)
                    savedPrivateRelays.forEachIndexed { index, address ->
                        val remark = snapshot.history.relayRemarks[address].orEmpty()
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(remark.ifBlank { "家庭节点 ${index + 1}" },
                                    style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = {
                                    editingRelayRemark = if (editingRelayRemark == address) null else address
                                    relayRemarkDraft = remark
                                }) { Text(if (editingRelayRemark == address) "收起备注" else "修改节点备注") }
                                if (editingRelayRemark == address) {
                                    OutlinedTextField(value = relayRemarkDraft,
                                        onValueChange = { relayRemarkDraft = it.take(32) },
                                        label = { Text("例如：西安家中路由器") }, singleLine = true)
                                    Button(onClick = {
                                        relayRemarkFeedback = runCatching {
                                            manager.setRelayRemark(address, relayRemarkDraft)
                                            editingRelayRemark = null
                                            refresh()
                                            "节点备注已保存在本机"
                                        }.getOrElse { "保存失败：${it.message ?: "未知错误"}" }
                                    }) { Text("保存备注") }
                                }
                            }
                        }
                    }
                    InlineActionFeedback(relayRemarkFeedback)
                }
                FeatureHelp("连接状态不代表家人收到求助；" +
                    "真实 SOS/报平安必须等待接收设备的签名回执。" +
                    "如果联系不到家人，请检查双方绑定、网络和是否设置了相同的节点。",
                    label = "连接成功与消息送达有什么区别")
                TextButton(onClick = { showRelayManual = !showRelayManual }) {
                    Text(if (showRelayManual) "收起高级设置 ↑" else "高级：手动节点与离线暂存 ↓")
                }
                if (showRelayManual) {
                FeatureHelp("可用自己的电脑或支持 Python 的 OpenWrt 路由器搭建节点。" +
                    "每行一个地址，最多三个，两台手机需至少有一个共同节点。" +
                    "免域名模式必须核对完整64位 TLS 公钥指纹，不能只相信陌生人的二维码。")
                OutlinedTextField(value = relayDraft, onValueChange = { relayDraft = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 2,
                    maxLines = 3, label = { Text("中继 WSS 地址（最多3行）") },
                    placeholder = { Text("wss://[2409:xxxx::1]:9443/relay#spki=64位指纹") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        privateConfigFeedback = runCatching {
                            manager.configureRelay(relayDraft, true)
                            refresh()
                            "已保存并开始连接；两台手机至少要有一个相同的可用中继节点"
                        }.getOrElse { "启用失败：${it.message ?: it.javaClass.simpleName}" }
                    }) { Text("保存并启用") }
                    OutlinedButton(enabled = !busy, onClick = {
                        manager.configureRelay(relayDraft, false)
                        refresh()
                        privateConfigFeedback = "已关闭本机公网中继；原有局域网功能仍可用"
                    }) { Text("关闭公网中继") }
                }
                InlineActionFeedback(privateConfigFeedback)
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("允许暂存离线测试 SOS / 报平安（默认关闭）",
                        modifier = Modifier.weight(1f))
                    Switch(checked = snapshot.history.relayOfflineQueueEnabled,
                        onCheckedChange = { enabled ->
                            manager.setRelayOfflineQueueEnabled(enabled)
                            refresh()
                            privateConfigFeedback = if (enabled)
                                "已主动允许短期 RAM 暂存；仅代表排队，不代表对方收到"
                            else "已关闭新的离线暂存；中继可能仍持有之前未过期的内存消息"
                        })
                }
                InlineActionFeedback(privateConfigFeedback)
                FeatureHelp("私人中继安全说明：必须使用系统可信 TLS 证书，或显式核对并固定私有节点 SPKI 公钥指纹（均为 wss://）。" +
                    "单凭 IP 地址绝不可跳过 TLS 身份校验。节点可知道设备连接状态和收发时间，" +
                    "但无法伪造设备签名；走此私人中继的普通 SOS/报平安消息仍不是端到端加密，" +
                    "精确 GPS 坐标继续采用单次端到端加密。对方离线时，测试 SOS/报平安可在" +
                    "中继内存中短暂排队（最长5分钟，非送达回执，节点重启即丢失）。" +
                    "手机后台仍受系统省电策略限制。",
                    label = "安全与离线暂存说明")
                }
                }
                if (settingsSection == SettingsDestination.MAP) {
                HorizontalDivider()
                Text("国内默认地图", style = MaterialTheme.typography.titleLarge)
                Text("查看定位时可直接选 Google 地图或国内地图；这里仅设置国内优先使用高德还是百度。" +
                    "原始定位按 WGS84 坐标交接，国内地图负责正确转换；仅在你主动点击时传给第三方地图。")
                val availableMaps = remember(page, mapAppsRevision) {
                    ThirdPartyMapLauncher.availableDomestic(context)
                }
                val selectedMap = availableMaps.firstOrNull {
                    it.id == snapshot.history.preferredMapHandler
                } ?: availableMaps.first()
                Box {
                    OutlinedButton(onClick = { mapPickerExpanded = true }) {
                        Text("国内地图：${selectedMap.title}")
                    }
                    DropdownMenu(expanded = mapPickerExpanded,
                        onDismissRequest = { mapPickerExpanded = false }) {
                        availableMaps.forEach { option ->
                            DropdownMenuItem(text = { Text(option.title) }, onClick = {
                                manager.setPreferredMapHandler(option.id)
                                mapPickerExpanded = false
                                refresh()
                            })
                        }
                    }
                }
                TextButton(onClick = { mapAppsRevision++ }) { Text("重新检测已安装地图") }
                }
                if (settingsSection == SettingsDestination.BACKGROUND) {
                HorizontalDivider()
                Text("后台连接", style = MaterialTheme.typography.titleLarge)
                Text(backgroundReadiness?.statusLabel ?: "正在检测后台监听")
                backgroundReadiness?.let { check ->
                    Text("前台服务：" + check.health.serviceState.displayLabel,
                        style = MaterialTheme.typography.bodySmall)
                    Text("本机调度器：" + check.health.schedulerState.displayLabel,
                        style = MaterialTheme.typography.bodySmall)
                    Text("本机 TCP 监听：" + check.health.tcpListenerState.displayLabel,
                        style = MaterialTheme.typography.bodySmall)
                    Text("系统网络：" + check.health.networkState.displayLabel,
                        style = MaterialTheme.typography.bodySmall)
                    Text("最近恢复触发：" + check.health.lastRecoveryTrigger.displayLabel,
                        style = MaterialTheme.typography.bodySmall)
                    Text("上次进程退出原因：" + check.previousProcessExit.displayLabel,
                        style = MaterialTheme.typography.bodySmall)
                    check.previousProcessExit.detail?.takeIf { it.isNotBlank() }?.let {
                        Text("系统退出说明：" + it, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("家属设备是否可达：本阶段不检测，不能由本机状态推断。",
                        style = MaterialTheme.typography.bodySmall)
                    check.health.lastErrorDetail?.takeIf { it.isNotBlank() }?.let {
                        Text("最近错误：" + it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                FeatureHelp("手动开启后，可在离开本应用时继续尝试收发签名消息，并按你设置的" +
                    "${snapshot.history.lossWarningMinutes}分钟、${snapshot.history.lossCriticalMinutes}分钟检测失联。" +
                    "此功能不是实时定位或救援保障；系统省电限制、断网或强制停止仍可能中断服务。")
                if (backgroundReadiness?.consent == BackgroundConsent.ENABLED) {
                    OutlinedButton(onClick = {
                        backgroundFeedback = S1LanService.requestStop(context)
                        startupAuditRevision++
                    }) { Text("停止持续监听") }
                } else {
                    Button(onClick = {
                        backgroundFeedback = S1LanService.requestStart(context)
                        startupAuditRevision++
                    }) { Text("开启持续监听") }
                }
                InlineActionFeedback(backgroundFeedback)
                }
                if (settingsSection == SettingsDestination.NOTICES) {
                HorizontalDivider()
                Text("通知", style = MaterialTheme.typography.titleLarge)
                Text(S1TestNotifier.status(context))
                Text(S1TestNotifier.checkinStatus(context))
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                    Button(onClick = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("申请通知权限") }
                    InlineActionFeedback(noticePermissionFeedback)
                }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("系统通知设置") }
                TextButton(onClick = {
                    noticeFeedback = S1TestNotifier.show(context, "本机通知测试，不是真实求救")
                        .ifEmpty { "已提交测试通知，请下拉通知栏查看" }
                }) { Text("发送本机通知测试") }
                InlineActionFeedback(noticeFeedback)
                FeatureHelp("检查通知栏是否出现本机测试通知。此测试不向家人发送消息。")
                TextButton(onClick = {
                    checkinNoticeFeedback = S1TestNotifier.showCheckin(context, "本机报平安横幅测试")
                        .ifEmpty { "已提交高优先级报平安通知；若未弹窗，请检查系统的浮动通知设置" }
                }) { Text("测试报平安悬浮提醒") }
                InlineActionFeedback(checkinNoticeFeedback)
                FeatureHelp("如果没有浮动横幅，检查系统通知权限、弹出通知及省电策略；" +
                    "本机通知测试不会证明另一台设备已收到报平安。")
                }
                if (settingsSection == SettingsDestination.FIND) {
                HorizontalDivider()
                Text("厂商查找设备", style = MaterialTheme.typography.titleLarge)
                Text("官方网页备用入口。需设备已启用相应查找服务，并由机主在浏览器登录；" +
                    "这不代表 OpenKinGuard 自动拥有厂商定位权限。")
                OutlinedButton(onClick = { showFindPortals = !showFindPortals }) {
                    Text(if (showFindPortals) "收起官方查找入口" else "展开厂商查找入口")
                }
                if (showFindPortals) {
                    OfficialDeviceFindPortals.entries.forEach { portal ->
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(portal.name)
                                Text(portal.applicability,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { selectedFindPortal = portal }) { Text("进入") }
                        }
                        HorizontalDivider()
                    }
                }
                InlineActionFeedback(findPortalFeedback)
                }
                if (settingsSection == SettingsDestination.MEDICAL) {
                HorizontalDivider()
                Text("我的病历 · 本机文件夹", style = MaterialTheme.typography.titleLarge)
                FeatureHelp("你可以自己选择一个病历文件夹，里面可以放 PDF、照片、文字等任何文件。" +
                    "本应用只保存该文件夹的本机访问入口，不复制、不自动上传文件；" +
                    "文件最终由你选用的系统查看器打开。请保护好手机锁屏和病历隐私。")
                if (medicalTree.isBlank()) {
                    Button(onClick = { medicalFolderPicker.launch(null) }) {
                        Text("选择我的病历文件夹")
                    }
                    InlineActionFeedback(medicalFeedback)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            medicalPath.clear()
                            medicalFolderOpen = !medicalFolderOpen
                            medicalReload++
                        }) { Text(if (medicalFolderOpen) "收起病历" else "打开我的病历") }
                        OutlinedButton(onClick = { medicalFolderPicker.launch(null) }) {
                            Text("更换文件夹")
                        }
                    }
                    InlineActionFeedback(medicalFeedback)
                    TextButton(onClick = { removeMedicalFolderConfirm = true }) {
                        Text("移除文件夹入口（不会删除文件）")
                    }
                    if (medicalFolderOpen) {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("文件夹：${medicalFolderName.ifBlank { "正在读取…" }}")
                                if (medicalPath.isNotEmpty()) {
                                    Text("当前位置：${medicalPath.joinToString(" / ") { it.second }}",
                                        style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { medicalPath.removeAt(medicalPath.lastIndex) }) {
                                        Text("返回上级文件夹")
                                    }
                                }
                                if (medicalFolderLoading) LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth())
                                if (medicalLoadError.isNotBlank()) {
                                    Text("文件夹读取失败：$medicalLoadError",
                                        color = MaterialTheme.colorScheme.error)
                                    Text("可尝试重新选择文件夹以恢复系统授权。")
                                } else if (!medicalFolderLoading) {
                                    if (medicalFiles.isEmpty()) Text("当前文件夹为空")
                                    InlineActionFeedback(medicalFeedback)
                                    medicalFiles.forEach { item ->
                                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                            if (item.isFolder) medicalPath.add(item.documentId to item.name)
                                            else medicalFeedback = runCatching {
                                                PersonalMedicalFolder.openFile(context,
                                                    Uri.parse(medicalTree), item)
                                                "已交给系统查看器打开：${item.name}"
                                            }.getOrElse {
                                                "无法打开文件：${CommandFailureExplainer.explain(it).message}"
                                            }
                                        }) {
                                            Text("${if (item.isFolder) "📁" else "📄"} ${item.name}")
                                        }
                                    }
                                    if (medicalFiles.size >= 250) Text(
                                        "文件较多，当前最多展示250项；可以使用系统文件管理器整理。")
                                }
                            }
                        }
                    }
                }
                }
                if (settingsSection == SettingsDestination.HISTORY) {
                HorizontalDivider()
                Text("历史保留", style = MaterialTheme.typography.titleLarge)
                Text("记录在本机加密保存；已结束、被替代或超时归档的历史按保留天数清理。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (days in listOf(7, 30, 90)) {
                        FilterChip(selected = snapshot.history.retentionDays == days,
                            onClick = { manager.setRetention(days); refresh() },
                            label = { Text("$days 天") })
                    }
                }
                }
                if (settingsSection == SettingsDestination.CONTACTS) {
                HorizontalDivider()
                Text("手动联系人", style = MaterialTheme.typography.titleLarge)
                Text("不会自动拨号或扣费；只有你点击系统拨号或短信编辑器才会执行。")
                snapshot.history.contacts.forEach { contact ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${contact.name} · ${contact.phone}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_DIAL,
                                        Uri.parse("tel:${contact.phone}")))
                                }) { Text("拨号") }
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_SENDTO,
                                        Uri.parse("smsto:${contact.phone}")))
                                }) { Text("短信") }
                                TextButton(onClick = {
                                    manager.removeContact(contact.phone); refresh()
                                }) { Text("移除") }
                            }
                        }
                    }
                }
                OutlinedTextField(value = contactName,
                    onValueChange = { contactName = it.take(40) },
                    label = { Text("联系人称呼") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = contactNumber,
                    onValueChange = { contactNumber = it.take(25) },
                    label = { Text("电话号码") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true)
                OutlinedButton(enabled = !busy, onClick = {
                    runTask("保存联系人") {
                        manager.addContact(contactName, contactNumber)
                        handler.post { contactName = ""; contactNumber = "" }
                        "联系人已保存在本机"
                    }
                }) { Text("添加联系人") }
                InlineActionFeedback(actionFeedback["保存联系人"])
                }
                if (settingsSection == SettingsDestination.ADVANCED) {
                HorizontalDivider()
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "收起高级信息" else "高级信息 · 身份与握手日志")
                }
                if (showAdvanced) {
                    OutlinedButton(onClick = {
                        identityInfo = runCatching {
                            val keys = AndroidDeviceKeyStore()
                            val identity = keys.createOrLoad()
                            if (keys.selfTest()) "本机身份有效 · 指纹 ${identity.fingerprint}"
                            else "身份自检失败"
                        }.getOrElse { "自检失败：${it.javaClass.simpleName}" }
                    }) { Text("检查本机签名身份") }
                    if (identityInfo.isNotBlank()) Text(identityInfo)
                    Text("局域网：${if (snapshot.listening) "正在监听" else "未启动"}；" +
                        "发现 ${snapshot.nodes.size} 台设备；" +
                        "已认证 ${snapshot.verifiedRelations.size} 条关系")
                    if (snapshot.discoveryError.isNotBlank()) Text(snapshot.discoveryError)
                    snapshot.nodes.forEach { node ->
                        Text("发现：${node.advertisedNickname.ifBlank { node.serviceName }} · " +
                            "${node.address}:${node.port}（未验证身份时昵称不可信）")
                    }
                    OutlinedButton(enabled = snapshot.listening,
                        onClick = { manager.probeAsync(); refresh() }) {
                        Text("重新验证设备")
                    }
                    Text("最近握手记录（本机加密保存最多120条）")
                    snapshot.history.handshakeLog.takeLast(40).asReversed().forEach { entry ->
                        Text("${compactTime(entry.time)} · ${entry.direction} · " +
                            "${entry.stage} · ${entry.result} · ${entry.detail}")
                    }
                    snapshot.diagnostics.takeLast(5).forEach { Text(it) }
                    Text("握手记录含诊断信息，不含签名私钥；分享时请注意隐私。")
                }
                }
                if (settingsSection == SettingsDestination.THEMES) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("简洁守护首页", style = MaterialTheme.typography.titleMedium)
                            Text("首页仅显示核心求助与在线家人操作，其他功能留在设置。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = snapshot.history.simpleHomeEnabled,
                            onCheckedChange = { enabled ->
                                manager.setSimpleHome(enabled)
                                refresh()
                            })
                    }
                    ThemeSettingsSection(themes)
                }
                if (settingsSection == SettingsDestination.WIDGETS) {
                    WidgetSettingsSection()
                }
                if (settingsSection == SettingsDestination.WEARABLE_ALERTS) {
                    HealthDevicesSection(healthMonitor, WearableSectionMode.ALERTS)
                }
                if (settingsSection == SettingsDestination.WEARABLE_HISTORY) {
                    HealthDevicesSection(healthMonitor, WearableSectionMode.HISTORY)
                }
                if (settingsSection == SettingsDestination.WEARABLE_TECH) {
                    HealthDevicesSection(healthMonitor, WearableSectionMode.TECHNICAL)
                }
                if (settingsSection == SettingsDestination.ABOUT) {
                    val installedVersion = remember(context.packageName) {
                        AppVersionInfo.installed(context)
                    }
                    Text("OpenKinGuard · 亲友守护", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("当前安装版本：${installedVersion.name}",
                        style = MaterialTheme.typography.titleMedium)
                    Text("内部版本号：${installedVersion.code} · Android 版本信息来自实际安装的 APK",
                        style = MaterialTheme.typography.bodySmall)
                    Text("一款由 tigao 发起的公益开源 Android 家庭安全互助软件。" +
                        "我们希望缓解老人独居、紧急情况下求助困难、家属无法及时了解平安状态，" +
                        "以及老人遇到智能手机操作问题却得不到远程帮助等现实问题。")
                    Text("开发者：tigao", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/tigaotg")))
                        }
                    }) { Text("GitHub：github.com/tigaotg") }
                    Text("当前主要功能", style = MaterialTheme.typography.titleMedium)
                    Text("• 家人与亲友互助关系绑定，可在同一个应用中帮助他人或接受帮助。\n" +
                        "• SOS 紧急求助和报平安，展示设备在线状态、近期求助记录。\n" +
                        "• 可授权的位置查询与地图打开、本机健康设备和通知检查。\n" +
                        "• 支持局域网及公网点对点连接，并可选择私人中继或公共节点。\n" +
                        "• 远程协助需要被帮助者逐次明确同意，屏幕分享另由安卓系统授权。")
                    Text("本项目坚持开源、透明和用户自主授权，不要求集中注册账号。" +
                        "客户端以 GPLv3 发布，家庭中继以 AGPLv3 发布。" +
                        "这是测试中的安全辅助工具，不能替代急救电话或专业医疗服务。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (settingsSection != SettingsDestination.HOME) {
                FeatureHelp("当前版本已通过私人中继扫码与部分公网真机连接验证，" +
                    "但尚未验证长期后台可靠性；" +
                    "GPS 请求需要对方授权及应用前台。尚未实现可靠后台定位、" +
                    "后台健康异常自动求救或自动短信电话。设备页仅支持前台 BLE 测量。" +
                    "不可代替真实求救渠道。", label = "测试版安全说明")
                }
            }
        }
        }
    }

    pendingHistoricalMapFix?.let { historicalFix ->
        AlertDialog(onDismissRequest = {
            pendingHistoricalMapFix = null
            pendingHistoricalMapDomestic = null
        },
            title = { Text("这是历史定位，不是实时位置") },
            text = { Text("采集时间：${compactTime(historicalFix.capturedAtMillis)}。" +
                "位置可能已经改变；如继续，将把这条旧坐标交给所选地图软件，" +
                "地图可能通过网络处理坐标。") },
            confirmButton = { TextButton(onClick = {
                val domestic = pendingHistoricalMapDomestic
                pendingHistoricalMapFix = null
                pendingHistoricalMapDomestic = null
                openMapLocation(historicalFix, domestic = domestic, confirmedHistorical = true)
            }) { Text("仍要查看历史位置") } },
            dismissButton = { TextButton(onClick = {
                pendingHistoricalMapFix = null
                pendingHistoricalMapDomestic = null
            }) {
                Text("取消")
            } })
    }

    mapChoiceFix?.let { fix ->
        AlertDialog(onDismissRequest = { mapChoiceFix = null },
            title = { Text("使用哪个地图查看？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只在你选择后打开地图。Google 地图使用原始 GPS 坐标；" +
                    "国内地图会按坐标系规则处理，同一个位置不直接混用坐标。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    onClick = { launchMapLocation(fix, domestic = false) }) {
                    Text("打开 Google 地图")
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(),
                    onClick = { launchMapLocation(fix, domestic = true) }) {
                    Text("打开国内地图（高德 / 百度）")
                }
            } },
            confirmButton = { TextButton(onClick = { mapChoiceFix = null }) { Text("取消") } })
    }

    if (confirmSos) AlertDialog(onDismissRequest = { confirmSos = false },
        title = { Text("发送测试 SOS？") },
        text = { Text("仅向已绑定且在线的救助者发送局域网演练消息。" +
            "不是真实求救，不会自动拨号或发送收费短信。") },
        confirmButton = { TextButton(onClick = {
            confirmSos = false
            runTask("正在发送测试求助", inlineResult = { sosFeedback = it }) {
                val id = manager.createOwnSos()
                S1TestNotifier.show(context, "已在本机创建测试 SOS")
                val errors = manager.deliverPending()
                if (errors.isEmpty()) "测试SOS ${id.take(8)} 已创建；请查看签名送达回执"
                else "求助已保存在本机，仍有待确认送达：${errors.joinToString("；").take(320)}"
            }
        }) { Text("确认测试") } },
        dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("取消") } })

    if (confirmCheckin) AlertDialog(onDismissRequest = { confirmCheckin = false },
        title = { Text("从桌面发送报平安？") },
        text = { Text("将向已建立帮助关系的救助者发送一条演练报平安消息。" +
            "消息是否送达以对方的签名回执为准；离线时不保证送达。") },
        confirmButton = { TextButton(onClick = {
            confirmCheckin = false
            runTask("正在发送桌面报平安", inlineResult = { checkinFeedback = it }) {
                val id = manager.createOwnCheckin()
                val errors = manager.deliverPending()
                if (errors.isEmpty()) "报平安 ${id.take(8)} 已创建；请在记录中核对送达回执"
                else "报平安已保存在本机，仍待确认送达：${errors.joinToString("；").take(300)}"
            }
        }) { Text("确认报平安") } },
        dismissButton = { TextButton(onClick = { confirmCheckin = false }) {
            Text("取消")
        } })

    closing?.let { id ->
        AlertDialog(onDismissRequest = { closing = null },
            title = { Text("确认结束演练？") },
            text = { Text("结案后将尝试通知在线救助者；离线设备可能稍后才能同步。") },
            confirmButton = { TextButton(onClick = {
                closing = null
                runTask("结束演练", inlineResult = { sosFeedback = it }) {
                    val errors = manager.closeOwn(id)
                    if (errors.isEmpty()) "本机已结案，请查看远端签名回执"
                    else "本机已结案，尚有未确认同步：${errors.joinToString("；").take(320)}"
                }
            }) { Text("确认结束") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("返回") } })
    }

    revoking?.let { id ->
        AlertDialog(onDismissRequest = { revoking = null },
            title = { Text("撤销这条帮助关系？") },
            text = { Text("这只会立即撤销本机对该方向的信任，不能自动删除对方手机里的旧记录。" +
                "双向互助的另一个方向不会被撤销。") },
            confirmButton = { TextButton(onClick = {
                revoking = null
                runTask("撤销本机关系") {
                    manager.revokeRelation(id)
                    "本机已撤销所选关系；对方设备尚未同步撤销"
                }
            }) { Text("确认撤销") } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("取消") } })
    }

    exportWarning?.let { kind ->
        AlertDialog(onDismissRequest = { exportWarning = null },
            title = { Text("确认导出历史？") },
            text = { Text("导出文件是未加密的明文，可能包含设备指纹、求助时间及精确地理位置。" +
                "只有你在系统文件选择器中确认位置后才会保存。") },
            confirmButton = { TextButton(onClick = {
                exportWarning = null
                val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
                    .format(Date())
                when (kind) {
                    "json" -> jsonExport.launch("OpenKinGuard_历史_$date.json")
                    "gpx" -> gpxExport.launch("OpenKinGuard_定位轨迹_$date.gpx")
                    else -> csvExport.launch("OpenKinGuard_历史_$date.csv")
                }
            }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = { exportWarning = null }) { Text("取消") } })
    }

    selectedFindPortal?.let { portal ->
        AlertDialog(onDismissRequest = { selectedFindPortal = null },
            title = { Text("打开${portal.name}？") },
            text = { Text("即将交给系统浏览器访问厂商官方站点：${portal.url}\n" +
                "本应用不会获取或保存你的厂商账号密码。" +
                "如网页提供锁定、抹除设备等操作，请先确认后果。") },
            confirmButton = { TextButton(onClick = {
                selectedFindPortal = null
                findPortalFeedback = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(portal.url))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                    "已请求系统浏览器打开${portal.name}，请在浏览器中确认网站及账号"
                }.getOrElse { error ->
                    val failure = CommandFailureExplainer.explain(error)
                    "无法打开浏览器：${failure.message}"
                }
            }) { Text("打开浏览器") } },
            dismissButton = { TextButton(onClick = { selectedFindPortal = null }) {
                Text("取消")
            } })
    }

    keepLegacyRelation?.let { peer ->
        AlertDialog(onDismissRequest = { keepLegacyRelation = null },
            title = { Text("确认整理重复绑定？") },
            text = { Text("保留指纹 ${peer.peerFingerprint.take(12)} 在" +
                "${if (peer.role == PairingRole.SUBJECT) "我的救助者" else "我的被救助者"}" +
                "方向的关系 ${peer.relationId.take(8)}。其他同方向重复关系将停用，" +
                "旧公钥记录仍保留，但不会自动修改对方手机；若两机使用了不同关系，" +
                "需要之后在两机上重新核对。") },
            confirmButton = { TextButton(onClick = {
                keepLegacyRelation = null
                runTask("整理重复关系") {
                    val count = manager.keepOneLegacyRelation(peer.relationId)
                    "已保留所选关系并停用 $count 条旧重复关系；对方设备尚未同步"
                }
            }) { Text("确认保留这条") } },
            dismissButton = { TextButton(onClick = { keepLegacyRelation = null }) {
                Text("取消")
            } })
    }

    if (removeMedicalFolderConfirm) AlertDialog(
        onDismissRequest = { removeMedicalFolderConfirm = false },
        title = { Text("移除病历文件夹入口？") },
        text = { Text("只移除 OpenKinGuard 中保存的文件夹访问入口并撤销读取授权。" +
            "原文件、文件夹及其他应用中的内容完全不删除。") },
        confirmButton = { TextButton(onClick = {
            removeMedicalFolderConfirm = false
            val prior = snapshot.history.medicalFolderTreeUri
            medicalFeedback = runCatching {
                manager.setMedicalFolderTreeUri(null)
                if (prior.isNotBlank()) runCatching {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(prior),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                medicalFolderOpen = false
                medicalPath.clear()
                medicalFiles = emptyList()
                "已移除病历文件夹入口，原文件未修改"
            }.getOrElse { "移除入口失败：${CommandFailureExplainer.explain(it).message}" }
            snapshot = manager.snapshot()
        }) { Text("确认移除入口") } },
        dismissButton = { TextButton(onClick = { removeMedicalFolderConfirm = false }) {
            Text("取消")
        } })

    if (forgetLocationConfirm) AlertDialog(
        onDismissRequest = { forgetLocationConfirm = false },
        title = { Text("清空本机定位记录？") },
        text = { Text("仅删除本机加密保存的远端位置历史，不影响双方帮助关系，" +
            "也不会删除对方设备的记录。操作无法恢复。") },
        confirmButton = { TextButton(onClick = {
            forgetLocationConfirm = false
            runTask("清空定位记录") {
                manager.forgetReceivedLocations()
                "本机的定位历史已清空"
            }
        }) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = { forgetLocationConfirm = false }) {
            Text("取消")
        } })
}

private fun compactTime(time: Long): String = DateFormat.getDateTimeInstance().format(Date(time))

private fun formatLocation(fix: S3LocationWire.Fix): String =
    String.format(java.util.Locale.ROOT, "纬度 %.6f，经度 %.6f · %s · 误差约 %.1f 米 · %s。%s",
        fix.latitude, fix.longitude,
        if (fix.provider == "G") "GPS卫星定位" else "网络定位",
        fix.accuracyMeters, compactTime(fix.capturedAtMillis),
        S3FixQuality.description(fix.provider, fix.accuracyMeters.toFloat()))

private fun compactStatus(status: ExerciseStatus): String = when (status) {
    ExerciseStatus.OPEN -> "等待接手"
    ExerciseStatus.CLAIM_PENDING -> "确认中"
    ExerciseStatus.ACCEPTED -> "有人接手"
    ExerciseStatus.CLOSED -> "已结案"
    ExerciseStatus.EXPIRED -> "超时归档"
}
