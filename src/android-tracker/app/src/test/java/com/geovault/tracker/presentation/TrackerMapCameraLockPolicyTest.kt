package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.geovault.tracker.map.MapRenderMath
class TrackerMapCameraLockPolicyTest {
    
    @Test
    fun recordingDoesNotBanPuckWhenViewingAnotherTracker() {
        val decision = MapRenderMath.evaluateUserLocation(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = true,
                displayedTrackerId = "shared",
                locallyRecordedTrackerId = "self",
            )
        )
        assertTrue(decision.shouldEnablePuck)
        assertFalse(
            decision.blockers.contains(TrackerMapUserLocationBlocker.OwnRecordedTrackerOnScreen)
        )
    }

    @Test
    fun ownRecordedTrackerOnScreenHidesPuck() {
        val decision = MapRenderMath.evaluateUserLocation(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = true,
                displayedTrackerId = "self",
                locallyRecordedTrackerId = "self",
            )
        )
        assertFalse(decision.shouldEnablePuck)
        assertTrue(
            decision.blockers.contains(TrackerMapUserLocationBlocker.OwnRecordedTrackerOnScreen)
        )
    }
}
