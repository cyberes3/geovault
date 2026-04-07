package com.geovault.tracker.policy

/**
 * Decides whether a [TrackPointEvent] should update the tracker params UI for [trackerId].
 * Mirrors legacy [com.geovault.tracker.pipeline.TrackPointSourceResolver.shouldAcceptForParams].
 */
object TrackerParamsPointAcceptancePolicy {

    fun shouldAcceptForParams(
        event: TrackPointEvent,
        trackerId: String,
        trackingRunning: Boolean,
        selectedTrackerId: String,
    ): Boolean {
        if (event.trackId != trackerId) return false
        val localMode = trackingRunning && trackerId == selectedTrackerId
        return when (event.source) {
            TrackPointSource.LOCAL_GPS -> localMode
            TrackPointSource.REMOTE_STREAM -> !localMode
        }
    }
}
