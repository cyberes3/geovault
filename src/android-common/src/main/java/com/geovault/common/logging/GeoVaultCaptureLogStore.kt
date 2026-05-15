package com.geovault.common.logging

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class GeoVaultCaptureLogStore(
    context: Context,
    private val maxStoredBytes: Long = MAX_STORED_BYTES,
) : SQLiteOpenHelper(
        context.applicationContext,
        DB_NAME,
        null,
        DB_VERSION,
    ) {

    init {
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
            val sql =
                "SELECT wall_time_ms, level, tag, message, throwable FROM capture_log ORDER BY id ASC"
            readableDatabase.rawQuery(sql, null).use { cursor ->
                val ixTime = cursor.getColumnIndexOrThrow("wall_time_ms")
                val ixLevel = cursor.getColumnIndexOrThrow("level")
                val ixTag = cursor.getColumnIndexOrThrow("tag")
                val ixMessage = cursor.getColumnIndexOrThrow("message")
                val ixThrowable = cursor.getColumnIndexOrThrow("throwable")
                while (cursor.moveToNext()) {
                    val t = cursor.getLong(ixTime)
                    val lvl = cursor.getInt(ixLevel)
                    val tg = cursor.getString(ixTag)
                    val msg = cursor.getString(ixMessage)
                    val thr = cursor.getString(ixThrowable)
                    val line =
                        buildString {
                            append(ISO_INSTANT.format(Instant.ofEpochMilli(t)))
                            append('\t')
                            append(levelName(lvl))
                            append('\t')
                            append(tg)
                            append('\t')
                            append(msg.replace("\n", "\\n"))
                            if (thr != null) {
                                append("\n----\n")
                                append(thr.replace("\n", "\\n"))
                            }
                            append('\n')
                        }
                    writer.write(line)
                }
            }
        }
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
            val deleted =
                db.delete(
                    "capture_log",
                    "id = (SELECT MIN(id) FROM capture_log)",
                    null,
                )
            if (deleted == 0) {
                break
            }
            total = totalApproxBytes(db)
        }
    }

    companion object {
        internal const val DB_NAME = "geovault_capture_log.sqlite"
        private const val DB_VERSION = 1

        internal const val MAX_STORED_BYTES = 100L * 1024L * 1024L
        private const val PRUNE_TARGET_RATIO = 0.85

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
