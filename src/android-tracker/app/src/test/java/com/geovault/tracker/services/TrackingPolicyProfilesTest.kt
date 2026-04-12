package com.geovault.tracker.services

import com.geovault.tracker.policy.TrackPointOutlierPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingPolicyProfilesTest {

    @Test
    fun motionModeFromProfileIndex_defaultsToBiking() {
        assertEquals(TrackingMotionMode.BIKING, TrackingPolicyProfiles.motionModeFromProfileIndex(-1))
        assertEquals(TrackingMotionMode.BIKING, TrackingPolicyProfiles.motionModeFromProfileIndex(999))
    }

    @Test
    fun ingestConfig_walkingHasTighterBurstThanBiking() {
        val walking = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.WALKING,
            isMockLocation = false
        )
        val biking = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = 25f,
            motionMode = TrackingMotionMode.BIKING,
            isMockLocation = false
        )
        assertTrue(walking.maxBurstDistanceMeters < biking.maxBurstDistanceMeters)
        assertTrue(walking.rollingDistanceMultiplier < biking.rollingDistanceMultiplier)
    }

    @Test
    fun fallbackTransitionConfig_allowsDegradedAndUsesStrictOutlierPolicy() {
        val cfg = TrackingPolicyProfiles.fallbackTransitionConfig()
        assertTrue(cfg.allowDegradedAccuracy)
        assertFalse(cfg.requireAccuracyForAcceptance)
        assertEquals(TrackPointOutlierPolicy.STRICT, cfg.outlierPolicy)
    }
}
