package io.github.tigaotg.openkinguard

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Independently encrypted, never automatically pruned local medical archive.
 * No networking, account, relation or SOS/history store dependency is permitted here.
 */
internal enum class MedicalKind(val key: String, val title: String, val fields: List<Pair<String, String>>) {
    CONDITION("conditions", "既往病史", listOf("name" to "疾病名称", "date" to "确诊日期", "active" to "是否正在治疗", "description" to "病情描述", "treatment" to "治疗经过", "notes" to "备注")),
    ADMISSION("admissions", "住院记录", listOf("hospital" to "医院名称", "department" to "科室", "start" to "入院日期", "end" to "出院日期", "reason" to "入院原因", "diagnosis" to "出院诊断", "treatment" to "治疗经过", "instructions" to "出院医嘱", "notes" to "备注")),
    SURGERY("surgeries", "手术记录", listOf("name" to "手术名称", "date" to "手术日期", "hospital" to "医院名称", "condition" to "相关疾病", "admissionId" to "关联住院记录编号（选填）", "description" to "手术说明", "instructions" to "术后注意事项", "notes" to "备注")),
    MEDICINE("medicines", "我的药物", listOf("name" to "药品名称", "purpose" to "用途", "dose" to "每次服用剂量", "unit" to "剂量单位", "frequency" to "每日服药次数", "times" to "服药时间（08:00,20:00）", "weekdays" to "每周服药日（1-7，留空为每天）", "start" to "开始日期", "end" to "结束日期", "active" to "正在服用（是/否）", "paused" to "暂停提醒（是/否）", "cautions" to "服药注意事项", "instructions" to "医生医嘱", "notes" to "备注")),
    DOSE("doses", "服药记录", listOf("medicineId" to "药品编号", "medicineName" to "药品名称", "planned" to "计划服药时间", "actual" to "实际确认时间", "dose" to "服用剂量", "status" to "状态", "notes" to "备注"))
}

internal data class MedicalEntry(val id: String, val values: Map<String, String>,
    val attachments: List<String> = emptyList(), val updatedAt: Long = System.currentTimeMillis()) {
    fun value(key: String): String = values[key].orEmpty()
}

internal data class MedicalFile(val id: String, val name: String, val mime: String,
    val hash: String, val relatedKind: String, val relatedId: String, val size: Int,
    val note: String = "")

internal data class MedicalState(val profile: Map<String, String>, val emergency: String,
    val incident: Map<String, String>, val entries: Map<MedicalKind, List<MedicalEntry>>,
    val files: List<MedicalFile>) {
    fun records(kind: MedicalKind): List<MedicalEntry> = entries[kind].orEmpty()
}

internal object MedicalFields {
    val profile = listOf("name" to "姓名", "birthday" to "出生日期", "blood" to "血型（可填未知）",
        "height" to "身高", "weight" to "体重", "conditions" to "重要既往疾病",
        "drugAllergies" to "药物过敏史", "otherAllergies" to "其他过敏史",
        "cautions" to "重要医疗注意事项", "contact" to "紧急联系人姓名",
        "phone" to "紧急联系人电话", "notes" to "备注")
    val incident = listOf("symptoms" to "本次症状", "occurred" to "发生时间",
        "events" to "事情经过", "known" to "当前已知情况")
}

/** All public methods are synchronous and serialized. UI must call large imports/exports off main. */
internal class MedicalVault(private val context: Context) {
    private val directory = File(context.filesDir, "medical_vault_v1").apply { mkdirs() }
    private val content = AtomicFile(File(directory, "medical.enc"))
    private val fileDirectory = File(directory, "attachments").apply { mkdirs() }
    private val key: SecretKey by lazy { loadAesKey() }
    private val maxFile = 16 * 1024 * 1024

    @Synchronized fun snapshot(): MedicalState = decode(load())

    @Synchronized fun saveProfile(values: Map<String, String>) = mutate { root ->
        root.put("profile", mapJson(values))
    }

    @Synchronized fun saveEmergency(text: String, incident: Map<String, String>) = mutate { root ->
        root.put("emergency", text.take(30_000)); root.put("incident", mapJson(incident))
    }

