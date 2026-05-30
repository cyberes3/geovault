package com.geovault.tracker.services

import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.policy.filter.MotionProfileTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPolicyProfilesTest {

    @Test
    fun motionModeFromProfileIndex_defaultsToBiking() {
        assertEquals(TrackingMotionMode.BIKING, TrackingPolicyProfiles.motionModeFromProfileIndex(-1))
        assertEquals(TrackingMotionMode.BIKING, TrackingPolicyProfiles.motionModeFromProfileIndex(999))
    }

    @Test
    fun ingestConfig_appliesProfileSpecificPhysicsAndSharedAccuracyThreshold() {
        val walking = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.WALKING,
            isMockLocation = false,
        )
        val biking = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.BIKING,
            isMockLocation = false,
        )
        val driving = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.DRIVING,
            isMockLocation = false,
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
        val tight = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = 10f,
            motionMode = TrackingMotionMode.WALKING,
            isMockLocation = false,
        )
        val loose = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = 80f,
            motionMode = TrackingMotionMode.WALKING,
            isMockLocation = false,
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
    fun fallbackTransitionConfig_setsConservativeFreshnessTtl() {
        val fallback = TrackingPolicyProfiles.fallbackTransitionConfig()
        assertEquals(2L * 60L * 1000L, fallback.freshnessTtlMs)
    }
}
