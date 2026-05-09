package com.geovault.tracker.settings

import com.geovault.tracker.TrackingLocationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerSettingsWritePolicyTest {

    private val policy = TrackerSettingsWritePolicy()

    @Test
    fun sanitize_clampsOutOfRangeValues() {
        val raw = TrackerSettings(
            loggingIntervalSec = 999_999L,
            distanceFilterMeters = 0.0001f,
            accuracyFilterMeters = 50_000f,
            lowAccuracyFallbackTimeoutSec = 99_999L,
            trackingProfile = TrackerTrackingProfile.CUSTOM
        )
        val s = policy.sanitize(raw)
        assertEquals(TrackerSettings.MAX_LOGGING_INTERVAL_SEC, s.loggingIntervalSec)
        assertEquals(TrackerSettings.MIN_DISTANCE_FILTER_METERS, s.distanceFilterMeters, 0.0001f)
        assertEquals(TrackerSettings.MAX_ACCURACY_FILTER_METERS, s.accuracyFilterMeters, 0.0001f)
        assertEquals(TrackerSettings.MAX_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC, s.lowAccuracyFallbackTimeoutSec)
    }

    @Test
    fun applyProfile_walking_setsCadenceAndPreservesAccuracyPreference() {
        val userAccuracyPreference = 17.5f
        val base = TrackerSettings(
            loggingIntervalSec = 1L,
            distanceFilterMeters = 1f,
            accuracyFilterMeters = userAccuracyPreference,
            trackingProfile = TrackerTrackingProfile.CUSTOM
        )
        val next = policy.applyProfile(base, TrackerTrackingProfile.WALKING)
        assertEquals(TrackerTrackingProfile.WALKING, next.trackingProfile)
        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, next.loggingIntervalSec)
        assertEquals(TrackingLocationPolicy.WALKING_DISTANCE_FILTER_METERS, next.distanceFilterMeters, 0.001f)
        assertEquals(userAccuracyPreference, next.accuracyFilterMeters, 0.001f)
    }

    @Test
    fun applyProfile_custom_onlyUpdatesProfileEnum() {
        val base = TrackerSettings(
            loggingIntervalSec = 42L,
            distanceFilterMeters = 12f,
            accuracyFilterMeters = 25f,
            trackingProfile = TrackerTrackingProfile.WALKING
        )
        val next = policy.applyProfile(base, TrackerTrackingProfile.CUSTOM)
        assertEquals(TrackerTrackingProfile.CUSTOM, next.trackingProfile)
        assertEquals(42L, next.loggingIntervalSec)
        assertEquals(12f, next.distanceFilterMeters, 0.001f)
        assertEquals(25f, next.accuracyFilterMeters, 0.001f)
    }

}
