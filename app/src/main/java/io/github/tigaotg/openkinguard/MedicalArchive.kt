package io.github.tigaotg.openkinguard

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class MedicalImport(val state: MedicalState, val attachments: Map<String, ByteArray>,
    val hasFiles: Boolean = attachments.isNotEmpty()) {
    fun summary() = "病史 ${state.records(MedicalKind.CONDITION).size}、" +
        "住院 ${state.records(MedicalKind.ADMISSION).size}、手术 ${state.records(MedicalKind.SURGERY).size}、" +
        "药物 ${state.records(MedicalKind.MEDICINE).size}、服药记录 ${state.records(MedicalKind.DOSE).size}、" +
        "附件 ${state.files.size} 个"
}

/** Portable plain-text/CSV/ZIP export. All formats are usable without this app. */
internal object MedicalArchive {
    private const val maxArchive = 80 * 1024 * 1024
    private const val backupHeader = "OKG-HEALTH-ENCRYPTED-1\n"
    private const val marker = "# OpenKinGuard-Medical-TXT v1 "

    fun text(state: MedicalState, kind: MedicalKind? = null): String = buildString {
        appendLine(marker + (kind?.key ?: "all"))
        appendLine("导出日期：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}")
        if (kind == null) {
            val portableState = state.copy(files = emptyList(), entries = state.entries.mapValues { (_, list) ->
                list.map { it.copy(attachments = emptyList()) }
            })
            appendLine("# OKG_STATE_JSON " + MedicalVault.encode(portableState).toString())
            appendLine("\n【个人医疗信息】")
            MedicalFields.profile.forEach { (key, label) ->
                appendLine("$label：${state.profile[key].orEmpty()}")
            }
        }
        (kind?.let { listOf(it) } ?: MedicalKind.entries).forEach { section ->
            appendLine("\n【${section.title}】")
            state.records(section).forEachIndexed { index, record ->
                appendLine("\n${index + 1}. ${record.value(section.fields.first().first)}")
                section.fields.forEach { (key, label) -> appendLine("$label：${record.value(key)}") }
                appendLine("关联附件编号：${record.attachments.joinToString(", ")}")
                // A valid JSON record on a dedicated line enables exact re-import without
                // sacrificing the readable lines above it. No executable content is parsed.
                appendLine("# OKG_RECORD_JSON " + MedicalVault.recordJson(record).toString())
            }
        }
        if (kind == null) {
            appendLine("\n【紧急医疗信息】")
            appendLine(state.emergency)
            appendLine("\n【本次情况】")
            MedicalFields.incident.forEach { (key, label) ->
                appendLine("$label：${state.incident[key].orEmpty()}")
            }
            appendLine("\n【医疗附件】")
            state.files.forEach { file -> appendLine("${file.name}（${file.mime}，SHA256 ${file.hash}）") }
        }
    }

