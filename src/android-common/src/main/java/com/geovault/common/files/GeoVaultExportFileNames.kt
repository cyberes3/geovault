package com.geovault.common.files

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeoVaultExportFileNames {
    private val localStamp = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    fun timestamped(prefix: String, nowMillis: Long = System.currentTimeMillis()): String {
        val stamp = localStamp.get().format(Date(nowMillis))
        return "${prefix.trim('_')}_$stamp"
    }
}
