package com.geovault.tracker.settings

import com.geovault.tracker.TrackingLocationPolicy

class TrackerSettingsWritePolicy {

    fun sanitize(settings: TrackerSettings): TrackerSettings {
        return settings.copy(
            loggingIntervalSec = TrackerSettings.clampLoggingIntervalSec(settings.loggingIntervalSec),
            distanceFilterMeters = TrackerSettings.clampDistanceFilterMeters(settings.distanceFilterMeters),
            accuracyFilterMeters = TrackerSettings.clampAccuracyFilterMeters(settings.accuracyFilterMeters),
            lowAccuracyFallbackTimeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(
                settings.lowAccuracyFallbackTimeoutSec
            ),
            trackingProfile = TrackerTrackingProfile.fromIndex(settings.trackingProfile.index)
        )
    }

    /**
     * Profile selection only mutates the [LocationRequest] cadence
     * (interval + distance filter). The accuracy filter is the user's own
     * preference and is preserved across profile switches; profiles must
     * never thrash the [LocationFilter] config.
     */
    fun applyProfile(base: TrackerSettings, profile: TrackerTrackingProfile): TrackerSettings {
        if (profile == TrackerTrackingProfile.CUSTOM) {
            return sanitize(base.copy(trackingProfile = profile))
        }
        val (intervalSec, distanceMeters) = TrackingLocationPolicy.getProfileParams(profile.index)
        return sanitize(
            base.copy(
                trackingProfile = profile,
                loggingIntervalSec = intervalSec,
                distanceFilterMeters = distanceMeters,
            )
        )
    }
}
