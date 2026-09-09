package com.geovault.tracker.policy

import com.geovault.tracker.domain.TrackPoint

/**
 * Decides whether a [TrackPoint] should update the tracker params UI for [trackerId].
 * Mirrors [com.geovault.tracker.pipeline.TrackPointSourceResolver.shouldAcceptForParams].
 */
object TrackerParamsPointAcceptancePolicy {

    fun shouldAcceptForParams(
        event: TrackPoint,
        trackerId: String,
        trackingRunning: Boolean,
        selectedTrackerId: String,
    ): Boolean {
        if (event.trackerId != trackerId) return false
        val localMode = trackingRunning && trackerId == selectedTrackerId
        return when (event.provenance) {
            TrackPointSource.LOCAL_GPS -> localMode
            TrackPointSource.REMOTE_STREAM -> !localMode
        }
    }
}
