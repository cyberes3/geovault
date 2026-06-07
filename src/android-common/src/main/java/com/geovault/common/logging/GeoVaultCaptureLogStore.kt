package com.geovault.common.logging

import android.content.Context

internal class GeoVaultCaptureLogStore(
    context: Context,
    maxStoredBytes: Long = MAX_STORED_BYTES,
) : GeoVaultBufferedLogSqliteStore(
        context = context,
        dbFileName = DB_NAME,
        maxStoredBytes = maxStoredBytes,
    ) {

    companion object {
        internal const val DB_NAME = "geovault_capture_log.sqlite"

        internal const val MAX_STORED_BYTES = 100L * 1024L * 1024L
    }
}