    fun csv(state: MedicalState): String = buildString {
        append('\uFEFF')
        appendLine(listOf("药品名称", "计划服药时间", "实际确认时间", "服用剂量", "服药状态", "备注")
            .joinToString(",") { csvCell(it) })
        state.records(MedicalKind.DOSE).forEach { r ->
            appendLine(listOf(r.value("medicineName"), r.value("planned"), r.value("actual"),
                r.value("dose"), r.value("status"), r.value("notes")).joinToString(",") { csvCell(it) })
        }
    }

    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    fun exportZip(output: OutputStream, state: MedicalState, vault: MedicalVault) {
        ZipOutputStream(output).use { zip ->
            var rawSize = 0L
            fun write(path: String, bytes: ByteArray) {
                rawSize += bytes.size
                require(rawSize <= maxArchive) { "档案超过80 MB安全上限，请分批导出" }
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            write("README.txt", ("OpenKinGuard 本地健康档案导出\n" +
                "普通 ZIP 不加密。TXT/CSV/图片/PDF 可以由其他软件直接打开；请自行保管。\n" +
                "manifest.json 含结构化数据和关联关系，适用于重新导入。\n").toByteArray(Charsets.UTF_8))
            write("个人医疗信息.txt", MedicalFields.profile.joinToString("\n") { (key, label) ->
                "$label：${state.profile[key].orEmpty()}"
            }.toByteArray(Charsets.UTF_8))
            write("全部健康资料.txt", text(state).toByteArray(Charsets.UTF_8))
            MedicalKind.entries.forEach { kind ->
                write("${kind.title}.txt", text(state, kind).toByteArray(Charsets.UTF_8))
            }
            write("服药记录.csv", csv(state).toByteArray(Charsets.UTF_8))
            write("紧急医疗信息.txt", state.emergency.toByteArray(Charsets.UTF_8))
            write("本次情况.txt", MedicalFields.incident.joinToString("\n") { (key, label) ->
                "$label：${state.incident[key].orEmpty()}"
            }.toByteArray(Charsets.UTF_8))
            val filesJson = JSONArray()
            state.files.forEach { file ->
                val ext = file.name.substringAfterLast('.', "bin")
                    .take(8).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                    .ifBlank { "bin" }
                val path = "医疗附件/${file.id}.$ext"
                write(path, vault.readFile(file))
                filesJson.put(MedicalVault.fileJson(file).put("path", path))
            }
            write("manifest.json", MedicalVault.encode(state)
                .put("files", filesJson).toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    fun previewZip(input: InputStream): MedicalImport {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= 320) { "压缩包内文件太多" }
                val path = entry.name
                require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
                    path.split('/').none { it == ".." || it == "." }) { "压缩包路径不安全" }
                require(!entries.containsKey(path)) { "压缩包存在重复文件名" }
                if (!entry.isDirectory) {
                    val bytes = MedicalVault.limitedRead(zip, 16 * 1024 * 1024)
                    total += bytes.size
                    require(total <= maxArchive) { "压缩包解压后过大" }
                    entries[path] = bytes
                }
                zip.closeEntry()
            }
        }
        val manifest = entries["manifest.json"] ?: error("缺少 manifest.json；请选择本应用导出的完整 ZIP")
        require(manifest.size < 5 * 1024 * 1024) { "清单文件过大" }
        val root = JSONObject(manifest.toString(Charsets.UTF_8))
        require(root.optInt("formatVersion") == 1) { "不兼容的健康档案版本" }
        val state = MedicalVault.decode(root)
        require(state.files.size <= 300 && MedicalKind.entries.all { kind ->
            state.records(kind).size <= 5_000 && state.records(kind).all { MedicalVault.safeId(it.id) }
        }) { "健康档案记录过多或编号不合法" }
        require(state.files.all { MedicalVault.safeId(it.id) }) { "附件编号不安全" }
        val declaredFiles = root.optJSONArray("files") ?: JSONArray()
        val blobs = mutableMapOf<String, ByteArray>()
        for (i in 0 until declaredFiles.length()) {
            val file = declaredFiles.getJSONObject(i)
            val path = file.getString("path")
            require(path.startsWith("医疗附件/") && !path.contains("..")) { "附件路径异常" }
            val data = entries[path] ?: error("附件缺失：$path")
            val expected = MedicalVault.fileFromJson(file)
            require(data.size == expected.size && MedicalVault.sha(data) == expected.hash) {
                "附件已损坏：${expected.name}"
            }
            blobs[expected.id] = data
        }
        return MedicalImport(state, blobs)
    }

