package com.geovault.tracker.runtime

import com.geovault.tracker.positioning.TrackingRuntimeSnapshot

data class RecordingSession(
    val trackerId: String,
    val trackerName: String,
    val startTimeMs: Long,
    val visibleBoundaryId: Long,
)

@ConsistentCopyVisibility
data class TrackerRuntimeDocument internal constructor(
    val orchestration: RuntimeState = RuntimeState(),
    internal val recording: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot(),
) {
    val shouldBeRunning: Boolean
        get() = orchestration.shouldBeRunning

    val isRecording: Boolean
        get() = recording.session != null

    val locallyRecordedTrackerId: String
        get() = recording.locallyRecordedTrackerId
}
