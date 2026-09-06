package com.geovault.tracker.logging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geovault.tracker.BuildConfig

/**
 * Dumps point recording logs to Downloads via MediaStore. Trigger with
 * `adb shell am broadcast -n <package>/com.geovault.tracker.logging.GeoVaultPointRecordingLogExportReceiver -a com.geovault.tracker.EXPORT_POINT_RECORDING_LOG`.
 */
class GeoVaultPointRecordingLogExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != GeoVaultPointRecordingLogIntents.ACTION_EXPORT_POINT_RECORDING_LOG) {
            return
        }
        if (!BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED) {
            Log.e(
                TAG,
                "EXPORT_POINT_RECORDING_LOG broadcast ignored: this build was compiled without point recording " +
                    "(rebuild with -PGEOVAULT_ADD_RECORDING=true or ./build-android.sh ... --add-recording).",
            )
            return
        }
        GeoVaultPointRecordingLog.exportToDownloads(context.applicationContext)
    }

    private companion object {
        private const val TAG = "GvPointRecordingExport"
    }
}
