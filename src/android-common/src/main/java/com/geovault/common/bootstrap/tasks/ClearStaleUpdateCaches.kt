package com.geovault.common.bootstrap.tasks

import android.content.Context
import com.geovault.common.bootstrap.GateTask
import com.geovault.common.update.GeoVaultApkUpdateDownloadCache
import com.geovault.common.update.UpdateAvailableCacheStore

/**
 * Joint wipe of APK bytes and update-metadata cache on every cold start.
 * Resume-from-partial-download is not implemented; both caches share this lifecycle.
 */
class ClearStaleUpdateCaches : GateTask("clear-stale-update-caches") {
    override suspend fun execute(context: Context) {
        GeoVaultApkUpdateDownloadCache.clearAll(context)
        UpdateAvailableCacheStore.clearAll(context)
    }
}
