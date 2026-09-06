package com.geovault.tracker.logging

import android.app.Application
import android.content.Context
import android.util.Log
import com.geovault.common.logging.BufferedLogEngine
import com.geovault.tracker.BuildConfig

/**
 * Persists high-volume positioning replay lines to a separate SQLite DB in app cache when
 * [BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED] is true (tracker
 * `./build-android.sh debug|release --add-recording` or `-PGEOVAULT_ADD_RECORDING=true`).
 * Always mirrors to logcat. Call [init] from [Application.onCreate].
 *
 * Recorded line types:
 * - `positioning_raw_fix` — GPS fixes from [com.geovault.tracker.positioning.ingest.FixIngestSubsystem]
 * - `positioning_imu_classification` — stable IMU classification emissions from [com.geovault.tracker.positioning.motion.MotionSubsystem]
 */
object GeoVaultPointRecordingLog {

    private val engine =
        BufferedLogEngine(
            isEnabled = { BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED },
            insertThreadName = "GeoVaultPointRecordingLog",
            exportThreadName = "GeoVaultPointRecordingExport",
            tag = "GeoVaultPointRecordingLogEngine",
            eventPrefix = "point_recording",
            createStore = { GeoVaultPointRecordingLogStore(it) },
            writeExport = { context, store, requestId ->
                GeoVaultPointRecordingLogDownloadsExport.export(context, store, requestId)
            },
        )

    fun init(application: Application) {
        engine.init(application)
    }

    fun exportToDownloads(context: Context): Boolean = engine.exportToDownloads(context)

    @JvmStatic
    fun i(tag: String, msg: String) {
        logcat { Log.i(tag, msg) }
        if (!BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED) return
        engine.enqueue(Log.INFO, tag, msg)
    }

    private inline fun logcat(block: () -> Unit) {
        runCatching { block() }
    }
}
