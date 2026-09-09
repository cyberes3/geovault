package com.geovault.tracker.runtime

import com.geovault.tracker.positioning.TrackingRuntimeSnapshot

object RuntimePublisher {
    internal fun commit(recording: TrackingRuntimeSnapshot): TrackerRuntimeDocument {
        return TrackerRuntimeStore.commitRecording(recording)
    }
}
