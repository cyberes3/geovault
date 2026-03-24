package com.geovault.tracker.settings

data class TrackerSettings(
    val loggingIntervalSec: Long = DEFAULT_LOGGING_INTERVAL_SEC,
    val distanceFilterMeters: Float = DEFAULT_DISTANCE_FILTER_METERS,
    val accuracyFilterMeters: Float = DEFAULT_ACCURACY_FILTER_METERS,
    val lowAccuracyFallbackEnabled: Boolean = DEFAULT_LOW_ACCURACY_FALLBACK_ENABLED,
    val lowAccuracyFallbackTimeoutSec: Long = DEFAULT_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC,
    val sendExtendedData: Boolean = true,
    val significantDataOnly: Boolean = true,
    val resetTrackingIfKilled: Boolean = true,
    val autoTrackingMode: Boolean = true,
    val trackingProfile: TrackerTrackingProfile = TrackerTrackingProfile.BIKING,
    val startOnBoot: Boolean = false,
    val startTrackingOnLaunch: Boolean = false,
    val keepScreenOnWhileViewingMap: Boolean = DEFAULT_KEEP_SCREEN_ON_WHILE_VIEWING_MAP
) {
    companion object {
        const val DEFAULT_KEEP_SCREEN_ON_WHILE_VIEWING_MAP: Boolean = true

        const val DEFAULT_LOGGING_INTERVAL_SEC: Long = 15L
        const val MIN_LOGGING_INTERVAL_SEC: Long = 1L
        const val MAX_LOGGING_INTERVAL_SEC: Long = 3600L

        const val DEFAULT_DISTANCE_FILTER_METERS: Float = 10f
        // Support a true "1 ft" minimum in imperial UI (0.3048 meters).
        const val MIN_DISTANCE_FILTER_METERS: Float = 0.3048f
        const val MAX_DISTANCE_FILTER_METERS: Float = 10_000f

        const val DEFAULT_ACCURACY_FILTER_METERS: Float = 50f
        const val MIN_ACCURACY_FILTER_METERS: Float = 1f
        const val MAX_ACCURACY_FILTER_METERS: Float = 10_000f
        const val DEFAULT_LOW_ACCURACY_FALLBACK_ENABLED: Boolean = true
        const val DEFAULT_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC: Long = 60L
        const val MIN_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC: Long = 1L
        const val MAX_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC: Long = 3600L

        @JvmStatic
        fun clampLoggingIntervalSec(value: Long): Long {
            return value.coerceIn(MIN_LOGGING_INTERVAL_SEC, MAX_LOGGING_INTERVAL_SEC)
        }

        @JvmStatic
        fun clampDistanceFilterMeters(value: Float): Float {
            return value.coerceIn(MIN_DISTANCE_FILTER_METERS, MAX_DISTANCE_FILTER_METERS)
        }

        @JvmStatic
        fun clampAccuracyFilterMeters(value: Float): Float {
            return value.coerceIn(MIN_ACCURACY_FILTER_METERS, MAX_ACCURACY_FILTER_METERS)
        }

        @JvmStatic
        fun clampLowAccuracyFallbackTimeoutSec(value: Long): Long {
            return value.coerceIn(
                MIN_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC,
                MAX_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC
            )
        }
    }
}
