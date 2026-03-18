package com.geovault.tracker.settings

object TrackingSettingsReapplyPolicy {
    @JvmStatic
    fun shouldReapplyLocationRequest(previous: TrackerSettings, current: TrackerSettings): Boolean {
        return previous.loggingIntervalSec != current.loggingIntervalSec ||
            previous.distanceFilterMeters != current.distanceFilterMeters ||
            previous.autoTrackingMode != current.autoTrackingMode ||
            previous.trackingProfile != current.trackingProfile
    }
}
