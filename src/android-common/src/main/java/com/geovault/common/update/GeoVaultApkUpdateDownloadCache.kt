package com.geovault.common.update

import android.content.Context
import android.util.Log
import java.io.File

/**
 * On-disk cache for in-app update APK downloads. Matches `gv_apk_updates/` in
 * `res/xml/geovault_update_file_paths.xml` (FileProvider).
 */
object GeoVaultApkUpdateDownloadCache {
    const val DIRECTORY_NAME = "gv_apk_updates"

    fun directory(context: Context): File {
        val dir = File(context.applicationContext.cacheDir, DIRECTORY_NAME)
        dir.mkdirs()
        return dir
    }

    /**
     * Deletes every file in the update cache directory. Safe to call on every process start;
     * failures are logged and ignored so boot is not blocked.
     */
    fun clearAll(context: Context) {
        val dir = File(context.applicationContext.cacheDir, DIRECTORY_NAME)
        if (!dir.isDirectory) return
        runCatching {
            dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        }.onFailure { e ->
            Log.w(UpdateCheckLog.TAG, "Failed clearing APK update cache", e)
        }
    }
}
