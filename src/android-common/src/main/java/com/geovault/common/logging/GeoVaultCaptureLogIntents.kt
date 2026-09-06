package com.geovault.common.logging

/**
 * Broadcast action for [GeoVaultCaptureLogExportReceiver].
 *
 * Example: `adb shell am broadcast -n <package>/com.geovault.common.logging.GeoVaultCaptureLogExportReceiver -a com.geovault.common.EXPORT_CAPTURE_LOG`
 */
object GeoVaultCaptureLogIntents {
    const val ACTION_EXPORT_CAPTURE_LOG = "com.geovault.common.EXPORT_CAPTURE_LOG"
}
