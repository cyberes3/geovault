package com.geovault.tracker.logging

import android.content.Context
import com.geovault.common.logging.GeoVaultBufferedLogSqliteStore

internal class GeoVaultPointRecordingLogStore(
    context: Context,
    maxStoredBytes: Long = MAX_STORED_BYTES,
) : GeoVaultBufferedLogSqliteStore(
        context = context,
        dbFileName = DB_NAME,
        maxStoredBytes = maxStoredBytes,
    ) {

    companion object {
        const val DB_NAME = "geovault_point_recording.sqlite"

        const val MAX_STORED_BYTES = 100L * 1024L * 1024L
    }
}
