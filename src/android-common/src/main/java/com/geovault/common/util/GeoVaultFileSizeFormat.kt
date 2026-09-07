package com.geovault.common.util

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object GeoVaultFileSizeFormat {
    fun humanBytesOrUnknown(bytes: Long, unknownLabel: String = "Size unknown"): String {
        if (bytes <= 0L) return unknownLabel
        return humanBytes(bytes)
    }

    /** Compact human-readable size (e.g. `1.2 MB`, `512 B`) using SI-style 1024 steps. */
    fun humanBytes(bytes: Long): String {
        val n = bytes.coerceAtLeast(0L)
        if (n < 1024L) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        val base = 1024.0
        val exp = (ln(n.toDouble()) / ln(base)).toInt().coerceIn(1, units.size)
        val value = n / base.pow(exp.toDouble())
        val unit = units[exp - 1]
        val rounded = if (value >= 10) {
            String.format(Locale.getDefault(), "%.0f", value)
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
        return "$rounded $unit"
    }
}
