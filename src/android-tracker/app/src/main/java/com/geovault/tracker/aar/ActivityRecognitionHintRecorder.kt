package com.geovault.tracker.aar

import com.geovault.tracker.logging.GeoVaultPointRecordingLog

/**
 * Writes `positioning_activity_transition` log lines to [GeoVaultPointRecordingLog] for
 * offline replay and diagnostics. One line per raw GMS transition event, regardless of
 * whether the transition activates, extends, or clears the hint.
 *
 * The line carries raw activity/transition types for human debugging plus a
 * [hintActive] boolean indicating whether the transition caused an active hint.
 */
internal class ActivityRecognitionHintRecorder(
    private val trackId: String,
    private val trackingGeneration: Int,
) {
    fun record(
        wallMs: Long,
        elapsedRealtimeNanos: Long,
        eventTimeMs: Long,
        activityLabel: String,
        transitionLabel: String,
        hintActive: Boolean,
    ) {
        GeoVaultPointRecordingLog.i(
            TAG,
            "positioning_activity_transition " +
                "track=$trackId " +
                "wall=$wallMs " +
                "elapsedNanos=$elapsedRealtimeNanos " +
                "time=$eventTimeMs " +
                "activity=$activityLabel " +
                "transition=$transitionLabel " +
                "trackingGeneration=$trackingGeneration " +
                "hintActive=$hintActive",
        )
    }

    private companion object {
        private const val TAG = "GeoVaultAAR"
    }
}
