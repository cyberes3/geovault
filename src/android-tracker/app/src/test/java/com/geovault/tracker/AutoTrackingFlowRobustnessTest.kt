package com.geovault.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AutoTrackingFlowRobustnessTest {

    @Test
    fun autoModeStartsWalking_thenTransitionsBasedOnSpeedSequence() {
        var profile = TrackingLocationPolicy.getAutoStartProfileIndex()
        assertEquals(0, profile)

        profile = TrackingLocationPolicy.getRecommendedProfile(0.8f, profile)
        assertEquals(0, profile)

        profile = TrackingLocationPolicy.getRecommendedProfile(3.0f, profile)
        assertEquals(1, profile)

        profile = TrackingLocationPolicy.getRecommendedProfile(9.0f, profile)
        assertEquals(2, profile)
    }

    @Test
    fun resumeResetSpeedAvoidsStaleHighSpeedBias_onFirstDecision() {
        val staleProfileDecision = TrackingLocationPolicy.getRecommendedProfile(9.5f, 2)
        assertEquals(2, staleProfileDecision)

        val resumedProfileDecision = TrackingLocationPolicy.getRecommendedProfile(
            speedMps = 0.8f,
            currentProfile = TrackingLocationPolicy.getAutoStartProfileIndex()
        )
        assertEquals(0, resumedProfileDecision)
        assertNotEquals(staleProfileDecision, resumedProfileDecision)
    }
}
