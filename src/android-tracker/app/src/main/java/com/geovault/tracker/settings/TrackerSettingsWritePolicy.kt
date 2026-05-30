package com.geovault.tracker.settings

class TrackerSettingsWritePolicy {

    fun sanitize(settings: TrackerSettings): TrackerSettings {
        return settings.copy(
            accuracyFilterMeters = TrackerSettings.clampAccuracyFilterMeters(settings.accuracyFilterMeters),
            lowAccuracyFallbackTimeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(
                settings.lowAccuracyFallbackTimeoutSec
            ),
        )
    }
}
