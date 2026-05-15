package com.geovault.common.logging

/**
 * Broadcast action for [GeoVaultCaptureLogExportReceiver].
 *
 * Example: `adb shell am broadcast -a com.geovault.common.EXPORT_CAPTURE_LOG -p <package>`
 */
object GeoVaultCaptureLogIntents {
    const val ACTION_EXPORT_CAPTURE_LOG = "com.geovault.common.EXPORT_CAPTURE_LOG"
}