    fun previewText(input: InputStream): MedicalImport {
        val text = MedicalVault.limitedRead(input, 5 * 1024 * 1024).toString(Charsets.UTF_8)
        val first = text.lineSequence().firstOrNull().orEmpty().removePrefix("\uFEFF")
        require(first.startsWith(marker)) {
            "不是 OpenKinGuard 结构化 TXT；可以作为普通医疗附件导入"
        }
        if (first.removePrefix(marker).trim() == "all") {
            val line = text.lineSequence().firstOrNull { it.startsWith("# OKG_STATE_JSON ") }
                ?: error("这个 TXT 缺少结构化恢复信息")
            val root = JSONObject(line.removePrefix("# OKG_STATE_JSON "))
            require(root.optInt("formatVersion") == 1) { "结构化 TXT 版本不兼容" }
            val state = MedicalVault.decode(root)
            require(state.files.isEmpty()) { "TXT 不允许包含文件附件数据" }
            require(MedicalKind.entries.all { kind ->
                state.records(kind).size <= 5_000 && state.records(kind).all { MedicalVault.safeId(it.id) }
            }) { "记录数量或编号不合法" }
            return MedicalImport(state, emptyMap())
        }
        val kind = MedicalKind.entries.firstOrNull { it.key == first.removePrefix(marker).trim() }
            ?: error("未知结构化 TXT 类别")
        val records = text.lineSequence().filter { it.startsWith("# OKG_RECORD_JSON ") }
            .map { MedicalVault.recordFromJson(JSONObject(it.removePrefix("# OKG_RECORD_JSON "))) }
            .toList()
        require(records.size <= 5000) { "记录数量超出限制" }
        require(records.all { MedicalVault.safeId(it.id) }) { "记录编号不合法" }
        return MedicalImport(MedicalVault.emptyState().copy(entries =
            MedicalKind.entries.associateWith { if (it == kind) records else emptyList() }), emptyMap())
    }

    /** Merge: local wins on ID collisions; imported IDs and SHA256s are de-duplicated. */
    fun merge(local: MedicalState, incoming: MedicalImport, vault: MedicalVault): MedicalImport {
        val mergedFiles = local.files.toMutableList()
        val bytes = local.files.associate { it.id to vault.readFile(it) }.toMutableMap()
        val mapping = mutableMapOf<String, String>()
        incoming.state.files.forEach { file ->
            val present = mergedFiles.firstOrNull { it.hash == file.hash }
            if (present != null) mapping[file.id] = present.id
            else {
                val newId = if (mergedFiles.any { it.id == file.id }) UUID.randomUUID().toString() else file.id
                mapping[file.id] = newId
                mergedFiles.add(file.copy(id = newId))
                bytes[newId] = incoming.attachments[file.id] ?: error("附件内容缺失")
            }
        }
        val records = MedicalKind.entries.associateWith { kind ->
            val current = local.records(kind).toMutableList()
            incoming.state.records(kind).forEach { entry ->
                val remapped = entry.copy(attachments = entry.attachments.mapNotNull { mapping[it] })
                val identical = current.any { it.id == remapped.id ||
                    (it.values == remapped.values && it.attachments == remapped.attachments) }
                if (!identical) current.add(remapped)
            }
            current
        }
        val profile = incoming.state.profile + local.profile.filterValues { it.isNotBlank() }
        val state = MedicalState(profile,
            local.emergency.ifBlank { incoming.state.emergency },
            incoming.state.incident + local.incident.filterValues { it.isNotBlank() },
            records, mergedFiles)
        return MedicalImport(state, bytes)
    }

    fun backup(output: OutputStream, state: MedicalState, vault: MedicalVault, password: CharArray) {
        require(password.size >= 10) { "备份密码至少10个字符" }
        val zip = ByteArrayOutputStream()
        exportZip(zip, state, vault)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        output.write(backupHeader.toByteArray(Charsets.UTF_8))
        output.write(salt)
        output.write(cipher.iv)
        output.write(cipher.doFinal(zip.toByteArray()))
        password.fill('\u0000')
    }

    fun restorePreview(input: InputStream, password: CharArray): MedicalImport {
        val bytes = MedicalVault.limitedRead(input, maxArchive + 100)
        val header = backupHeader.toByteArray(Charsets.UTF_8)
        require(bytes.size > header.size + 16 + 12 + 16 &&
            bytes.copyOfRange(0, header.size).contentEquals(header)) { "不是本应用的加密备份" }
        val offset = header.size
        val key = derive(password, bytes.copyOfRange(offset, offset + 16))
        password.fill('\u0000')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(128, bytes.copyOfRange(offset + 16, offset + 28)))
        val zip = try { cipher.doFinal(bytes, offset + 28, bytes.size - offset - 28) }
            catch (_: java.security.GeneralSecurityException) {
                throw IllegalArgumentException("备份密码错误或文件已损坏")
            }
        return previewZip(ByteArrayInputStream(zip))
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return SecretKeySpec(bytes, "AES")
    }
}