    @Synchronized fun put(kind: MedicalKind, entry: MedicalEntry) = mutate { root ->
        val array = root.getJSONObject("entries").getJSONArray(kind.key)
        val newer = entry.copy(updatedAt = System.currentTimeMillis())
        val index = (0 until array.length()).firstOrNull { array.getJSONObject(it).optString("id") == entry.id }
        if (index == null) array.put(recordJson(newer)) else array.put(index, recordJson(newer))
    }

    @Synchronized fun remove(kind: MedicalKind, id: String) = mutate { root ->
        val list = root.getJSONObject("entries").getJSONArray(kind.key)
        val replace = JSONArray()
        for (i in 0 until list.length()) if (list.getJSONObject(i).optString("id") != id)
            replace.put(list.getJSONObject(i))
        root.getJSONObject("entries").put(kind.key, replace)
        // Attachments remain in the user's file library and can be reassigned or deleted explicitly.
    }

    /** Copies the provider content immediately; no temporary external URI is retained. */
    @Synchronized fun addFile(uri: Uri, name: String, mime: String,
        kind: String = "", relatedId: String = ""): MedicalFile {
        val bytes = context.contentResolver.openInputStream(uri)?.use { limitedRead(it, maxFile) }
            ?: error("无法读取所选文件")
        return addFileBytes(bytes, name, mime, kind, relatedId)
    }

    @Synchronized fun addFileBytes(bytes: ByteArray, name: String, mime: String,
        kind: String = "", relatedId: String = ""): MedicalFile {
        require(bytes.size <= maxFile) { "单个附件不能超过16 MB" }
        val hash = sha(bytes)
        val existing = snapshot().files.firstOrNull { it.hash == hash }
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        val file = MedicalFile(id, name.take(120).ifBlank { "医疗附件" }, mime.take(120), hash,
            kind, relatedId, bytes.size)
        val encrypted = File(fileDirectory, "$id.enc")
        encrypted.writeBytes(crypt(bytes, true))
        try {
            mutate { it.getJSONArray("files").put(fileJson(file)) }
        } catch (error: Exception) { encrypted.delete(); throw error }
        return file
    }

