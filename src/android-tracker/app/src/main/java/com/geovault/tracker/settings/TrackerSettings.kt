package com.geovault.tracker.settings

data class TrackerSettings(
    val accuracyFilterMeters: Float = INTERNAL_ACCURACY_FILTER_METERS,
    val lowAccuracyFallbackEnabled: Boolean = DEFAULT_LOW_ACCURACY_FALLBACK_ENABLED,
    val lowAccuracyFallbackTimeoutSec: Long = DEFAULT_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC,
    val sendExtendedData: Boolean = true,
    val significantDataOnly: Boolean = true,
    val startOnBoot: Boolean = false,
    val startTrackingOnLaunch: Boolean = false,
    val keepScreenOnWhileViewingMap: Boolean = DEFAULT_KEEP_SCREEN_ON_WHILE_VIEWING_MAP,
    val groupModeFitOnlyActiveTrackers: Boolean = DEFAULT_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS,
) {
    companion object {
        const val DEFAULT_KEEP_SCREEN_ON_WHILE_VIEWING_MAP: Boolean = true
        const val DEFAULT_GROUP_MODE_FIT_ONLY_ACTIVE_TRACKERS: Boolean = true

        const val INTERNAL_ACCURACY_FILTER_METERS: Float = 50f
        const val MIN_ACCURACY_FILTER_METERS: Float = 1f
        const val MAX_ACCURACY_FILTER_METERS: Float = 10_000f
        const val DEFAULT_LOW_ACCURACY_FALLBACK_ENABLED: Boolean = true
        const val DEFAULT_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC: Long = 60L
        const val MIN_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC: Long = 1L
        const val MAX_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC: Long = 3600L

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
