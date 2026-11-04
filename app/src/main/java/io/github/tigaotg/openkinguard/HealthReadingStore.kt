package io.github.tigaotg.openkinguard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.tigaotg.openkinguard.core.engine.HealthMetricKind
import io.github.tigaotg.openkinguard.core.engine.HealthMetricValue
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private, opt-in encrypted records. Android Keystore retains the AES key on device.
 * Measurement payloads and device display names are encrypted independently with AES/GCM.
 * The timestamp remains indexed in SQLite so the user can enforce local retention.
 */
class HealthReadingStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "openkinguard_health_v1.db", null, 1
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "openkinguard_health_history_v1", Context.MODE_PRIVATE)
    private val secretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadOrCreateKey() }

    var enabled: Boolean
        get() = preferences.getBoolean("history_enabled", false)
        set(value) { preferences.edit().putBoolean("history_enabled", value).apply() }

    var retentionDays: Int
        get() = preferences.getInt("retention_days", 30).takeIf { it in listOf(7, 30, 90) } ?: 30
        set(value) {
            require(value in listOf(7, 30, 90))
            preferences.edit().putInt("retention_days", value).apply()
            prune()
        }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_ms INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX readings_time ON readings(timestamp_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No schema migrations yet; do not destroy health history on future upgrades.
        error("Unsupported health history schema update: $oldVersion -> $newVersion")
    }

    @Synchronized fun append(records: List<HealthBleReading>) {
        if (!enabled || records.isEmpty()) return
        val database = writableDatabase
        database.beginTransaction()
        try {
            records.forEach { record ->
                val content = JSONObject().apply {
                    put("kind", record.metric.kind.name)
                    put("value", record.metric.value)
                    put("unit", record.metric.unit)
                    put("display", record.metric.display)
                    if (record.metric.secondary != null) put("secondary", record.metric.secondary)
                    put("device", record.deviceName)
                }.toString().toByteArray(Charsets.UTF_8)
                val payload = encrypt(content)
                database.execSQL("INSERT INTO readings(timestamp_ms, payload) VALUES(?, ?)",
                    arrayOf(record.capturedAt, payload))
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        prune()
    }

    @Synchronized fun latest(limit: Int = 100): List<HealthBleReading> {
        prune()
        val safeLimit = limit.coerceIn(1, 5_000)
        return readableDatabase.rawQuery(
            "SELECT timestamp_ms, payload FROM readings ORDER BY timestamp_ms DESC, id DESC LIMIT $safeLimit",
            null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val timestamp = cursor.getLong(0)
                    val payload = cursor.getBlob(1)
                    runCatching {
                        val json = JSONObject(decrypt(payload).toString(Charsets.UTF_8))
                        val kind = HealthMetricKind.valueOf(json.getString("kind"))
                        val metric = HealthMetricValue(kind, json.getDouble("value"),
                            json.getString("unit"), json.getString("display"),
                            if (json.has("secondary")) json.getDouble("secondary") else null)
                        HealthBleReading(metric, json.getString("device"), timestamp)
                    }.getOrNull()?.let { add(it) }
                }
            }
        }
    }

    @Synchronized fun count(): Int {
        prune()
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    @Synchronized fun clear() {
        writableDatabase.delete("readings", null, null)
    }

    @Synchronized private fun prune() {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        writableDatabase.execSQL("DELETE FROM readings WHERE timestamp_ms < ?", arrayOf(cutoff))
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "openkinguard_health_history_aes_v1"
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= 29) { "Corrupt encrypted health record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}
