package com.geovault.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingLocationPolicyProfileTest {

    @Test
    fun getProfileParams_returnsExpectedPresets() {
        assertEquals(Triple(20L, 7f, 40f), TrackingLocationPolicy.getProfileParams(0))
        assertEquals(Triple(15L, 30f, 100f), TrackingLocationPolicy.getProfileParams(1))
        assertEquals(Triple(10L, 100f, 200f), TrackingLocationPolicy.getProfileParams(2))
    }

    @Test
    fun getProfileParams_unknownProfile_defaultsToBiking() {
        assertEquals(Triple(15L, 30f, 100f), TrackingLocationPolicy.getProfileParams(99))
    }

    @Test
    fun getRecommendedProfile_respectsThresholdsAndHysteresis() {
        assertEquals(0, TrackingLocationPolicy.getRecommendedProfile(1.0f, 0))
        assertEquals(1, TrackingLocationPolicy.getRecommendedProfile(2.1f, 0))

        assertEquals(2, TrackingLocationPolicy.getRecommendedProfile(8.2f, 1))
        assertEquals(0, TrackingLocationPolicy.getRecommendedProfile(1.4f, 1))
        assertEquals(1, TrackingLocationPolicy.getRecommendedProfile(4.0f, 1))

        assertEquals(1, TrackingLocationPolicy.getRecommendedProfile(5.9f, 2))
        assertEquals(2, TrackingLocationPolicy.getRecommendedProfile(6.1f, 2))
    }

    @Test
    fun getRecommendedProfile_atExactBoundaries_staysInCurrentProfile() {
        assertEquals(0, TrackingLocationPolicy.getRecommendedProfile(2.0f, 0))
        assertEquals(1, TrackingLocationPolicy.getRecommendedProfile(8.0f, 1))
        assertEquals(1, TrackingLocationPolicy.getRecommendedProfile(1.5f, 1))
        assertEquals(2, TrackingLocationPolicy.getRecommendedProfile(6.0f, 2))
    }

    @Test
    fun getRecommendedProfile_unknownCurrentProfile_defaultsToBiking() {
        assertEquals(1, TrackingLocationPolicy.getRecommendedProfile(0.5f, -1))
        assertEquals(1, TrackingLocationPolicy.getRecommendedProfile(20f, 99))
    }

    @Test
    fun autoStartProfileIndex_isWalking() {
        assertEquals(0, TrackingLocationPolicy.getAutoStartProfileIndex())
    }
}
