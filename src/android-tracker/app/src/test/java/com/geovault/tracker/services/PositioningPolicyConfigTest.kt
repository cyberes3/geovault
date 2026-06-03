package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PositioningPolicyConfigTest {

    @Test
    fun ingestConfig_appliesPresetSpecificPhysicsAndSharedAccuracyThreshold() {
        val walking = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.WALKING,
        )
        val biking = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.BIKING,
        )
        val driving = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.DRIVING,
        )
        assertTrue(walking.maxImpliedSpeedMps < biking.maxImpliedSpeedMps)
        assertTrue(biking.maxImpliedSpeedMps < driving.maxImpliedSpeedMps)
        assertTrue(walking.maxBurstDistanceMeters < biking.maxBurstDistanceMeters)
        assertTrue(biking.maxBurstDistanceMeters < driving.maxBurstDistanceMeters)
        assertEquals(25.0, walking.trackingAccuracyThresholdMeters, 1e-9)
        assertEquals(25.0, biking.trackingAccuracyThresholdMeters, 1e-9)
        assertEquals(25.0, driving.trackingAccuracyThresholdMeters, 1e-9)
    }

    @Test
    fun ingestConfig_appliesAccuracyThresholdAcrossModes() {
        val tight = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = 10f,
            motionMode = TrackingMotionMode.WALKING,
        )
        val loose = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = 80f,
            motionMode = TrackingMotionMode.WALKING,
        )
        assertTrue(tight.trackingAccuracyThresholdMeters < loose.trackingAccuracyThresholdMeters)
    }

    @Test
    fun positioningPresets_bundleProviderFilterAndRecoveryValues() {
        val walking = PositioningPresets.forMotionMode(TrackingMotionMode.WALKING)
        val biking = PositioningPresets.forMotionMode(TrackingMotionMode.BIKING)
        val driving = PositioningPresets.forMotionMode(TrackingMotionMode.DRIVING)

        assertEquals(TrackingLocationPolicy.WALKING_INTERVAL_SEC, walking.locationIntervalSec)
        assertEquals(TrackingLocationPolicy.BIKING_DISTANCE_FILTER_METERS, biking.distanceFilterMeters)
        assertEquals(MotionProfileTuning.Driving, driving.filterTuning)
        assertEquals(60f, driving.recoveryConfig(maxLocalPointGapMs = 90_000L).recoverySpeedCapMps)
    }

    @Test
    fun sparsePreset_doesNotChangeIngestFilterTuning() {
        val normalWalking = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.WALKING,
        )
        val sparseWalking = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.WALKING,
        )
        val sparsePreset = PositioningPresets.forMotionMode(
            TrackingMotionMode.WALKING,
            PositioningDensity.Sparse,
        )

        assertEquals(normalWalking.maxImpliedSpeedMps, sparseWalking.maxImpliedSpeedMps, 1e-9)
        assertEquals(normalWalking.maxBurstDistanceMeters, sparseWalking.maxBurstDistanceMeters, 1e-9)
        assertEquals(MotionProfileTuning.Walking, sparsePreset.filterTuning)
        assertEquals(
            TrackingLocationPolicy.WALKING_INTERVAL_SEC * 2,
            sparsePreset.locationIntervalSec,
        )
    }

    @Test
    fun fallbackTransitionConfig_setsConservativeFreshnessTtl() {
        val fallback = PositioningPolicyConfig.fallbackTransitionConfig()
        assertEquals(2L * 60L * 1000L, fallback.freshnessTtlMs)
    }

    @Test
    fun ingestConfig_canBeBuiltFromResolvedPresetTuning() {
        val preset = PositioningPresets.forMotionMode(TrackingMotionMode.WALKING)

        val config = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = preset.accuracyThresholdMeters,
            tuning = preset.filterTuning,
        )

        assertEquals(MotionProfileTuning.Walking.maxImpliedSpeedMps, config.maxImpliedSpeedMps, 1e-9)
        assertEquals(MotionProfileTuning.Walking.movementCandidate, config.movementCandidate)
    }

    @Test
    fun positioningContextCarriesResolvedRequestFilterRecoveryAndAdaptiveConfig() {
        val context = PositioningContext.build(
            settings = TrackerSettings(),
            activeMotionMode = TrackingMotionMode.WALKING,
            effectiveDistanceFilterMeters = 42f,
            localPointMaxGapMs = 90_000L,
            collectionPace = com.geovault.tracker.positioning.RecordingPace.Moving,
        )

        assertEquals(TrackingMotionMode.WALKING, context.activeMotionMode)
        assertEquals(TrackingLocationPolicy.WALKING_DISTANCE_FILTER_METERS, context.baseDistanceFilterMeters)
        assertEquals(42f, context.distanceFilterMeters)
        assertEquals(MotionProfileTuning.Walking.maxImpliedSpeedMps, context.filterConfig.maxImpliedSpeedMps, 1e-9)
        assertEquals(MotionProfileTuning.Walking.maxImpliedSpeedMps.toFloat(), context.recoveryConfig.recoverySpeedCapMps)
        assertEquals(5f, context.elasticityConfig.speedBucketSizeMps)
        assertEquals(60_000L, context.fastLockConfig.windowMs)
    }

    @Test
    fun defaultLocationFilterConfigIsGenericNonSessionConfig() {
        assertNotEquals(MotionProfileTuning.Walking.maxImpliedSpeedMps, LocationFilterConfig.Default.maxImpliedSpeedMps)
        assertEquals(60.0, LocationFilterConfig.Default.maxImpliedSpeedMps, 1e-9)
    }
}
