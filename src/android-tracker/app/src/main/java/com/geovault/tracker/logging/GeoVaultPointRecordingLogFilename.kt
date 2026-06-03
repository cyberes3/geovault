package com.geovault.tracker.logging

import com.geovault.common.logging.GeoVaultCaptureLogFilename
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object GeoVaultPointRecordingLogFilename {

    private val TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)

    fun buildExportDisplayName(appLabel: String, instant: Instant, compressed: Boolean = false): String {
        val safe = GeoVaultCaptureLogFilename.sanitizeForFilename(appLabel)
        val ts = TIMESTAMP_FORMATTER.format(instant)
        val suffix = if (compressed) ".txt.gz" else ".txt"
        return "${safe}_point-recording_$ts$suffix"
    }
}
