package com.geovault.common.files

import java.util.Locale

object GeoVaultFileSizeFormat {
    fun format(bytes: Long, locale: Locale = Locale.US): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024.0) return String.format(locale, "%.1f KB", kib)
        val mib = kib / 1024.0
        if (mib < 1024.0) return String.format(locale, "%.1f MB", mib)
        return String.format(locale, "%.1f GB", mib / 1024.0)
    }
}
