package com.geovault.tracker.settings

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerSettingsWritePolicy @Inject constructor() {

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
        return when (profile) {
            TrackerTrackingProfile.WALKING -> sanitize(
                base.copy(
                    trackingProfile = profile,
                    loggingIntervalSec = 30L,
                    distanceFilterMeters = 10f,
                    accuracyFilterMeters = 50f
                )
            )

            TrackerTrackingProfile.BIKING -> sanitize(
                base.copy(
                    trackingProfile = profile,
                    loggingIntervalSec = 15L,
                    distanceFilterMeters = 30f,
                    accuracyFilterMeters = 100f
                )
            )

            TrackerTrackingProfile.DRIVING -> sanitize(
                base.copy(
                    trackingProfile = profile,
                    loggingIntervalSec = 10L,
                    distanceFilterMeters = 100f,
                    accuracyFilterMeters = 200f
                )
            )

            TrackerTrackingProfile.CUSTOM -> sanitize(base.copy(trackingProfile = profile))
        }
    }
}
