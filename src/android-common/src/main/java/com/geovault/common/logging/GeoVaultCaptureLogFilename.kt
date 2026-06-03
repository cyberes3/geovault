package com.geovault.common.logging

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object GeoVaultCaptureLogFilename {

    private val INVALID_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC)

    fun sanitizeForFilename(label: String): String {
        val replaced =
            label.map { ch ->
                if (ch in INVALID_FILENAME_CHARS) {
                    '_'
                } else {
                    ch
                }
            }.joinToString("")
        val trimmed = replaced.trim().ifBlank { "app" }
        val collapsed = trimmed.replace(Regex("_+"), "_").trim('_')
        return collapsed.ifBlank { "app" }
    }

    fun buildExportDisplayName(appLabel: String, instant: Instant, compressed: Boolean = false): String {
        val safe = sanitizeForFilename(appLabel)
        val ts = TIMESTAMP_FORMATTER.format(instant)
        val suffix = if (compressed) ".txt.gz" else ".txt"
        return "${safe}_$ts$suffix"
    }
}
