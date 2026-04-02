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

    fun applyProfile(base: TrackerSettings, profile: TrackerTrackingProfile): TrackerSettings {
        val resolved = TrackingLocationPolicy.getProfileParams(profile.index)
        val intervalSec = resolved.first
        val distanceMeters = resolved.second
        val accuracyMeters = resolved.third
        return when (profile) {
            TrackerTrackingProfile.WALKING -> sanitize(
                base.copy(
                    trackingProfile = profile,
                    loggingIntervalSec = intervalSec,
                    distanceFilterMeters = distanceMeters,
                    accuracyFilterMeters = accuracyMeters
                )
            )

            TrackerTrackingProfile.BIKING -> sanitize(
                base.copy(
                    trackingProfile = profile,
                    loggingIntervalSec = intervalSec,
                    distanceFilterMeters = distanceMeters,
                    accuracyFilterMeters = accuracyMeters
                )
            )

            TrackerTrackingProfile.DRIVING -> sanitize(
                base.copy(
                    trackingProfile = profile,
                    loggingIntervalSec = intervalSec,
                    distanceFilterMeters = distanceMeters,
                    accuracyFilterMeters = accuracyMeters
                )
            )

            TrackerTrackingProfile.CUSTOM -> sanitize(base.copy(trackingProfile = profile))
        }
    }
}
