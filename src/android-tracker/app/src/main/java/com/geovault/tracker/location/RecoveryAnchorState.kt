package com.geovault.tracker.location

import android.content.Context
import android.location.Location
import com.geovault.tracker.services.TrackingMotionMode

data class RecoveryAnchorState(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val elapsedRealtimeNanos: Long,
    val accuracyMeters: Float?,
    val radiusMeters: Float,
    val source: String,
    val motionMode: TrackingMotionMode,
) {
    fun toLocation(providerPrefix: String): Location {
        return Location("$providerPrefix:$source").apply {
            latitude = this@RecoveryAnchorState.latitude
            longitude = this@RecoveryAnchorState.longitude
            time = timestampMs
            elapsedRealtimeNanos = this@RecoveryAnchorState.elapsedRealtimeNanos
            accuracyMeters?.let { accuracy = it }
        }
    }

    companion object {
        fun fromLocation(
            location: Location,
            radiusMeters: Float,
            source: String,
            motionMode: TrackingMotionMode,
        ): RecoveryAnchorState {
            return RecoveryAnchorState(
                latitude = location.latitude,
                longitude = location.longitude,
                timestampMs = location.time,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                radiusMeters = radiusMeters,
                source = source,
                motionMode = motionMode,
            )
        }
    }
}

class RecoveryAnchorStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(anchor: RecoveryAnchorState) {
        prefs.edit()
            .putString(KEY_LATITUDE, anchor.latitude.toString())
            .putString(KEY_LONGITUDE, anchor.longitude.toString())
            .putLong(KEY_TIMESTAMP_MS, anchor.timestampMs)
            .putLong(KEY_ELAPSED_NANOS, anchor.elapsedRealtimeNanos)
            .putFloat(KEY_ACCURACY_METERS, anchor.accuracyMeters ?: NO_ACCURACY)
            .putFloat(KEY_RADIUS_METERS, anchor.radiusMeters)
            .putString(KEY_SOURCE, anchor.source)
            .putString(KEY_MOTION_MODE, anchor.motionMode.name)
            .apply()
    }

    fun load(): RecoveryAnchorState? {
        val timestamp = prefs.getLong(KEY_TIMESTAMP_MS, 0L)
        if (timestamp <= 0L) return null
        val mode = prefs.getString(KEY_MOTION_MODE, null)
            ?.let { runCatching { TrackingMotionMode.valueOf(it) }.getOrNull() }
            ?: TrackingMotionMode.WALKING
        val accuracy = prefs.getFloat(KEY_ACCURACY_METERS, NO_ACCURACY)
            .takeIf { it != NO_ACCURACY }
        return RecoveryAnchorState(
            latitude = prefs.getString(KEY_LATITUDE, "0.0")?.toDoubleOrNull() ?: 0.0,
            longitude = prefs.getString(KEY_LONGITUDE, "0.0")?.toDoubleOrNull() ?: 0.0,
            timestampMs = timestamp,
            elapsedRealtimeNanos = prefs.getLong(KEY_ELAPSED_NANOS, 0L),
            accuracyMeters = accuracy,
            radiusMeters = prefs.getFloat(
                KEY_RADIUS_METERS,
                com.geovault.tracker.TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            ),
            source = prefs.getString(KEY_SOURCE, "unknown") ?: "unknown",
            motionMode = mode,
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val PREFS_NAME = "tracker_recovery_anchor_v1"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_TIMESTAMP_MS = "timestamp_ms"
        private const val KEY_ELAPSED_NANOS = "elapsed_realtime_nanos"
        private const val KEY_ACCURACY_METERS = "accuracy_meters"
        private const val KEY_RADIUS_METERS = "radius_meters"
        private const val KEY_SOURCE = "source"
        private const val KEY_MOTION_MODE = "motion_mode"
        private const val NO_ACCURACY = -1f
    }
}
