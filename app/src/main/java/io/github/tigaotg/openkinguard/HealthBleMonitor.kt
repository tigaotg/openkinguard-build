package io.github.tigaotg.openkinguard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tigaotg.openkinguard.core.engine.HealthGattCodec
import io.github.tigaotg.openkinguard.core.engine.HealthGattCapabilityDetector
import io.github.tigaotg.openkinguard.core.engine.HealthMetricValue
import io.github.tigaotg.openkinguard.core.engine.HealthMetricKind
import io.github.tigaotg.openkinguard.core.engine.HeartRatePilotAlert
import io.github.tigaotg.openkinguard.core.engine.HeartRatePilotEvaluator
import io.github.tigaotg.openkinguard.core.engine.HeartRatePilotSettings
import io.github.tigaotg.openkinguard.core.engine.StandardHealthProfile
import java.util.ArrayDeque
import java.util.UUID
import java.security.MessageDigest
import java.security.SecureRandom

data class HealthBleCandidate(
    val address: String,
    val name: String,
    val rssi: Int?,
    val advertisedProfiles: List<StandardHealthProfile>,
    val isBonded: Boolean = false,
    val isSystemGattConnected: Boolean = false,
    val seenInScan: Boolean = false,
    val bluetoothType: String = "未知类型",
    val manufacturerIds: List<Int> = emptyList(),
    val advertisedServiceUuids: List<String> = emptyList(),
    val nameSource: String = "未提供名称"
)

data class HealthBleReading(
    val metric: HealthMetricValue,
    val deviceName: String,
    val capturedAt: Long
)

/** Read-only GATT metadata, with no MAC address, characteristic value, or pairing secret. */
data class HealthGattCharacteristicInfo(
    val uuid: String,
    val properties: String
)

data class HealthGattServiceInfo(
    val uuid: String,
    val characteristics: List<HealthGattCharacteristicInfo>
)

data class HealthDeviceProperty(val title: String, val value: String)

data class HealthConnectionEvent(val recordedAt: Long, val message: String)

data class HealthBleState(
    val available: Boolean = true,
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val connectedName: String = "",
    val status: String = "蓝牙设备尚未连接",
    val candidates: List<HealthBleCandidate> = emptyList(),
    val profiles: List<StandardHealthProfile> = emptyList(),
    val readings: List<HealthBleReading> = emptyList(),
    val gattServices: List<HealthGattServiceInfo> = emptyList(),
    val deviceProperties: List<HealthDeviceProperty> = emptyList(),
    val batteryLevel: Int? = null,
    val huaweiServiceDetected: Boolean = false,
    val standardHeartRateDetected: Boolean = false,
    val connectedAt: Long? = null,
    val lastMeasurementAt: Long? = null,
    val diagnosticEvents: List<HealthConnectionEvent> = emptyList()
)

/**
 * Foreground, user-initiated BLE GATT health device session.
 * No network requests, no collection of Bluetooth identifiers in saved history,
 * and no synthetic measurements. Only one active GATT device in this milestone.
 */
class HealthBleMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val discovered = linkedMapOf<String, BluetoothDevice>()
    private val candidates = linkedMapOf<String, HealthBleCandidate>()
    private var systemGattAddresses = emptySet<String>()
    private sealed interface GattOperation {
        data class Subscribe(val characteristic: BluetoothGattCharacteristic) : GattOperation
        data class Read(val characteristic: BluetoothGattCharacteristic, val label: String) : GattOperation
    }
    private val operations = ArrayDeque<GattOperation>()
    private var operationInFlight = false
    private var operationTimedOut = false
    private var operationTimeout: Runnable? = null
    private val cccdId = standardUuid(0x2902)
    private val historyStore = HealthReadingStore(appContext)
    private val aliasPrefs = appContext.getSharedPreferences("okg.health.local_aliases.v1", Context.MODE_PRIVATE)
    private val localAliasSalt: String = aliasPrefs.getString("device_local_salt", null)
        ?.takeIf { it.length >= 20 } ?: run {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        Base64.encodeToString(bytes, Base64.NO_WRAP).also {
            aliasPrefs.edit().putString("device_local_salt", it).apply()
        }
    }
    private val pilotPrefs = appContext.getSharedPreferences("okg.health.heart_pilot.v1", Context.MODE_PRIVATE)
    private val pilot = HeartRatePilotEvaluator(HeartRatePilotSettings(
        enabled = pilotPrefs.getBoolean("enabled", false),
        lowBpm = pilotPrefs.getInt("low", 50).coerceIn(30, 75),
        highBpm = pilotPrefs.getInt("high", 120).coerceIn(100, 220),
        durationMillis = pilotPrefs.getLong("duration", 180_000L)
            .takeIf { it in 60_000L..600_000L } ?: 180_000L
    ))
    private var activeGatt: BluetoothGatt? = null
    private var scannerCallback: ScanCallback? = null
    private val stopScanRunnable = Runnable { stopScan() }

    var state by mutableStateOf(HealthBleState(available = adapter != null))
        private set

    var historyEnabled by mutableStateOf(historyStore.enabled)
        private set
    var historyRetentionDays by mutableStateOf(historyStore.retentionDays)
        private set

    var heartPilotSettings by mutableStateOf(pilot.settings)
        private set
    var heartPilotStatus by mutableStateOf(pilot.snapshot(SystemClock.elapsedRealtime(), false))
        private set
    var heartPilotLastNotice by mutableStateOf("尚未触发实验性本机提醒")
        private set
    var heartPilotNoticeCount by mutableStateOf(0)
        private set

    /** Only configuration is persisted. No measurement or vendor identity is saved here. */
    fun configureHeartPilot(enabled: Boolean = heartPilotSettings.enabled,
                            lowBpm: Int = heartPilotSettings.lowBpm,
                            highBpm: Int = heartPilotSettings.highBpm,
                            durationMillis: Long = heartPilotSettings.durationMillis) {
        val config = HeartRatePilotSettings(enabled, lowBpm, highBpm, durationMillis)
        pilot.configure(config) // Always reset if thresholds or opt-in have changed.
        heartPilotSettings = pilot.settings
        pilotPrefs.edit().putBoolean("enabled", config.enabled)
            .putInt("low", config.lowBpm).putInt("high", config.highBpm)
            .putLong("duration", config.durationMillis).apply()
        heartPilotLastNotice = "设置已更新，必须重新取得连续的新测量值才可能提醒"
        refreshHeartPilot()
        event("本机心率试验${if (config.enabled) "启用" else "关闭"}；没有向外发送数据")
    }

    fun refreshHeartPilot() {
        heartPilotStatus = pilot.snapshot(SystemClock.elapsedRealtime(), state.connected)
    }

    fun testHeartPilotNotification(): String {
        val result = S1TestNotifier.showHeartPilot(appContext,
            "手动发起的本机通知测试；不是实际心率异常，也没有联系任何家属")
        heartPilotLastNotice = if (result.isBlank()) "已提交本机测试通知（系统是否显示取决于设置）" else result
        return heartPilotLastNotice
    }

    private fun resetHeartPilotSession() {
        pilot.reset()
        refreshHeartPilot()
    }

    private fun recordHeartPilotAlert(alert: HeartRatePilotAlert) {
        val message = "本机心率试验：接收的心率连续${alert.durationMillis / 60_000}分钟" +
            "${if (alert.low) "偏低" else "偏高"}（最新 ${alert.bpm} 次/分），请人工复核设备读数；非医学诊断"
        val result = S1TestNotifier.showHeartPilot(appContext, message)
        heartPilotNoticeCount++
        heartPilotLastNotice = if (result.isBlank()) "已提交本机预警通知：$message" else
            "心率条件满足，但通知未成功提交：$result"
        event("心率实验阈值判断达到连续时间；通知${if (result.isBlank()) "已提交" else "未提交"}，未进行远程求救")
    }

    private fun event(message: String) {
        state = state.copy(diagnosticEvents =
            (listOf(HealthConnectionEvent(System.currentTimeMillis(), message)) +
                state.diagnosticEvents).take(25))
    }

    fun enableHistory(enabled: Boolean) {
        historyStore.enabled = enabled
        historyEnabled = enabled
        event(if (enabled) "已开启本机 AES-GCM 加密历史" else "已停止保存新读数；已有历史不自动删除")
    }

    fun setHistoryRetention(days: Int) {
        historyStore.retentionDays = days
        historyRetentionDays = days
        event("本机历史保留天数调整为 $days")
    }

    fun historyReadings(limit: Int = 100): List<HealthBleReading> = historyStore.latest(limit)
    fun historyCount(): Int = historyStore.count()
    fun clearHistory() {
        historyStore.clear()
        event("用户已清空本机加密健康记录")
    }

    // Local nicknames are indexed by a device-specific salted digest, not by a
    // plaintext Bluetooth address. Address rotation still prevents recognition.
    private fun aliasKey(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$localAliasSalt:$address".toByteArray(Charsets.UTF_8))
        return "alias_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun setLocalDeviceAlias(address: String, proposed: String) {
        require(address in discovered) { "请先扫描该设备，再为它设置备注" }
        val newName = proposed.trim().take(32)
        val editor = aliasPrefs.edit()
        if (newName.isBlank()) editor.remove(aliasKey(address))
        else editor.putString(aliasKey(address), newName)
        editor.apply()
        refreshCandidateNames()
        event(if (newName.isBlank()) "已移除本机设备备注" else "已更新本机设备备注（不会上传）")
    }

    @SuppressLint("MissingPermission")
    private fun refreshCandidateNames() {
        candidates.keys.toList().forEach { address ->
            val device = discovered[address] ?: return@forEach
            val candidate = candidates[address] ?: return@forEach
            val (name, source) = resolvedName(device, null, candidate)
            candidates[address] = candidate.copy(name = name, nameSource = source)
        }
        publishCandidates()
        if (state.connectedName.isNotBlank()) {
            val current = activeGatt?.device?.address
            if (current != null) candidates[current]?.name?.let { name ->
                state = state.copy(connectedName = name)
            }
        }
    }

    fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermission()) {
            state = state.copy(status = "需要先授权蓝牙扫描和连接权限")
            return
        }
        val bluetooth = adapter
        if (bluetooth == null) {
            state = state.copy(available = false, status = "本机不支持蓝牙")
            return
        }
        if (!bluetooth.isEnabled) {
            state = state.copy(status = "请先打开系统蓝牙开关，再重新扫描")
            return
        }
        stopScan()
        discovered.clear()
        candidates.clear()
        refreshSystemDevices(updateStatus = false)
        val scanner = bluetooth.bluetoothLeScanner
        if (scanner == null) {
            state = state.copy(status = "蓝牙扫描不可用；请检查蓝牙和定位开关")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onFound(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onFound(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    stopScan()
                    state = state.copy(status = "扫描失败（代码 $errorCode）；请重开蓝牙后再试")
                }
            }
        }
        scannerCallback = callback
        state = state.copy(scanning = true,
            status = "正在搜索附近 BLE 设备（12秒后自动停止）；已加入系统配对设备…")
        runCatching {
            scanner.startScan(null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback)
            handler.postDelayed(stopScanRunnable, 12_000L)
        }.onFailure {
            scannerCallback = null
            state = state.copy(scanning = false,
                status = "无法启动扫描：${it.message ?: it.javaClass.simpleName}")
        }
    }

    /** Read system Bluetooth state only. Pairing and GATT profile connection do not mean
     * OpenKinGuard has opened a measurement session or authenticated a vendor protocol. */
    @SuppressLint("MissingPermission")
    fun refreshSystemDevices(updateStatus: Boolean = true) {
        if (!hasPermission()) {
            state = state.copy(status = "需要先授权蓝牙连接权限，才能查看系统设备名称")
            return
        }
        val bluetooth = adapter ?: return
        if (!bluetooth.isEnabled) {
            state = state.copy(status = "请先打开系统蓝牙开关")
            return
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val systemConnected = runCatching {
            manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        }.getOrDefault(emptyList())
        systemGattAddresses = systemConnected.map { it.address }.toSet()
        val bonded = runCatching { bluetooth.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        (bonded + systemConnected).forEach { device ->
            val address = device.address ?: return@forEach
            discovered[address] = device
            val previous = candidates[address]
            val (label, source) = resolvedName(device, null, previous)
            candidates[address] = (previous ?: HealthBleCandidate(
                address, label, null, emptyList())).copy(
                name = label, nameSource = source,
                isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
                isSystemGattConnected = address in systemGattAddresses,
                bluetoothType = bluetoothType(device))
        }
        // Clear stale state after the phone or Huawei Health changes its connection.
        candidates.keys.toList().forEach { address ->
            val previous = candidates[address] ?: return@forEach
            val device = discovered[address] ?: return@forEach
            val (label, source) = resolvedName(device, null, previous)
            candidates[address] = previous.copy(
                name = label, nameSource = source,
                isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
                isSystemGattConnected = address in systemGattAddresses)
        }
        publishCandidates()
        if (updateStatus) state = state.copy(status =
            "已刷新系统设备信息。系统配对/连接状态不代表 OpenKinGuard 已连接。")
    }

    @SuppressLint("MissingPermission")
    private fun resolvedName(device: BluetoothDevice, advertisedName: String?,
        previous: HealthBleCandidate?): Pair<String, String> {
        aliasPrefs.getString(aliasKey(device.address), null)?.takeIf { it.isNotBlank() }
            ?.let { return it to "用户本机备注" }
        val alias = if (Build.VERSION.SDK_INT >= 30) runCatching { device.alias }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() } else null
        if (alias != null) return alias.take(48) to "系统昵称"
        val systemName = runCatching { device.name }.getOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (systemName != null) return systemName.take(48) to "系统设备名称"
        val broadcast = advertisedName?.trim()?.takeIf { it.isNotEmpty() }
        if (broadcast != null) return broadcast.take(48) to "蓝牙广播名称"
        if (previous != null && previous.nameSource != "未提供名称" &&
            previous.nameSource != "用户本机备注")
            return previous.name to previous.nameSource
        return "未命名设备 · ${device.address.takeLast(5)}" to "未提供名称"
    }

    @SuppressLint("MissingPermission") // The helper rechecks permission and catches races/revocation.
    private fun bluetoothType(device: BluetoothDevice): String = when (
        if (hasPermission()) runCatching { device.type }.getOrNull() else null
    ) {
        BluetoothDevice.DEVICE_TYPE_LE -> "低功耗蓝牙 BLE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "双模蓝牙"
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙"
        else -> "未知类型"
    }

    private fun publishCandidates() {
        state = state.copy(candidates = candidates.values.sortedWith(
            compareByDescending<HealthBleCandidate> { it.isBonded }
                .thenByDescending { it.isSystemGattConnected }
                .thenByDescending { it.advertisedProfiles.isNotEmpty() }
                .thenByDescending { it.nameSource != "未提供名称" }
                .thenByDescending { it.rssi ?: -200 }
        ).take(80))
    }

    @SuppressLint("MissingPermission")
    private fun onFound(result: ScanResult) {
        if (!hasPermission()) return
        val device = result.device ?: return
        val address = device.address ?: return
        val advertised = result.scanRecord?.serviceUuids.orEmpty().mapNotNull { parcel ->
            StandardHealthProfile.entries.firstOrNull {
                standardUuid(it.serviceId) == parcel.uuid
            }
        }.distinct()
        handler.post {
            if (!state.scanning) return@post
            discovered[address] = device
            val previous = candidates[address]
            val (label, source) = resolvedName(device, result.scanRecord?.deviceName, previous)
            val manufacturerIds = result.scanRecord?.manufacturerSpecificData?.let { values ->
                (0 until values.size()).map { values.keyAt(it) }.take(8)
            }.orEmpty()
            val serviceUuids = result.scanRecord?.serviceUuids.orEmpty()
                .map { it.uuid.toString() }.take(8)
            candidates[address] = HealthBleCandidate(
                address, label, result.rssi,
                (previous?.advertisedProfiles.orEmpty() + advertised).distinct(),
                isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
                isSystemGattConnected = address in systemGattAddresses,
                seenInScan = true,
                bluetoothType = bluetoothType(device),
                manufacturerIds = (previous?.manufacturerIds.orEmpty() + manufacturerIds).distinct(),
                advertisedServiceUuids = (previous?.advertisedServiceUuids.orEmpty() + serviceUuids).distinct().take(8),
                nameSource = source)
            publishCandidates()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        scannerCallback?.let { callback ->
            if (hasPermission()) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        scannerCallback = null
        if (state.scanning) state = state.copy(scanning = false,
            status = "搜索已结束，可选择设备连接；附近设备不一定支持标准健康协议")
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermission()) {
            state = state.copy(status = "没有蓝牙连接权限")
            return
        }
        val device = discovered[address] ?: run {
            state = state.copy(status = "设备已不在列表，请重新扫描")
            return
        }
        val name = candidates[address]?.name ?: "BLE 设备"
        stopScan()
        disconnect()
        operationTimedOut = false
        event("用户选择连接设备：$name")
        state = state.copy(connected = false, connectedName = name,
            profiles = emptyList(), gattServices = emptyList(), deviceProperties = emptyList(),
            batteryLevel = null, huaweiServiceDetected = false,
            standardHeartRateDetected = false,
            connectedAt = null, lastMeasurementAt = null, status = "正在连接 $name…")
        runCatching { device.connectGatt(appContext, false, gattCallback,
            BluetoothDevice.TRANSPORT_LE) }.onSuccess { activeGatt = it }
            .onFailure {
                state = state.copy(status = "连接请求失败：${it.message ?: it.javaClass.simpleName}")
            }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        cancelOperationTimeout()
        operations.clear()
        operationInFlight = false
        operationTimedOut = false
        activeGatt?.let { gatt -> runCatching { gatt.disconnect() }; runCatching { gatt.close() } }
        activeGatt = null
        if (state.connected) state = state.copy(connected = false, profiles = emptyList(),
            status = "已断开，已显示的数值仅是上次接收记录")
        resetHeartPilotSession()
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (activeGatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    activeGatt = null
                    cancelOperationTimeout()
                    operations.clear()
                    operationInFlight = false
                    state = state.copy(connected = false, profiles = emptyList(),
                        status = "蓝牙已断开（状态 $status）；数据不再实时更新")
                    resetHeartPilotSession()
                    event("连接结束：GATT 状态 $status")
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    state = state.copy(connected = true,
                        connectedAt = System.currentTimeMillis(),
                        status = "已连接，正在检查健康设备服务…")
                    event("GATT 连接建立，开始发现服务")
                    if (!gatt.discoverServices()) state = state.copy(status = "无法读取设备服务列表")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (activeGatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    state = state.copy(status = "读取 GATT 服务失败（状态 $status）")
                    return@post
                }
                val found = mutableListOf<StandardHealthProfile>()
                operations.clear()
                operationInFlight = false
                operationTimedOut = false
                cancelOperationTimeout()
                // Enumerating GATT metadata does not read measurements, write device
                // configuration, start authentication, or initiate a new pairing.
                val services = gatt.services.orEmpty().take(128).map { service ->
                    HealthGattServiceInfo(service.uuid.toString(),
                        service.characteristics.orEmpty().take(64).map { characteristic ->
                            val flags = characteristic.properties
                            val labels = buildList {
                                if (flags and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("读")
                                if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("写")
                                if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("无回应写")
                                if (flags and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("通知")
                                if (flags and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("指示")
                            }
                            HealthGattCharacteristicInfo(characteristic.uuid.toString(),
                                labels.joinToString("/").ifBlank { "其他" })
                        })
                }
                StandardHealthProfile.entries.forEach { profile ->
                    val service = gatt.getService(standardUuid(profile.serviceId)) ?: return@forEach
                    var valid = false
                    profile.characteristicIds.forEach { characteristicId ->
                        val characteristic = service.getCharacteristic(standardUuid(characteristicId))
                            ?: return@forEach
                        if (characteristic.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                            if (characteristic.getDescriptor(cccdId) != null) {
                                operations.addLast(GattOperation.Subscribe(characteristic))
                                valid = true
                            }
                        }
                    }
                    if (valid) found.add(profile)
                }
                // Public information only: no serial number, hardware identity or personal values.
                val gap = gatt.getService(standardUuid(0x1800))
                val info = gatt.getService(standardUuid(0x180A))
                val battery = gatt.getService(standardUuid(0x180F))
                listOf(
                    Triple(gap, 0x2A00, "设备公开名称"),
                    Triple(info, 0x2A29, "制造商"),
                    Triple(info, 0x2A24, "型号"),
                    Triple(info, 0x2A26, "固件版本"),
                    Triple(info, 0x2A27, "硬件版本"),
                    Triple(info, 0x2A28, "软件版本"),
                    Triple(battery, 0x2A19, "标准电量")
                ).forEach { (service, id, label) ->
                    service?.getCharacteristic(standardUuid(id))?.let { characteristic ->
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                            operations.addLast(GattOperation.Read(characteristic, label))
                    }
                }
                val capabilityHints = HealthGattCapabilityDetector.classify(
                    services.associate { service -> service.uuid to
                        service.characteristics.map { it.uuid }.toSet() })
                val huaweiChannel = capabilityHints.hasHuaweiCommandAndNotify
                state = state.copy(profiles = found, gattServices = services,
                    huaweiServiceDetected = huaweiChannel,
                    standardHeartRateDetected = capabilityHints.hasStandardHeartRate,
                    status = if (found.isEmpty())
                        "已连接，但未发现可订阅的标准健康数据；可能使用厂商私有协议"
                    else "发现 ${found.size} 种标准服务，正在订阅真实测量数据…")
                event("发现 ${services.size} 个服务、${found.size} 个标准健康协议" +
                    if (huaweiChannel) "；检测到华为 FE86 双向通道（未认证、未发私有指令）" else "")
                processNext(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor,
                                       status: Int) {
            handler.post {
                if (activeGatt !== gatt) return@post
                if (operationTimedOut) return@post
                cancelOperationTimeout()
                operationInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    state = state.copy(status = "某项测量订阅失败（$status）；继续尝试其他服务")
                    event("标准通知订阅失败，状态 $status")
                }
                processNext(gatt)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            completeCharacteristicRead(gatt, characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33)
                completeCharacteristicRead(gatt, characteristic,
                    characteristic.value?.clone() ?: byteArrayOf(), status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                                             characteristic: BluetoothGattCharacteristic,
                                             value: ByteArray) {
            receiveMeasurement(gatt, characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                                             characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                receiveMeasurement(gatt, characteristic, characteristic.value?.clone() ?: return)
            }
        }
    }

    /** Android GATT permits one outstanding ATT request at a time. Never read another
     * characteristic while a descriptor write is pending (or vice versa). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun processNext(gatt: BluetoothGatt) {
        if (operationInFlight || operationTimedOut || activeGatt !== gatt || !hasPermission()) return
        while (operations.isNotEmpty()) {
            when (val operation = operations.removeFirst()) {
                is GattOperation.Subscribe -> {
                    val characteristic = operation.characteristic
                    val descriptor = characteristic.getDescriptor(cccdId) ?: continue
                    if (!gatt.setCharacteristicNotification(characteristic, true)) continue
                    val value = if (characteristic.properties and
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    val started = if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                    } else {
                        descriptor.value = value
                        gatt.writeDescriptor(descriptor)
                    }
                    if (started) {
                        operationInFlight = true
                        armOperationTimeout(gatt)
                        return
                    }
                    event("某个标准健康订阅请求未能启动")
                }
                is GattOperation.Read -> {
                    if (gatt.readCharacteristic(operation.characteristic)) {
                        operationInFlight = true
                        armOperationTimeout(gatt)
                        return
                    }
                    event("读取 ${operation.label} 请求未启动")
                }
            }
        }
        state = state.copy(status = if (state.profiles.isNotEmpty())
            "已订阅标准健康数据；等待设备上报实际测量值" else if (state.huaweiServiceDetected)
            "已识别华为私有接口；尚未认证，不能直接读取后台心率" else
            "设备信息检查完成；未发现可订阅的标准健康测量服务")
        event("标准服务订阅与公开设备信息读取已完成")
    }

    private fun completeCharacteristicRead(gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic, payload: ByteArray, status: Int) {
        handler.post {
            if (activeGatt !== gatt) return@post
            if (operationTimedOut) return@post
            cancelOperationTimeout()
            operationInFlight = false
            val id = characteristic.uuid.toString().substring(4, 8).toIntOrNull(16)
            val labels = mapOf(0x2A00 to "设备公开名称", 0x2A29 to "制造商",
                0x2A24 to "型号", 0x2A26 to "固件版本", 0x2A27 to "硬件版本",
                0x2A28 to "软件版本", 0x2A19 to "标准电量")
            val label = labels[id]
            if (status == BluetoothGatt.GATT_SUCCESS && label != null) {
                if (id == 0x2A19 && payload.size == 1) {
                    val percent = payload[0].toInt() and 0xff
                    if (percent in 0..100) state = state.copy(batteryLevel = percent)
                } else if (id != 0x2A19 && payload.isNotEmpty()) {
                    val safeValue = payload.toString(Charsets.UTF_8)
                        .filter { !it.isISOControl() }.trim().take(64)
                    if (safeValue.isNotBlank() && !safeValue.contains('\uFFFD')) {
                        state = state.copy(deviceProperties =
                            (state.deviceProperties.filterNot { it.title == label } +
                                HealthDeviceProperty(label, safeValue)))
                    }
                }
            } else if (label != null) {
                event("公开属性 $label 不可读取（状态 $status）")
            }
            processNext(gatt)
        }
    }

    private fun cancelOperationTimeout() {
        operationTimeout?.let { handler.removeCallbacks(it) }
        operationTimeout = null
    }

    private fun armOperationTimeout(gatt: BluetoothGatt) {
        cancelOperationTimeout()
        val timer = Runnable {
            if (activeGatt !== gatt || !operationInFlight) return@Runnable
            operations.clear() // Do not send more ATT requests while the peer is unresponsive.
            operationInFlight = false
            operationTimedOut = true
            state = state.copy(status = "蓝牙 GATT 请求超过10秒无响应；请手动断开再重连")
            event("GATT 操作超时，已停止后续请求，没有发送私有指令")
        }
        operationTimeout = timer
        handler.postDelayed(timer, 10_000L)
    }

    private fun receiveMeasurement(gatt: BluetoothGatt,
                                   characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        val characteristicId = characteristic.uuid.toString().substring(4, 8).toIntOrNull(16)
            ?: return
        val values = HealthGattCodec.decode(characteristicId, bytes)
        if (values.isEmpty()) return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        handler.post {
            if (activeGatt !== gatt || !state.connected) return@post
            val readings = values.map { HealthBleReading(it, state.connectedName, now) }
            state = state.copy(readings = (readings + state.readings).take(50),
                lastMeasurementAt = now,
                status = "已收到真实 BLE 测量数据，更新于本机时间")
            if (historyEnabled) runCatching { historyStore.append(readings) }
                .onFailure { event("加密历史保存失败：${it.javaClass.simpleName}") }
            // Only real Bluetooth SIG 0x2A37 heart-rate readings can drive this pilot.
            // Pulse oximeter readings, cached history, and private vendor traffic cannot.
            values.firstOrNull { it.kind == HealthMetricKind.HEART_RATE }?.let { metric ->
                val alert = pilot.observe(metric.value.toInt(), elapsed)
                refreshHeartPilot()
                if (alert != null) recordHeartPilotAlert(alert)
            }
        }
    }

    fun close() {
        stopScan()
        disconnect()
    }

    fun clearReadings() {
        state = state.copy(readings = emptyList(), status = "本次会话测量记录已清空")
    }

    fun gattDiagnosticText(): String = buildString {
        appendLine("OpenKinGuard GATT 接口清单（只读；不含蓝牙地址、测量值或认证信息）")
        appendLine("设备：${state.connectedName}；华为 FE86 双向通道：${if (state.huaweiServiceDetected) "存在（未认证）" else "未发现"}")
        state.deviceProperties.filter { it.title != "设备序列号" }.forEach {
            appendLine("PUBLIC_INFO ${it.title}: ${it.value}")
        }
        state.batteryLevel?.let { appendLine("标准电量：$it%") }
        appendLine("服务数：${state.gattServices.size}")
        state.gattServices.forEach { service ->
            appendLine("SERVICE ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                appendLine("  CHARACTERISTIC ${characteristic.uuid} [${characteristic.properties}]")
            }
        }
    }

    fun connectionDiagnosticText(): String = buildString {
        appendLine("OpenKinGuard 本机 BLE 连接事件（无 MAC、认证密钥或测量数值）")
        state.diagnosticEvents.reversed().forEach {
            appendLine("${it.recordedAt}: ${it.message}")
        }
    }

    companion object {
        private fun standardUuid(shortId: Int): UUID = UUID.fromString(
            "0000${shortId.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb")
    }
}
