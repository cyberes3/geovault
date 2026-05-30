package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerPositioningRuntimeContextTest {
    @Test
    fun build_autoModeUsesMotionProfileCadenceAndRecoveryConfig() {
        val context = TrackerPositioningRuntimeContext.build(
            settings = TrackerSettings(autoTrackingMode = true),
            activeMotionMode = TrackingMotionMode.DRIVING,
            effectiveDistanceFilterMeters = 123f,
            localPointMaxGapMs = 90_000L,
        )

        assertEquals(TrackingLocationPolicy.DRIVING_INTERVAL_SEC, context.locationIntervalSec)
        assertEquals(123f, context.distanceFilterMeters)
        assertEquals(TrackingLocationPolicy.DRIVING_INTERVAL_SEC, context.pointFreshnessIntervalSec)
        assertEquals(90_000L, context.recoveryConfig.maxLocalPointGapMs)
        assertEquals(60f, context.recoveryConfig.recoverySpeedCapMps)
    }

    @Test
    fun build_manualModeKeepsUserCadenceButUsesActiveModeForPhysics() {
        val context = TrackerPositioningRuntimeContext.build(
            settings = TrackerSettings(
                autoTrackingMode = false,
                loggingIntervalSec = 42L,
                accuracyFilterMeters = 33f,
            ),
            activeMotionMode = TrackingMotionMode.WALKING,
            effectiveDistanceFilterMeters = 8f,
            localPointMaxGapMs = 120_000L,
        )

        assertEquals(42L, context.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, context.pointFreshnessIntervalSec)
        assertEquals(33f, context.effectiveAccuracyThresholdMeters)
        assertEquals(4.5f, context.recoveryConfig.recoverySpeedCapMps)
    }

    @Test
    fun build_autoModeIgnoresUserCadenceButPreservesUserAccuracy() {
        val context = TrackerPositioningRuntimeContext.build(
            settings = TrackerSettings(
                autoTrackingMode = true,
                loggingIntervalSec = 42L,
                accuracyFilterMeters = 33f,
            ),
            activeMotionMode = TrackingMotionMode.WALKING,
            effectiveDistanceFilterMeters = 7f,
            localPointMaxGapMs = 120_000L,
        )

        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, context.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, context.pointFreshnessIntervalSec)
        assertEquals(33f, context.effectiveAccuracyThresholdMeters)
        assertEquals(33.0, context.filterConfig.trackingAccuracyThresholdMeters, 0.0)
    }

    @Test
    fun build_activeMotionModeSelectsFilterTuningValues() {
        val context = TrackerPositioningRuntimeContext.build(
            settings = TrackerSettings(autoTrackingMode = true),
            activeMotionMode = TrackingMotionMode.BIKING,
            effectiveDistanceFilterMeters = 30f,
            localPointMaxGapMs = 90_000L,
        )

        assertEquals(MotionProfileTuning.Biking.maxImpliedSpeedMps, context.filterConfig.maxImpliedSpeedMps, 0.0)
        assertEquals(MotionProfileTuning.Biking.maxBurstDistanceMeters, context.filterConfig.maxBurstDistanceMeters, 0.0)
        assertEquals(MotionProfileTuning.Biking.burstWindowSeconds, context.filterConfig.burstWindowSeconds, 0.0)
        assertEquals(MotionProfileTuning.Biking.movementCandidate, context.filterConfig.movementCandidate)
        assertEquals(MotionProfileTuning.Biking.speedRecovery, context.filterConfig.speedRecovery)
    }
}
