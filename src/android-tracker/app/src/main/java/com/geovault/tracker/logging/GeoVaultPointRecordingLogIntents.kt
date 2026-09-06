package com.geovault.tracker.logging

/**
 * Example: `adb shell am broadcast -n <package>/com.geovault.tracker.logging.GeoVaultPointRecordingLogExportReceiver -a com.geovault.tracker.EXPORT_POINT_RECORDING_LOG`
 */
object GeoVaultPointRecordingLogIntents {
    const val ACTION_EXPORT_POINT_RECORDING_LOG = "com.geovault.tracker.EXPORT_POINT_RECORDING_LOG"
}
