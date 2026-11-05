package io.github.tigaotg.openkinguard

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Local-only UI. No manager, pairing or network instance is referenced. */
@Composable
internal fun MedicalHealthSection(scrollState: ScrollState) {
    val context = LocalContext.current
    val vault = remember { MedicalVault(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(runCatching { vault.snapshot() }.getOrDefault(MedicalVault.emptyState())) }
    var screen by remember { mutableStateOf("home") }
    var kind by remember { mutableStateOf(MedicalKind.CONDITION) }
    var editing by remember { mutableStateOf<MedicalEntry?>(null) }
    val fields = remember { mutableStateMapOf<String, String>() }
    var emergencyText by remember { mutableStateOf(state.emergency) }
    val incident = remember { mutableStateMapOf<String, String>().apply { putAll(state.incident) } }
    var feedback by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var deleteRecord by remember { mutableStateOf<MedicalEntry?>(null) }
    var deleteAttachment by remember { mutableStateOf<MedicalFile?>(null) }
    var renameAttachment by remember { mutableStateOf<MedicalFile?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var attachmentTarget by remember { mutableStateOf<Pair<MedicalKind, String>?>(null) }
    var cameraPhoto by remember { mutableStateOf<Uri?>(null) }
    var cameraPath by remember { mutableStateOf<File?>(null) }
    var exportType by remember { mutableStateOf("zip") }
    var exportConfirm by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<MedicalImport?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var passwordDialog by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }

    fun reload() { state = vault.snapshot() }
    fun background(block: suspend () -> String) {
        if (working) return
        working = true
        scope.launch {
            feedback = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { error ->
                "操作失败：${error.message ?: error.javaClass.simpleName}"
            } }
            runCatching { reload() }.onFailure { feedback = "刷新失败：${it.message}" }
            working = false
        }
    }
    fun openEditor(item: MedicalEntry?) {
        editing = item ?: MedicalEntry(MedicalVault.freshId(), emptyMap())
        fields.clear()
        fields.putAll(item?.values.orEmpty())
        screen = "editor"
    }
    fun logDose(occurrence: MedicineOccurrence, status: String) {
        background {
            val row = MedicalEntry(MedicalVault.freshId(), mapOf(
                "medicineId" to occurrence.medicine.id,
                "medicineName" to occurrence.medicine.value("name"),
                "planned" to occurrence.key,
                "actual" to LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "dose" to occurrence.medicine.value("dose") + occurrence.medicine.value("unit"),
                "status" to status))
            vault.put(MedicalKind.DOSE, row)
            "${occurrence.medicine.value("name")}：已记录$status（仅保存在本机）"
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val target = attachmentTarget
            val file = vault.addFile(uri, name, mime, target?.first?.key.orEmpty(), target?.second.orEmpty())
            if (target != null) vault.linkFile(target.first, target.second, file.id)
            "已安全导入 ${file.name}，不再依赖原文件的访问权限"
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = cameraPhoto
        if (captured && uri != null) background {
            val target = attachmentTarget
            val item = vault.addFile(uri, "药品或病历照片_${System.currentTimeMillis()}.jpg",
                "image/jpeg", target?.first?.key.orEmpty(), target?.second.orEmpty())
            if (target != null) vault.linkFile(target.first, target.second, item.id)
            cameraPath?.delete()
            "照片已保存到本机加密档案"
        }
    }
    fun capturePhoto() {
        val directory = File(context.cacheDir, "medical-capture").apply { mkdirs() }
        val file = File(directory, "capture_${System.currentTimeMillis()}.jpg")
        cameraPath = file
        cameraPhoto = FileProvider.getUriForFile(context,
            "${context.packageName}.medicalfiles", file)
        camera.launch(cameraPhoto!!)
    }
    val fileExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) background {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                when (exportType) {
                    "zip" -> MedicalArchive.exportZip(output, vault.snapshot(), vault)
                    "csv" -> output.write(MedicalArchive.csv(vault.snapshot()).toByteArray(Charsets.UTF_8))
                    "emergency" -> output.write(vault.snapshot().emergency.toByteArray(Charsets.UTF_8))
                    "txt" -> output.write(MedicalArchive.text(vault.snapshot()).toByteArray(Charsets.UTF_8))
                    "backup" -> MedicalArchive.backup(output, vault.snapshot(), vault,
                        backupPassword.toCharArray())
                    else -> error("未知导出格式")
                }
            } ?: error("无法写入目标文件")
            backupPassword = ""
            "导出完成，请在系统文件管理器中查看。普通导出文件未加密。"
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background {
            val name = uri.lastPathSegment.orEmpty().lowercase()
            val mime = context.contentResolver.getType(uri).orEmpty()
            val incoming = when {
                screen == "restore" -> context.contentResolver.openInputStream(uri)?.use {
                    MedicalArchive.restorePreview(it, restorePassword.toCharArray())
                } ?: error("无法读取备份")
                mime == "application/zip" || name.endsWith(".zip") ->
                    context.contentResolver.openInputStream(uri)?.use { MedicalArchive.previewZip(it) }
                        ?: error("无法读取 ZIP")
                mime == "text/plain" || name.endsWith(".txt") ->
                    context.contentResolver.openInputStream(uri)?.use { MedicalArchive.previewText(it) }
                        ?: error("无法读取 TXT")
                else -> {
                    val display = context.contentResolver.query(uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "医疗附件"
                    vault.addFile(uri, display, mime.ifBlank { "application/octet-stream" })
                    return@background "已作为普通医疗附件保存（未自动识别为诊断或处方）"
                }
            }
            withContext(Dispatchers.Main) { imported = incoming }
            restorePassword = ""
            "预览完成，请核对记录数量，确认后再合并导入"
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        feedback = if (granted) "通知已允许；本机提醒可显示" else "未允许通知，请在系统设置中开启"
    }

    fun export(format: String) { exportType = format; exportConfirm = true }
    fun fileName(type: String) = when (type) {
        "zip" -> "OpenKinGuard_完整健康档案.zip"
        "txt" -> "OpenKinGuard_健康档案.txt"
        "csv" -> "OpenKinGuard_服药记录.csv"
        "emergency" -> "紧急医疗信息.txt"
        else -> "OpenKinGuard_加密健康备份.okgbak"
    }

    LaunchedEffect(screen) { scrollState.scrollTo(0) }

    if (screen != "home") TextButton(onClick = { screen = if (screen == "editor") "list" else "home" }) {
        Text("‹ 返回${if (screen == "editor") kind.title else "健康档案"}")
    }
    Text(when (screen) {
        "home" -> "健康档案"
        "profile" -> "个人医疗信息"
        "emergency" -> "紧急医疗信息"
        "list", "editor" -> kind.title
        "files" -> "医疗附件"
        "export" -> "导入与导出"
        "restore" -> "加密备份与恢复"
        else -> "健康档案"
    }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (feedback.isNotBlank()) Card(Modifier.fillMaxWidth()) {
        Text(feedback, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
    if (working) Text("正在处理，请稍候……", color = MaterialTheme.colorScheme.primary)

    when (screen) {
        "home" -> {
            Text("仅存放于当前手机，不会发送给家庭节点或其他家人。",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { emergencyText = state.emergency; incident.clear();
                incident.putAll(state.incident); screen = "emergency" },
                modifier = Modifier.fillMaxWidth()) { Text("紧急医疗信息 · 给医生看") }
            val today = MedicalSchedule.today(state)
            Text("今日服药", style = MaterialTheme.typography.titleLarge)
            if (today.isEmpty()) Text("今天没有已启用的服药计划")
            today.forEach { occurrence ->
                MedicalDoseCard(occurrence,
                    onTaken = { logDose(occurrence, "已服药") },
                    onSkipped = { logDose(occurrence, "已跳过") },
                    onSnooze = {
                        MedicalReminders.snooze(context, occurrence.medicine)
                        feedback = "10分钟后再提醒（如果系统允许通知）"
                    })
            }
            HorizontalDivider()
            MedicalAction("个人基本医疗信息", "过敏史、联系人和注意事项") {
                fields.clear(); fields.putAll(state.profile); screen = "profile"
            }
            listOf(MedicalKind.MEDICINE, MedicalKind.CONDITION,
                MedicalKind.ADMISSION, MedicalKind.SURGERY, MedicalKind.DOSE).forEach { item ->
                MedicalAction(item.title, "已有 ${state.records(item).size} 条记录") {
                    kind = item; screen = "list"
                }
            }
            MedicalAction("医疗附件", "已保存 ${state.files.size} 份图片或文档") { screen = "files" }
            MedicalAction("导入、导出健康档案", "通用 TXT、CSV、ZIP，可独立阅读") { screen = "export" }
            MedicalAction("加密备份与恢复", "使用自己设置的密码保护完整备份") { screen = "restore" }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("开启本机服药通知权限")
                }
            }
            if (Build.VERSION.SDK_INT >= 31 &&
                !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                Text("系统未允许精确闹钟，服药提醒可能延迟。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")))
                }) { Text("检查精确闹钟设置") }
            }
        }
        "profile" -> {
            MedicalFields.profile.forEach { (key, title) -> MedicalField(title, fields[key].orEmpty()) {
                fields[key] = it.take(2000)
            } }
            Button(enabled = !working, onClick = { background {
                vault.saveProfile(fields.toMap()); "个人资料已加密保存在本机"
            } }) { Text("保存个人医疗信息") }
        }
        "emergency" -> {
            Text("长期病史与本次发生的情况分开填写。保存后可离线显示、复制或导出 TXT。")
            OutlinedTextField(value = emergencyText, onValueChange = { emergencyText = it.take(30_000) },
                label = { Text("给医生看的长期医疗说明") }, modifier = Modifier.fillMaxWidth(),
                minLines = 6)
            OutlinedButton(onClick = {
                emergencyText = buildString {
                    MedicalFields.profile.forEach { (key, label) ->
                        appendLine("$label：${state.profile[key].orEmpty()}") }
                    appendLine("当前用药：" + state.records(MedicalKind.MEDICINE)
                        .filter { MedicalSchedule.active(it) }.joinToString("；") { it.value("name") })
                    appendLine("重大手术：" + state.records(MedicalKind.SURGERY)
                        .joinToString("；") { it.value("name") })
                }
                feedback = "已生成初稿，手动核对后请点击保存"
            }) { Text("根据档案生成初稿（会替换上方未保存文本）") }
            MedicalFields.incident.forEach { (key, title) -> MedicalField(title, incident[key].orEmpty()) {
                incident[key] = it.take(4000)
            } }
            Button(onClick = { background {
                vault.saveEmergency(emergencyText, incident.toMap()); "紧急医疗信息已保存"
            } }) { Text("保存紧急医疗信息") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("紧急医疗信息", emergencyText))
                    feedback = "已复制文本"
                }) { Text("复制全文") }
                OutlinedButton(onClick = { export("emergency") }) { Text("导出 TXT") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, emergencyText), "分享医疗说明"))
                }) { Text("分享") }
            }
        }
        "list" -> {
            Button(onClick = { openEditor(null) }) { Text("新增${kind.title}") }
            if (state.records(kind).isEmpty()) Text("暂无记录，点击上方按钮添加")
            state.records(kind).sortedByDescending { it.updatedAt }.forEach { entry ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.value(kind.fields.first().first).ifBlank { "未命名记录" },
                            style = MaterialTheme.typography.titleMedium)
                        kind.fields.drop(1).take(3).forEach { (key, label) ->
                            if (entry.value(key).isNotBlank()) Text("$label：${entry.value(key)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { openEditor(entry) }) { Text("查看 / 编辑") }
                            TextButton(onClick = { deleteRecord = entry }) { Text("删除") }
                        }
                    }
                }
            }
        }
        "editor" -> {
            val current = editing
            if (current != null) {
                kind.fields.forEach { (key, label) -> MedicalField(label, fields[key].orEmpty()) {
                    fields[key] = it.take(4000)
                } }
                if (kind == MedicalKind.SURGERY && state.records(MedicalKind.ADMISSION).isNotEmpty()) {
                    Text("选择关联的住院记录（选填）")
                    state.records(MedicalKind.ADMISSION).forEach { stay ->
                        TextButton(onClick = { fields["admissionId"] = stay.id }) {
                            Text("关联：${stay.value("hospital")} ${stay.value("start")}")
                        }
                    }
                }
                Button(onClick = { background {
                    vault.put(kind, current.copy(values = fields.toMap()))
                    if (kind == MedicalKind.MEDICINE) MedicalReminders.scheduleAll(context)
                    withContext(Dispatchers.Main) { editing = current.copy(values = fields.toMap()); screen = "list" }
                    "${kind.title}已保存"
                } }) { Text("保存记录") }
                if (state.records(kind).any { it.id == current.id }) {
                    Text("关联附件", style = MaterialTheme.typography.titleMedium)
                    current.attachments.forEach { id ->
                        state.files.firstOrNull { it.id == id }?.let { Text(it.name) }
                    }
                    OutlinedButton(onClick = {
                        attachmentTarget = kind to current.id
                        filePicker.launch(arrayOf("image/*", "application/pdf", "text/plain"))
                    }) { Text("添加药品照片、说明书或病历附件") }
                    OutlinedButton(onClick = {
                        attachmentTarget = kind to current.id
                        capturePhoto()
                    }) { Text("拍照保存附件") }
                } else Text("先保存记录，再添加照片或 PDF 附件")
            }
        }
        "files" -> {
            Button(onClick = { attachmentTarget = null;
                filePicker.launch(arrayOf("image/*", "application/pdf", "text/plain")) }) {
                Text("导入图片 / PDF / TXT")
            }
            OutlinedButton(onClick = { attachmentTarget = null; capturePhoto() }) {
                Text("拍照保存医疗资料")
            }
            if (state.files.isEmpty()) Text("目前没有保存的医疗附件")
            state.files.forEach { file ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                        Text("${file.mime} · ${file.size / 1024} KB", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                background {
                                    val suffix = file.name.substringAfterLast('.', "bin")
                                        .replace(Regex("[^a-zA-Z0-9]"), "").take(8)
                                    val previews = File(context.cacheDir, "medical-preview").apply { mkdirs() }
                                    val target = File(previews, "${file.id}.$suffix")
                                    target.writeBytes(vault.readFile(file))
                                    val uri = FileProvider.getUriForFile(context,
                                        "${context.packageName}.medicalfiles", target)
                                    withContext(Dispatchers.Main) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uri, file.mime)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    }
                                    "已交给系统文件查看器打开"
                                }
                            }) { Text("查看") }
                            TextButton(onClick = { deleteAttachment = file }) { Text("删除") }
                            TextButton(onClick = {
                                renameAttachment = file; renameDraft = file.name
                            }) { Text("改标题") }
                        }
                    }
                }
            }
        }
        "export" -> {
            Text("普通导出的 TXT、CSV、ZIP、图片和 PDF 无需安装本软件即可打开。")
            MedicalAction("导出完整健康档案 ZIP", "含全部文字和原始图片、PDF、说明书") { export("zip") }
            MedicalAction("导出可阅读 TXT", "全部文字信息及结构化导入标记") { export("txt") }
            MedicalAction("导出服药历史 CSV", "Excel / WPS / LibreOffice 可直接打开") { export("csv") }
            HorizontalDivider()
            MedicalAction("导入健康档案 ZIP / 结构化 TXT", "先预览、再确认，合并时不会覆盖已有记录") {
                screen = "export"
                importer.launch(arrayOf("application/zip", "application/octet-stream", "text/plain"))
            }
            MedicalAction("导入单个医疗附件", "JPG、PNG、PDF、TXT；作为附件保存") {
                attachmentTarget = null
                filePicker.launch(arrayOf("image/*", "application/pdf", "text/plain"))
            }
        }
        "restore" -> {
            Text("加密备份使用你设置的密码。请妥善保管；忘记密码无法恢复。")
            OutlinedButton(onClick = { passwordDialog = "create" }) { Text("创建加密备份") }
            OutlinedButton(onClick = { passwordDialog = "restore" }) { Text("恢复加密备份") }
        }
    }

    if (exportConfirm) AlertDialog(onDismissRequest = { exportConfirm = false },
        title = { Text(if (exportType == "backup") "保存加密备份" else "确认导出敏感健康信息？") },
        text = { Text(if (exportType == "backup") "备份将采用密码加密，必须记住密码才能恢复。" else
            "导出的文件没有加密，可能包含病历、过敏史、服药情况和联系人。请保存到安全的位置。") },
        confirmButton = { TextButton(onClick = {
            exportConfirm = false
            fileExporter.launch(fileName(exportType))
        }) { Text("选择保存位置") } },
        dismissButton = { TextButton(onClick = { exportConfirm = false }) { Text("取消") } })

    imported?.let { candidate ->
        AlertDialog(onDismissRequest = { imported = null }, title = { Text("预览健康档案导入") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(candidate.summary())
                Text("只合并缺失的记录；已存在的同编号记录不会被覆盖。" +
                    "导入历史记录不会触发过去的服药提醒。")
            } }, confirmButton = { TextButton(onClick = {
                imported = null
                background {
                    val merged = MedicalArchive.merge(vault.snapshot(), candidate, vault)
                    vault.replaceForRestore(merged.state, merged.attachments)
                    MedicalReminders.scheduleAll(context)
                    "导入完成：${candidate.summary()}"
                }
            }) { Text("确认合并导入") } },
            dismissButton = { TextButton(onClick = { imported = null }) { Text("取消") } })
    }
    deleteRecord?.let { entry ->
        AlertDialog(onDismissRequest = { deleteRecord = null }, title = { Text("删除这条记录？") },
            text = { Text("删除${kind.title}记录后不能撤销。独立的医疗附件不会自动删除。") },
            confirmButton = { TextButton(onClick = {
                deleteRecord = null
                background { vault.remove(kind, entry.id)
                    if (kind == MedicalKind.MEDICINE) MedicalReminders.scheduleAll(context)
                    "记录已删除" }
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteRecord = null }) { Text("取消") } })
    }
    deleteAttachment?.let { file ->
        AlertDialog(onDismissRequest = { deleteAttachment = null }, title = { Text("永久删除附件？") },
            text = { Text("将删除本机加密文件并解除全部记录关联，不影响你原来导入的外部文件。") },
            confirmButton = { TextButton(onClick = {
                deleteAttachment = null
                background { vault.deleteFile(file.id); "已删除附件" }
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteAttachment = null }) { Text("取消") } })
    }
    renameAttachment?.let { file ->
        AlertDialog(onDismissRequest = { renameAttachment = null },
            title = { Text("修改附件标题") },
            text = { OutlinedTextField(value = renameDraft,
                onValueChange = { renameDraft = it.take(120) },
                label = { Text("标题") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = {
                renameAttachment = null
                background { vault.renameFile(file.id, renameDraft); "标题已修改" }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameAttachment = null }) { Text("取消") } })
    }
    if (passwordDialog.isNotBlank()) AlertDialog(onDismissRequest = { passwordDialog = "" },
        title = { Text(if (passwordDialog == "create") "创建加密备份" else "打开加密备份") },
        text = { OutlinedTextField(value = backupPassword,
            onValueChange = { backupPassword = it.take(128) },
            label = { Text("备份密码（至少10个字符）") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = backupPassword.length >= 10, onClick = {
            val creating = passwordDialog == "create"
            passwordDialog = ""
            if (creating) export("backup") else {
                restorePassword = backupPassword
                backupPassword = ""
                screen = "restore"
                importer.launch(arrayOf("application/octet-stream", "*/*"))
            }
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { backupPassword = ""; passwordDialog = "" }) {
            Text("取消")
        } })
}

@Composable private fun MedicalField(title: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(title) },
        modifier = Modifier.fillMaxWidth(), minLines = if (title.contains("描述") ||
            title.contains("备注") || title.contains("医嘱")) 2 else 1)
}

@Composable private fun MedicalAction(title: String, detail: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun MedicalDoseCard(occurrence: MedicineOccurrence,
    onTaken: () -> Unit, onSkipped: () -> Unit, onSnooze: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val time = occurrence.planned.toLocalTime()
            val pending = occurrence.status == "待确认"
            val description = if (pending && occurrence.planned.isAfter(LocalDateTime.now())) "未到时间"
                else occurrence.status
            Text("$time · ${occurrence.medicine.value("name")}",
                style = MaterialTheme.typography.titleMedium)
            Text("${occurrence.medicine.value("dose")}${occurrence.medicine.value("unit")} · $description")
            if (pending) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onTaken) { Text("我已服药") }
                TextButton(onClick = onSnooze) { Text("稍后提醒") }
                TextButton(onClick = onSkipped) { Text("跳过") }
            }
        }
    }
}
