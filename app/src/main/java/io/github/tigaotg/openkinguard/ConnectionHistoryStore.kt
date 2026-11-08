package io.github.tigaotg.openkinguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal data class ConnectionHistoryEntry(
    val timeMillis: Long,
    val kind: String,
    val path: String,
    val before: String,
    val after: String,
    val durationMillis: Long
)

private class ConnectionHistoryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "connection-history-v1.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "at INTEGER NOT NULL, kind TEXT NOT NULL, path TEXT NOT NULL, " +
            "before_value TEXT NOT NULL, after_value TEXT NOT NULL, duration_ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX events_at ON events(at)")
        db.execSQL("CREATE TABLE latest (path TEXT PRIMARY KEY, value TEXT NOT NULL, changed_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE meta (name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Unsupported connection-history schema migration")
    }
}

/** A separate, bounded app-private database; identity, binding and health records stay untouched. */
internal object ConnectionHistoryStore {
    @Volatile private var holder: ConnectionHistoryDatabase? = null

    private fun database(context: Context): SQLiteDatabase {
        val existing = holder
        if (existing != null) return existing.writableDatabase
        synchronized(this) {
            val value = holder ?: ConnectionHistoryDatabase(context).also { holder = it }
            return value.writableDatabase
        }
    }

    /** Called on a worker thread, with immutable allowlisted fields only. */
    @Synchronized fun capture(context: Context, values: Map<String, String>,
                              now: Long = System.currentTimeMillis()): Int {
        val current = ConnectionHistoryPolicy.sanitizeState(values)
        if (current.isEmpty()) return 0
        val db = database(context)
        db.beginTransactionNonExclusive()
        try {
            val old = linkedMapOf<String, Pair<String, Long>>()
            db.rawQuery("SELECT path,value,changed_at FROM latest", null).use { cursor ->
                while (cursor.moveToNext()) {
                    old[cursor.getString(0)] = cursor.getString(1) to cursor.getLong(2)
                }
            }
            var count = 0
            for (event in ConnectionHistoryPolicy.transitions(
                    old.mapValues { it.value.first }, current)) {
                val changedAt = old[event.path]?.second ?: now
                add(db, now, if (event.before == "unknown") "initial" else "change",
                    event.path, event.before, event.after, (now - changedAt).coerceAtLeast(0L))
                if (event.after == "removed") db.delete("latest", "path=?", arrayOf(event.path))
                else db.insertWithOnConflict("latest", null, ContentValues().apply {
                    put("path", event.path)
                    put("value", event.after)
                    put("changed_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            val lastSnapshot = db.rawQuery(
                "SELECT value FROM meta WHERE name='snapshot'", null).use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                }
            if (ConnectionHistoryPolicy.shouldSnapshot(lastSnapshot, now)) {
                val sample = JSONObject()
                current.forEach { (key, value) -> sample.put(key, value) }
                add(db, now, "snapshot", "all", "", sample.toString(), 0L)
                db.insertWithOnConflict("meta", null, ContentValues().apply {
                    put("name", "snapshot")
                    put("value", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            db.delete("events", "at<? OR at>?", arrayOf(
                (now - ConnectionHistoryPolicy.RETENTION_MS).toString(), now.toString()))
            db.execSQL("DELETE FROM events WHERE id IN " +
                "(SELECT id FROM events ORDER BY at DESC,id DESC LIMIT -1 OFFSET " +
                ConnectionHistoryPolicy.MAX_ROWS + ")")
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    private fun add(db: SQLiteDatabase, at: Long, kind: String, path: String,
                    before: String, after: String, duration: Long) {
        db.insertOrThrow("events", null, ContentValues().apply {
            put("at", at)
            put("kind", kind)
            put("path", path)
            put("before_value", before)
            put("after_value", after)
            put("duration_ms", duration)
        })
    }

    @Synchronized fun read(context: Context, sinceMillis: Long, kind: String = "all",
                           limit: Int = ConnectionHistoryPolicy.MAX_DISPLAY_ROWS,
                           untilMillis: Long = Long.MAX_VALUE, offset: Int = 0):
        List<ConnectionHistoryEntry> {
        val filter = if (kind in setOf("change", "snapshot", "initial")) kind else "all"
        val where = if (filter == "all") "at>=? AND at<?"
            else "at>=? AND at<? AND kind=?"
        val args = if (filter == "all") arrayOf(sinceMillis.toString(), untilMillis.toString())
            else arrayOf(sinceMillis.toString(), untilMillis.toString(), filter)
        val rows = ArrayList<ConnectionHistoryEntry>()
        database(context).query("events",
            arrayOf("at", "kind", "path", "before_value", "after_value", "duration_ms"),
            where, args, null, null, "at DESC,id DESC",
            "${limit.coerceIn(1, ConnectionHistoryPolicy.MAX_DISPLAY_ROWS)} OFFSET ${offset.coerceIn(0, ConnectionHistoryPolicy.MAX_ROWS)}"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(ConnectionHistoryEntry(cursor.getLong(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getLong(5)))
            }
        }
        return rows
    }

    /** Export the entire rolling seven-day period, without a user-data or network-identity field. */
    fun export(context: Context, asJson: Boolean, now: Long = System.currentTimeMillis()): String {
        val rows = ArrayList<ConnectionHistoryEntry>()
        var offset = 0
        while (offset < ConnectionHistoryPolicy.MAX_ROWS) {
            val batch = read(context, now - ConnectionHistoryPolicy.RETENTION_MS,
                limit = ConnectionHistoryPolicy.MAX_DISPLAY_ROWS, untilMillis = now + 1L,
                offset = offset)
            rows.addAll(batch)
            if (batch.size < ConnectionHistoryPolicy.MAX_DISPLAY_ROWS) break
            offset += batch.size
        }
        return if (asJson) {
            val events = JSONArray()
            rows.asReversed().forEach { item ->
                events.put(JSONObject().put("timeMs", item.timeMillis)
                    .put("kind", item.kind).put("path", item.path)
                    .put("previous", item.before).put("current", item.after)
                    .put("durationMs", item.durationMillis))
            }
            JSONObject().put("format", "OpenKinGuard-connection-history-v1")
                .put("days", 7).put("generatedAtMs", now)
                .put("events", events).toString(2)
        } else buildString {
            appendLine("OpenKinGuard 最近7天连接历史（脱敏诊断）")
            appendLine("生成时间：" + DateFormat.getDateTimeInstance().format(Date(now)))
            appendLine("说明：状态不确定不等于手机关机；进程被系统停止时可能存在记录空档。")
            appendLine("只包含连接状态码和匿名代号，不包含IP、URL、指纹、密钥或原始错误。")
            appendLine("记录数：" + rows.size)
            for (entry in rows.asReversed()) {
                append(DateFormat.getDateTimeInstance().format(Date(entry.timeMillis)))
                append(" | ").append(entry.kind).append(" | ").append(entry.path)
                append(" | ").append(entry.before).append(" -> ").append(entry.after)
                if (entry.durationMillis > 0L) {
                    append(" | 上一状态持续 ").append(entry.durationMillis / 1000L).append("秒")
                }
                appendLine()
            }
        }
    }
}
