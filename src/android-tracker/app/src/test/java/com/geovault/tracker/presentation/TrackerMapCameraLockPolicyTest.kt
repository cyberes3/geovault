package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapCameraLockPolicyTest {
    private val policy = TrackerMapUserLocationPolicy()

    @Test
    fun recordingDoesNotBanPuckWhenViewingAnotherTracker() {
        val decision = policy.evaluate(
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
        val decision = policy.evaluate(
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
