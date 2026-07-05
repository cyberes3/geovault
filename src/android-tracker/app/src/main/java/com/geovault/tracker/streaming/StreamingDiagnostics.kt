package com.geovault.tracker.streaming

import com.geovault.common.logging.GeoVaultCaptureLog

/**
 * Centralized capture-log breadcrumbs for the streaming pipeline. Consolidating these here (as
 * opposed to inline `GeoVaultCaptureLog` calls scattered through the repository, service, and
 * admission pipeline) makes every diagnosable "why did streaming behave like that" question
 * answerable from a single, greppable log prefix (`stream_diag_*`).
 */
object StreamingDiagnostics {
    private const val TAG = "StreamingDiagnostics"

    fun logDispatch(reason: String?, trackerIds: Set<String>, trackerName: String?) {
        GeoVaultCaptureLog.d(
            TAG,
            "stream_diag_dispatch reason=${reason ?: "lease_changed"} count=${trackerIds.size} " +
                "name=${trackerName.orEmpty()} ids=${trackerIds.sorted()}",
        )
    }

    fun logWatchdogReconnect(activityAgeMs: Long) {
        GeoVaultCaptureLog.w(TAG, "stream_diag_watchdog_reconnect stale_for_ms=$activityAgeMs")
    }

    fun logCollectorRestart(collectorName: String, error: Throwable) {
        GeoVaultCaptureLog.e(TAG, "stream_diag_collector_restart collector=$collectorName", error)
    }

    fun logRosterDeltaHotUpdate(previousCount: Int, nextCount: Int) {
        GeoVaultCaptureLog.d(TAG, "stream_diag_roster_delta_hot_update from=$previousCount to=$nextCount")
    }

    fun logReloadNetworkSlowDuringRecording(trackerIds: Set<String>, elapsedMs: Long) {
        GeoVaultCaptureLog.w(
            TAG,
            "stream_diag_reload_slow_during_recording tracks=${trackerIds.sorted()} elapsedMs=$elapsedMs",
        )
    }

    fun logHeartbeat(
        wantsSubscription: Boolean,
        connection: ConnectionPhase,
        activeCount: Int,
        lastPointAgeMs: Long?,
        mutexHeld: Boolean,
    ) {
        GeoVaultCaptureLog.d(
            TAG,
            "stream_diag_heartbeat wants=$wantsSubscription connection=$connection active=$activeCount " +
                "lastPointAgeMs=${lastPointAgeMs ?: -1} trailMutexHeld=$mutexHeld",
        )
    }
}
