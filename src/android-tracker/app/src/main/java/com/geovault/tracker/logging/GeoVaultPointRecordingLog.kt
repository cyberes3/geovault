package com.geovault.tracker.logging

import android.app.Application
import android.util.Log
import com.geovault.tracker.BuildConfig

/**
 * Persists high-volume positioning replay lines to a separate SQLite DB in app cache when
 * [BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED] is true (tracker
 * `./build-android.sh debug|release --add-recording` or `-PGEOVAULT_ADD_RECORDING=true`).
 * Always mirrors to logcat. Call [init] from [Application.onCreate].
 *
 * Recorded line types:
 * - `positioning_raw_fix` — GPS fixes from [com.geovault.tracker.positioning.ingest.FixIngestSubsystem]
 * - `positioning_activity_transition` — GMS activity-transition events from [com.geovault.tracker.aar.ActivityRecognitionHintRecorder]
 */
object GeoVaultPointRecordingLog {

    fun init(application: Application) {
        GeoVaultPointRecordingLogEngine.init(application)
    }

    @JvmStatic
    fun i(tag: String, msg: String) {
        logcat { Log.i(tag, msg) }
        if (!BuildConfig.GEOVAULT_POINT_RECORDING_ENABLED) return
        GeoVaultPointRecordingLogEngine.enqueue(Log.INFO, tag, msg)
    }

    private inline fun logcat(block: () -> Unit) {
        runCatching { block() }
    }
}
