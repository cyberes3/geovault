package com.geovault.common.logging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geovault.common.BuildConfig

/**
 * Dumps point recording logs to Downloads via MediaStore. Trigger with
 * `adb shell am broadcast -a com.geovault.common.EXPORT_POINT_RECORDING_LOG -p <package>`.
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
        GeoVaultPointRecordingLogEngine.scheduleExport(context.applicationContext)
    }

    private companion object {
        private const val TAG = "GvPointRecordingExport"
    }
}
