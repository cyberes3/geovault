package com.geovault.common.logging

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.time.Instant

internal object GeoVaultCaptureLogDownloadsExport {

    private const val TAG = "GeoVaultCaptureLogExport"

    fun export(context: Context, store: GeoVaultCaptureLogStore) {
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
            GeoVaultCaptureLogFilename.buildExportDisplayName(appLabel, Instant.now())

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

        val uri =
            resolver.insert(collection, values)
                ?: run {
                    Log.e(TAG, "MediaStore insert returned null")
                    return
                }

        try {
            resolver.openOutputStream(uri)?.use { raw ->
                raw.buffered().use { buffered ->
                    store.streamLogsAsText(buffered)
                }
            }
                ?: run {
                    Log.e(TAG, "openOutputStream returned null")
                    resolver.delete(uri, null, null)
                    return
                }
        } catch (e: Exception) {
            Log.e(TAG, "export write failed", e)
            resolver.delete(uri, null, null)
            return
        }

        val done = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        resolver.update(uri, done, null, null)
        Log.i(
            TAG,
            "Exported capture log DISPLAY_NAME=$displayName URI=$uri " +
                "adb_pull_hint=/storage/emulated/0/Download/$displayName",
        )
    }
}
