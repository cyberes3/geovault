package com.geovault.tracker.services

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
    fun ingestConfig_isProfileIndependent_andOnlyAdjustsAccuracyThreshold() {
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
        assertEquals(walking, biking)
        assertEquals(walking, driving)
        assertEquals(25.0, walking.trackingAccuracyThresholdMeters, 1e-9)
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
    fun fallbackTransitionConfig_setsConservativeFreshnessTtl() {
        val fallback = TrackingPolicyProfiles.fallbackTransitionConfig()
        assertEquals(2L * 60L * 1000L, fallback.freshnessTtlMs)
    }
}
