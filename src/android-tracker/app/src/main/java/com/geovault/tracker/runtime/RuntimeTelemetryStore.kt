package com.geovault.tracker.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.geovault.common.logging.cacheDatabasePathPublic
import com.geovault.common.logging.deleteCacheDatabaseFiles

/**
 * SQLite-backed ring store for structured runtime telemetry events.
 *
 * Each event is stored as a `{wallTimeMs}|{name}|{details}` line, mirroring the
 * format consumed by replay test assertions. The store prunes oldest rows once the
 * total approximate byte size exceeds [maxStoredBytes] (default 100 MB), matching
 * the same capacity contract as the capture-log infrastructure.
 *
 * Unlike [com.geovault.common.logging.GeoVaultBufferedLogSqliteStore] (which uses
 * system wall-clock time), this store accepts caller-supplied [wallTimeMs] values so
 * the replay clock is faithfully preserved in test environments.
 */
internal class RuntimeTelemetryStore(
    context: Context,
    maxStoredBytes: Long = MAX_STORED_BYTES,
) : SQLiteOpenHelper(
    context.applicationContext,
    cacheDatabasePath(context),
    null,
    DB_VERSION,
) {
    private val maxStoredBytes = maxStoredBytes

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE telemetry_event (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                wall_time_ms INTEGER NOT NULL,
                name         TEXT NOT NULL,
                details      TEXT NOT NULL,
                approx_bytes INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_telemetry_event_id ON telemetry_event(id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS telemetry_event")
        onCreate(db)
    }

    fun insert(wallTimeMs: Long, name: String, details: String) {
        val safeName = name.take(MAX_NAME_CHARS)
        val safeDetails = details.take(MAX_DETAILS_CHARS)
        val approx = safeName.toByteArray(Charsets.UTF_8).size + safeDetails.toByteArray(Charsets.UTF_8).size + 24
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insert(
                "telemetry_event",
                null,
                ContentValues().apply {
                    put("wall_time_ms", wallTimeMs)
                    put("name", safeName)
                    put("details", safeDetails)
                    put("approx_bytes", approx)
                },
            )
            pruneIfNeeded(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readAllLines(): List<String> {
        val lines = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT wall_time_ms, name, details FROM telemetry_event ORDER BY id ASC",
            null,
        ).use { cursor ->
            val ixTime = cursor.getColumnIndexOrThrow("wall_time_ms")
            val ixName = cursor.getColumnIndexOrThrow("name")
            val ixDetails = cursor.getColumnIndexOrThrow("details")
            while (cursor.moveToNext()) {
                lines.add("${cursor.getLong(ixTime)}|${cursor.getString(ixName)}|${cursor.getString(ixDetails)}")
            }
        }
        return lines
    }

    fun clear() {
        writableDatabase.delete("telemetry_event", null, null)
    }

    private fun pruneIfNeeded(db: SQLiteDatabase) {
        var total = totalApproxBytes(db)
        if (total <= maxStoredBytes) return
        val target = (maxStoredBytes * PRUNE_TARGET_RATIO).toLong()
        while (total > target) {
            val cutoffId = oldestCutoffId(db, total - target) ?: break
            val deleted = db.delete("telemetry_event", "id <= ?", arrayOf(cutoffId.toString()))
            if (deleted == 0) break
            total = totalApproxBytes(db)
        }
    }

    private fun oldestCutoffId(db: SQLiteDatabase, bytesToRemove: Long): Long? {
        var cutoffId: Long? = null
        var removed = 0L
        db.rawQuery(
            "SELECT id, approx_bytes FROM telemetry_event ORDER BY id ASC LIMIT ?",
            arrayOf(PRUNE_BATCH_ROWS.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cutoffId = cursor.getLong(0)
                removed += cursor.getLong(1)
                if (removed >= bytesToRemove) break
            }
        }
        return cutoffId
    }

    private fun totalApproxBytes(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT IFNULL(SUM(approx_bytes), 0) FROM telemetry_event", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    companion object {
        private const val DB_VERSION = 1
        private const val DB_FILE_NAME = "geovault_telemetry.sqlite"
        private const val MAX_NAME_CHARS = 256
        private const val MAX_DETAILS_CHARS = 16_384
        private const val PRUNE_TARGET_RATIO = 0.85
        private const val PRUNE_BATCH_ROWS = 2_000

        internal const val MAX_STORED_BYTES = 100L * 1024L * 1024L

        private fun cacheDatabasePath(context: Context): String =
            cacheDatabasePathPublic(context, DB_FILE_NAME)

        fun deleteStore(context: Context) {
            deleteCacheDatabaseFiles(context, DB_FILE_NAME)
        }
    }
}
