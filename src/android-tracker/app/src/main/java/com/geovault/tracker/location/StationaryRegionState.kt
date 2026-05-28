package com.geovault.tracker.location

import android.content.Context
import com.geovault.tracker.services.TrackingMotionMode

data class StationaryRegionState(
    val anchor: RecoveryAnchorState? = null,
    val radiusMeters: Float = 0f,
    val enteredAtMs: Long = 0L,
    val probeActive: Boolean = false,
    val probeStartedAtMs: Long = 0L,
    val poorAccuracyFixes: Int = 0,
    val lastFreshnessPointAtMs: Long = 0L,
) {
    val hasRegion: Boolean
        get() = anchor != null && radiusMeters > 0f

    fun enter(anchor: RecoveryAnchorState, nowMs: Long): StationaryRegionState {
        return copy(
            anchor = anchor,
            radiusMeters = anchor.radiusMeters,
            enteredAtMs = nowMs,
            probeActive = false,
            probeStartedAtMs = 0L,
            poorAccuracyFixes = 0,
        )
    }

    fun startProbe(nowMs: Long): StationaryRegionState {
        return copy(
            probeActive = true,
            probeStartedAtMs = nowMs,
            poorAccuracyFixes = 0,
        )
    }

    fun recordPoorAccuracyFix(): StationaryRegionState {
        return copy(poorAccuracyFixes = poorAccuracyFixes + 1)
    }

    fun markFreshnessPointPersisted(nowMs: Long): StationaryRegionState {
        return copy(
            lastFreshnessPointAtMs = nowMs,
            probeActive = false,
            probeStartedAtMs = 0L,
            poorAccuracyFixes = 0,
        )
    }

    fun clearProbe(clearLastFreshnessTimestamp: Boolean): StationaryRegionState {
        return copy(
            probeActive = false,
            probeStartedAtMs = 0L,
            poorAccuracyFixes = 0,
            lastFreshnessPointAtMs = if (clearLastFreshnessTimestamp) 0L else lastFreshnessPointAtMs,
        )
    }

    fun clear(): StationaryRegionState = StationaryRegionState()
}

class StationaryRegionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(state: StationaryRegionState) {
        val anchor = state.anchor ?: return clear()
        prefs.edit()
            .putString(KEY_TRACKER_ID, anchor.trackerId)
            .putLong(KEY_SESSION_BOUNDARY_ID, anchor.sessionBoundaryId)
            .putString(KEY_LATITUDE, anchor.latitude.toString())
            .putString(KEY_LONGITUDE, anchor.longitude.toString())
            .putLong(KEY_ANCHOR_TIMESTAMP_MS, anchor.timestampMs)
            .putLong(KEY_ANCHOR_ELAPSED_NANOS, anchor.elapsedRealtimeNanos)
            .putFloat(KEY_ANCHOR_ACCURACY_METERS, anchor.accuracyMeters ?: NO_ACCURACY)
            .putFloat(KEY_RADIUS_METERS, state.radiusMeters)
            .putString(KEY_SOURCE, anchor.source)
            .putString(KEY_MOTION_MODE, anchor.motionMode.name)
            .putLong(KEY_ENTERED_AT_MS, state.enteredAtMs)
            .putLong(KEY_LAST_FRESHNESS_POINT_AT_MS, state.lastFreshnessPointAtMs)
            .apply()
    }

    fun load(trackerId: String, sessionBoundaryId: Long): StationaryRegionState? {
        if (prefs.getString(KEY_TRACKER_ID, null) != trackerId) return null
        if (prefs.getLong(KEY_SESSION_BOUNDARY_ID, -1L) != sessionBoundaryId) return null
        val timestamp = prefs.getLong(KEY_ANCHOR_TIMESTAMP_MS, 0L)
        if (timestamp <= 0L) return null
        val mode = prefs.getString(KEY_MOTION_MODE, null)
            ?.let { runCatching { TrackingMotionMode.valueOf(it) }.getOrNull() }
            ?: TrackingMotionMode.WALKING
        val accuracy = prefs.getFloat(KEY_ANCHOR_ACCURACY_METERS, NO_ACCURACY)
            .takeIf { it != NO_ACCURACY }
        val anchor = RecoveryAnchorState(
            trackerId = trackerId,
            sessionBoundaryId = sessionBoundaryId,
            latitude = prefs.getString(KEY_LATITUDE, "0.0")?.toDoubleOrNull() ?: 0.0,
            longitude = prefs.getString(KEY_LONGITUDE, "0.0")?.toDoubleOrNull() ?: 0.0,
            timestampMs = timestamp,
            elapsedRealtimeNanos = prefs.getLong(KEY_ANCHOR_ELAPSED_NANOS, 0L),
            accuracyMeters = accuracy,
            radiusMeters = prefs.getFloat(KEY_RADIUS_METERS, 0f),
            source = prefs.getString(KEY_SOURCE, "stationary_region") ?: "stationary_region",
            motionMode = mode,
        )
        return StationaryRegionState(
            anchor = anchor,
            radiusMeters = anchor.radiusMeters,
            enteredAtMs = prefs.getLong(KEY_ENTERED_AT_MS, 0L),
            lastFreshnessPointAtMs = prefs.getLong(KEY_LAST_FRESHNESS_POINT_AT_MS, 0L),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val PREFS_NAME = "tracker_stationary_region_v1"
        private const val KEY_TRACKER_ID = "tracker_id"
        private const val KEY_SESSION_BOUNDARY_ID = "session_boundary_id"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_ANCHOR_TIMESTAMP_MS = "anchor_timestamp_ms"
        private const val KEY_ANCHOR_ELAPSED_NANOS = "anchor_elapsed_realtime_nanos"
        private const val KEY_ANCHOR_ACCURACY_METERS = "anchor_accuracy_meters"
        private const val KEY_RADIUS_METERS = "radius_meters"
        private const val KEY_SOURCE = "source"
        private const val KEY_MOTION_MODE = "motion_mode"
        private const val KEY_ENTERED_AT_MS = "entered_at_ms"
        private const val KEY_LAST_FRESHNESS_POINT_AT_MS = "last_freshness_point_at_ms"
        private const val NO_ACCURACY = -1f
    }
}
