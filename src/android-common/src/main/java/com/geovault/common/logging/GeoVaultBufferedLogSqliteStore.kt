package com.geovault.common.logging

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class GeoVaultBufferedLogSnapshotBounds(
    val minId: Long,
    val maxId: Long,
    val rowCount: Long,
    val approxBytes: Long,
)

internal data class GeoVaultBufferedLogStreamResult(
    val rowsWritten: Long,
    val lastId: Long,
)

internal open class GeoVaultBufferedLogSqliteStore(
    context: Context,
    private val dbFileName: String,
    private val maxStoredBytes: Long,
) : SQLiteOpenHelper(
        context.applicationContext,
        GeoVaultLoggingDatabasePaths.cacheDatabasePath(context, dbFileName),
        null,
        DB_VERSION,
    ) {

    init {
        GeoVaultLoggingDatabasePaths.deleteLegacyLoggingDatabaseIfPresent(context, dbFileName)
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE capture_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                wall_time_ms INTEGER NOT NULL,
                level INTEGER NOT NULL,
                tag TEXT NOT NULL,
                message TEXT NOT NULL,
                throwable TEXT,
                approx_bytes INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_capture_log_id ON capture_log(id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unexpected upgrade from $oldVersion to $newVersion")
    }

    fun insertLog(level: Int, tag: String, message: String, throwable: String?) {
        val safeTag = tag.take(MAX_TAG_CHARS)
        val safeMessage = message.take(MAX_MESSAGE_CHARS)
        val safeThrowable = throwable?.take(MAX_THROWABLE_CHARS)
        val wallMs = System.currentTimeMillis()
        val approx =
            utf8ByteCount(safeTag) +
                utf8ByteCount(safeMessage) +
                (safeThrowable?.let { utf8ByteCount(it) } ?: 0) +
                32
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv =
                ContentValues().apply {
                    put("wall_time_ms", wallMs)
                    put("level", level)
                    put("tag", safeTag)
                    put("message", safeMessage)
                    if (safeThrowable != null) {
                        put("throwable", safeThrowable)
                    } else {
                        putNull("throwable")
                    }
                    put("approx_bytes", approx)
                }
            db.insert("capture_log", null, cv)
            pruneIfNeeded(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun streamLogsAsText(out: OutputStream) {
        OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
            streamLogsAsText(
                writer = writer,
                maxIdInclusive = snapshotBounds().maxId,
            )
        }
    }

    fun streamLogsAsText(
        writer: Writer,
        maxIdInclusive: Long,
        pageSize: Int = DEFAULT_EXPORT_PAGE_SIZE,
        onProgress: (GeoVaultBufferedLogStreamResult) -> Unit = {},
    ): GeoVaultBufferedLogStreamResult {
        var lastId = 0L
        var rowsWritten = 0L
        if (maxIdInclusive <= 0L) {
            writer.flush()
            return GeoVaultBufferedLogStreamResult(rowsWritten = 0L, lastId = 0L)
        }
        while (true) {
            var pageRows = 0
            readableDatabase.rawQuery(
                """
                SELECT id, wall_time_ms, level, tag, message, throwable
                FROM capture_log
                WHERE id > ? AND id <= ?
                ORDER BY id ASC
                LIMIT ?
                """.trimIndent(),
                arrayOf(lastId.toString(), maxIdInclusive.toString(), pageSize.toString()),
            ).use { cursor ->
                val ixId = cursor.getColumnIndexOrThrow("id")
                val ixTime = cursor.getColumnIndexOrThrow("wall_time_ms")
                val ixLevel = cursor.getColumnIndexOrThrow("level")
                val ixTag = cursor.getColumnIndexOrThrow("tag")
                val ixMessage = cursor.getColumnIndexOrThrow("message")
                val ixThrowable = cursor.getColumnIndexOrThrow("throwable")
                while (cursor.moveToNext()) {
                    lastId = cursor.getLong(ixId)
                    rowsWritten++
                    pageRows++
                    writer.write(
                        formattedLine(
                            wallTimeMs = cursor.getLong(ixTime),
                            level = cursor.getInt(ixLevel),
                            tag = cursor.getString(ixTag),
                            message = cursor.getString(ixMessage),
                            throwable = cursor.getString(ixThrowable),
                        ),
                    )
                }
            }
            writer.flush()
            val progress = GeoVaultBufferedLogStreamResult(rowsWritten = rowsWritten, lastId = lastId)
            onProgress(progress)
            if (pageRows < pageSize || lastId >= maxIdInclusive) {
                return progress
            }
        }
    }

    fun snapshotBounds(db: SQLiteDatabase = readableDatabase): GeoVaultBufferedLogSnapshotBounds {
        db.rawQuery(
            "SELECT IFNULL(MIN(id), 0), IFNULL(MAX(id), 0), COUNT(*), IFNULL(SUM(approx_bytes), 0) FROM capture_log",
            null,
        ).use { c ->
            if (c.moveToFirst()) {
                return GeoVaultBufferedLogSnapshotBounds(
                    minId = c.getLong(0),
                    maxId = c.getLong(1),
                    rowCount = c.getLong(2),
                    approxBytes = c.getLong(3),
                )
            }
        }
        return GeoVaultBufferedLogSnapshotBounds(minId = 0L, maxId = 0L, rowCount = 0L, approxBytes = 0L)
    }

    fun totalApproxBytes(db: SQLiteDatabase = readableDatabase): Long {
        db.rawQuery("SELECT IFNULL(SUM(approx_bytes), 0) FROM capture_log", null).use { c ->
            if (c.moveToFirst()) {
                return c.getLong(0)
            }
        }
        return 0L
    }

    private fun pruneIfNeeded(db: SQLiteDatabase) {
        var total = totalApproxBytes(db)
        if (total <= maxStoredBytes) {
            return
        }
        val targetBytes = (maxStoredBytes * PRUNE_TARGET_RATIO).toLong()
        while (total > targetBytes) {
            val cutoffId = oldestCutoffIdForPrune(db, bytesToRemove = total - targetBytes) ?: break
            val deleted = db.delete("capture_log", "id <= ?", arrayOf(cutoffId.toString()))
            if (deleted == 0) break
            total = totalApproxBytes(db)
        }
    }

    private fun oldestCutoffIdForPrune(db: SQLiteDatabase, bytesToRemove: Long): Long? {
        var cutoffId: Long? = null
        var removedBytes = 0L
        db.rawQuery(
            "SELECT id, approx_bytes FROM capture_log ORDER BY id ASC LIMIT ?",
            arrayOf(PRUNE_BATCH_ROWS.toString()),
        ).use { c ->
            val ixId = c.getColumnIndexOrThrow("id")
            val ixBytes = c.getColumnIndexOrThrow("approx_bytes")
            while (c.moveToNext()) {
                cutoffId = c.getLong(ixId)
                removedBytes += c.getLong(ixBytes)
                if (removedBytes >= bytesToRemove) break
            }
        }
        return cutoffId
    }

    private fun formattedLine(
        wallTimeMs: Long,
        level: Int,
        tag: String,
        message: String,
        throwable: String?,
    ): String {
        return buildString {
            append(ISO_INSTANT.format(Instant.ofEpochMilli(wallTimeMs)))
            append('\t')
            append(levelName(level))
            append('\t')
            append(tag)
            append('\t')
            append(message.replace("\n", "\\n"))
            if (throwable != null) {
                append("\n----\n")
                append(throwable.replace("\n", "\\n"))
            }
            append('\n')
        }
    }

    companion object {
        private const val DB_VERSION = 1
        private const val PRUNE_TARGET_RATIO = 0.85
        private const val DEFAULT_EXPORT_PAGE_SIZE = 2_000
        private const val PRUNE_BATCH_ROWS = 2_000

        internal const val MAX_TAG_CHARS = 256
        internal const val MAX_MESSAGE_CHARS = 16_384
        internal const val MAX_THROWABLE_CHARS = 32_768

        private val ISO_INSTANT: DateTimeFormatter =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

        private fun utf8ByteCount(s: String): Int = s.toByteArray(StandardCharsets.UTF_8).size

        private fun levelName(level: Int): String =
            when (level) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                Log.ASSERT -> "A"
                else -> level.toString()
            }
    }
}