    @Synchronized fun deleteFile(id: String) {
        mutate { root ->
            val old = root.getJSONArray("files")
            val remaining = JSONArray()
            for (i in 0 until old.length()) if (old.getJSONObject(i).optString("id") != id)
                remaining.put(old.getJSONObject(i))
            root.put("files", remaining)
            MedicalKind.entries.forEach { kind ->
                val records = root.getJSONObject("entries").getJSONArray(kind.key)
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)
                    val linked = record.optJSONArray("attachments") ?: continue
                    val keep = JSONArray()
                    for (j in 0 until linked.length()) if (linked.optString(j) != id)
                        keep.put(linked.optString(j))
                    record.put("attachments", keep)
                }
            }
        }
        File(fileDirectory, "$id.enc").delete()
    }

    @Synchronized fun updateFileInfo(id: String, title: String, note: String) = mutate { root ->
        require(title.isNotBlank()) { "附件标题不能为空" }
        val list = root.getJSONArray("files")
        val file = (0 until list.length()).map { list.getJSONObject(it) }
            .firstOrNull { it.optString("id") == id } ?: error("附件不存在")
        file.put("name", title.trim().take(120))
        file.put("note", note.take(2000))
    }

    @Synchronized fun linkFile(kind: MedicalKind, recordId: String, fileId: String) {
        val record = snapshot().records(kind).firstOrNull { it.id == recordId }
            ?: error("找不到关联记录")
        require(snapshot().files.any { it.id == fileId }) { "找不到附件" }
        put(kind, record.copy(attachments = (record.attachments + fileId).distinct()))
    }

    @Synchronized fun readFile(file: MedicalFile): ByteArray {
        val bytes = crypt(File(fileDirectory, "${file.id}.enc").readBytes(), false)
        check(sha(bytes) == file.hash) { "附件校验失败" }
        return bytes
    }

    @Synchronized fun replaceForRestore(state: MedicalState, files: Map<String, ByteArray>) {
        // Validate and encrypt all new files before replacing the state. Existing files are retained.
        state.files.forEach { file ->
            require(safeId(file.id)) { "附件编号不合法" }
            val bytes = files[file.id] ?: error("备份中缺少附件：${file.name}")
            require(sha(bytes) == file.hash && bytes.size <= maxFile) { "附件校验失败" }
        }
        val created = mutableListOf<File>()
        try {
            state.files.forEach { file ->
                val target = File(fileDirectory, "${file.id}.enc")
                if (!target.exists()) { target.writeBytes(crypt(files.getValue(file.id), true)); created.add(target) }
            }
            save(encode(state))
        } catch (error: Exception) { created.forEach { it.delete() }; throw error }
    }

    private fun mutate(block: (JSONObject) -> Unit) {
        val root = load(); block(root); save(root)
    }

    private fun load(): JSONObject {
        if (!content.baseFile.exists()) return emptyRoot()
        val bytes = content.openRead().use { limitedRead(it, 8 * 1024 * 1024) }
        val root = JSONObject(crypt(bytes, false).toString(Charsets.UTF_8))
        require(root.optInt("formatVersion") == 1) { "不支持当前健康档案版本" }
        return root
    }

    private fun save(root: JSONObject) {
        val bytes = crypt(root.toString().toByteArray(Charsets.UTF_8), true)
        val output = content.startWrite()
        try { output.write(bytes); content.finishWrite(output) }
        catch (error: Exception) { content.failWrite(output); throw error }
    }

    private fun loadAesKey(): SecretKey {
        val alias = "openkinguard_medical_vault_aes_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun crypt(bytes: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (encrypt) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(bytes)
        }
        require(bytes.size >= 29) { "加密数据损坏" }
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    companion object {
        fun freshId(): String = UUID.randomUUID().toString()
        fun safeId(id: String): Boolean = id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun limitedRead(input: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(out.size() + read <= max) { "文件过大，已取消操作" }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
        fun emptyState() = MedicalState(emptyMap(), "", emptyMap(),
            MedicalKind.entries.associateWith { emptyList() }, emptyList())
        fun emptyRoot() = encode(emptyState())
        fun encode(state: MedicalState) = JSONObject().apply {
            put("formatVersion", 1)
            put("profile", mapJson(state.profile))
            put("emergency", state.emergency)
            put("incident", mapJson(state.incident))
            put("entries", JSONObject().apply { MedicalKind.entries.forEach { kind ->
                put(kind.key, JSONArray().apply { state.records(kind).forEach { put(recordJson(it)) } })
            } })
            put("files", JSONArray().apply { state.files.forEach { put(fileJson(it)) } })
        }
        fun decode(root: JSONObject): MedicalState {
            val entries = root.optJSONObject("entries") ?: JSONObject()
            return MedicalState(jsonMap(root.optJSONObject("profile") ?: JSONObject()),
                root.optString("emergency"), jsonMap(root.optJSONObject("incident") ?: JSONObject()),
                MedicalKind.entries.associateWith { kind ->
                    val array = entries.optJSONArray(kind.key) ?: JSONArray()
                    (0 until array.length()).map { recordFromJson(array.getJSONObject(it)) }
                }, (root.optJSONArray("files") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { fileFromJson(arr.getJSONObject(it)) }
                })
        }
        fun mapJson(map: Map<String, String>) = JSONObject().apply {
            map.forEach { (k, v) -> put(k.take(100), v.take(30_000)) }
        }
        fun jsonMap(json: JSONObject): Map<String, String> = json.keys().asSequence()
            .associateWith { json.optString(it) }
        fun recordJson(entry: MedicalEntry) = JSONObject().apply {
            put("id", entry.id); put("values", mapJson(entry.values))
            put("attachments", JSONArray(entry.attachments)); put("updatedAt", entry.updatedAt)
        }
        fun recordFromJson(json: JSONObject) = MedicalEntry(json.getString("id"),
            jsonMap(json.optJSONObject("values") ?: JSONObject()),
            (json.optJSONArray("attachments") ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getString(it) }
            }, json.optLong("updatedAt"))
        fun fileJson(file: MedicalFile) = JSONObject().apply {
            put("id", file.id); put("name", file.name); put("mime", file.mime)
            put("hash", file.hash); put("relatedKind", file.relatedKind)
            put("relatedId", file.relatedId); put("size", file.size); put("note", file.note)
        }
        fun fileFromJson(j: JSONObject) = MedicalFile(j.getString("id"), j.getString("name"),
            j.optString("mime"), j.getString("hash"), j.optString("relatedKind"),
            j.optString("relatedId"), j.optInt("size"), j.optString("note"))
    }
}
