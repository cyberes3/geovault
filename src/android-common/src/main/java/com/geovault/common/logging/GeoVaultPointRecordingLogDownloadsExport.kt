package com.geovault.common.logging

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.GZIPOutputStream

internal data class GeoVaultPointRecordingLogExportResult(
    val requestId: Long,
    val displayName: String,
    val rowsWritten: Long,
    val compressedBytesWritten: Long,
    val durationMs: Long,
    val maxIdAtStart: Long,
)

internal object GeoVaultPointRecordingLogDownloadsExport {

    private const val TAG = "GeoVaultPointRecordingExport"

    fun export(
        context: Context,
        store: GeoVaultPointRecordingLogStore,
        requestId: Long,
    ): GeoVaultPointRecordingLogExportResult? {
        val startedMs = System.currentTimeMillis()
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val pm = appContext.packageManager
        val appLabel =
            try {
                pm.getApplicationLabel(appContext.applicationInfo).toString()
            } catch (_: Exception) {
                appContext.packageName
            }
        val displayName =
            GeoVaultPointRecordingLogFilename.buildExportDisplayName(appLabel, Instant.now(), compressed = true)
        val bounds = store.snapshotBounds()
        Log.i(
            TAG,
            "point_recording_export_start requestId=$requestId minId=${bounds.minId} maxId=${bounds.maxId} " +
                "rowCount=${bounds.rowCount} approxBytes=${bounds.approxBytes} displayName=$displayName",
        )

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/gzip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

        val uri =
            resolver.insert(collection, values)
                ?: run {
                    Log.e(TAG, "point_recording_export_failed requestId=$requestId error=mediastore_insert_null")
                    return null
                }

        var rowsWritten = 0L
        var compressedBytesWritten = 0L
        try {
            resolver.openOutputStream(uri)?.use { raw ->
                val counting = CountingOutputStream(raw.buffered())
                GZIPOutputStream(counting).use { gzip ->
                    OutputStreamWriter(gzip, StandardCharsets.UTF_8).use { writer ->
                        writer.write("# GeoVault point recording log export\n")
                        writer.write("# request_id=$requestId\n")
                        writer.write("# app_label=${appLabel.replace("\n", "\\n")}\n")
                        writer.write("# package=${appContext.packageName}\n")
                        writer.write("# started_at=${Instant.ofEpochMilli(startedMs)}\n")
                        writer.write("# min_id=${bounds.minId}\n")
                        writer.write("# max_id=${bounds.maxId}\n")
                        writer.write("# snapshot_row_count=${bounds.rowCount}\n")
                        writer.write("# approx_bytes=${bounds.approxBytes}\n")
                        writer.write("# format=ISO_INSTANT<TAB>LEVEL<TAB>TAG<TAB>MESSAGE\n")
                        writer.write("# ----\n")
                        var lastProgressAtMs = startedMs
                        val result = store.streamLogsAsText(
                            writer = writer,
                            maxIdInclusive = bounds.maxId,
                        ) { progress ->
                            rowsWritten = progress.rowsWritten
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastProgressAtMs >= PROGRESS_LOG_INTERVAL_MS) {
                                lastProgressAtMs = nowMs
                                Log.i(
                                    TAG,
                                    "point_recording_export_progress requestId=$requestId rows=${progress.rowsWritten} " +
                                        "lastId=${progress.lastId} compressedBytes=${counting.bytesWritten}",
                                )
                            }
                        }
                        rowsWritten = result.rowsWritten
                        writer.write("# ----\n")
                        writer.write("# finished_at=${Instant.now()}\n")
                        writer.write("# rows_written=$rowsWritten\n")
                    }
                }
                compressedBytesWritten = counting.bytesWritten
            }
                ?: run {
                    Log.e(TAG, "point_recording_export_failed requestId=$requestId error=open_output_stream_null")
                    resolver.delete(uri, null, null)
                    return null
                }
        } catch (e: Exception) {
            Log.e(TAG, "point_recording_export_failed requestId=$requestId error=write_failed", e)
            resolver.delete(uri, null, null)
            return null
        }

        val done = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        resolver.update(uri, done, null, null)
        val durationMs = System.currentTimeMillis() - startedMs
        Log.i(
            TAG,
            "point_recording_export_done requestId=$requestId displayName=$displayName rows=$rowsWritten " +
                "compressedBytes=$compressedBytesWritten durationMs=$durationMs maxId=${bounds.maxId} " +
                "uri=$uri adb_pull_hint=/storage/emulated/0/Download/$displayName",
        )
        return GeoVaultPointRecordingLogExportResult(
            requestId = requestId,
            displayName = displayName,
            rowsWritten = rowsWritten,
            compressedBytesWritten = compressedBytesWritten,
            durationMs = durationMs,
            maxIdAtStart = bounds.maxId,
        )
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len.toLong()
        }
    }

    private const val PROGRESS_LOG_INTERVAL_MS = 2_000L
}
