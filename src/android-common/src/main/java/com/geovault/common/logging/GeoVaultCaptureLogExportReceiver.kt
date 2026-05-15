package com.geovault.common.logging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geovault.common.BuildConfig

/**
 * Dumps capture logs to Downloads via MediaStore. Trigger with
 * `adb shell am broadcast -a com.geovault.common.EXPORT_CAPTURE_LOG -p <package>`.
 *
 * Pull from host, for example:
 * `adb pull "/storage/emulated/0/Download/<DISPLAY_NAME from logcat>" .`
 * Use the release `applicationId` with `-p` when testing a release build (not the `.debug` suffix).
 */
class GeoVaultCaptureLogExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != GeoVaultCaptureLogIntents.ACTION_EXPORT_CAPTURE_LOG) {
            return
        }
        if (!BuildConfig.GEOVAULT_CAPTURE_LOGGING_ENABLED) {
            Log.e(
                TAG,
                "EXPORT_CAPTURE_LOG broadcast ignored: this build was compiled without capture logging " +
                    "(rebuild with -PGEOVAULT_ADD_LOGGING=true or ./build-android.sh ... --add-logging).",
            )
            return
        }
        val pendingResult = goAsync()
        GeoVaultCaptureLogEngine.runExport(context.applicationContext) {
            pendingResult.finish()
        }
    }

    private companion object {
        private const val TAG = "GvCaptureLogExport"
    }
}
