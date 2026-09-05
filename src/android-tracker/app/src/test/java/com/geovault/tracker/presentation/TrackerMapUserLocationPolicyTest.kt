package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapUserLocationPolicyTest {
    private val policy = TrackerMapUserLocationPolicy()

    @Test
    fun evaluate_blocksWhenLocationNotRequestedInSession() {
        val decision = policy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = false,
            )
        )

        assertFalse(decision.shouldStreamGps)
        assertFalse(decision.shouldEnablePuck)
        assertTrue(
            decision.blockers.contains(TrackerMapUserLocationBlocker.LocationNotRequestedThisSession)
        )
    }

    @Test
    fun evaluate_allowsStreamingPuckOnlyWhenAllGuardsPass() {
        val decision = policy.evaluate(
            allowedInput()
        )

        assertTrue(decision.shouldStreamGps)
        assertTrue(decision.shouldEnablePuck)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun evaluate_recordingWhileViewingAnotherTracker_allowsPuck() {
        val decision = policy.evaluate(
            allowedInput(
                displayedTrackerId = "shared",
                locallyRecordedTrackerId = "self",
            )
        )

        assertTrue(decision.shouldStreamGps)
        assertTrue(decision.shouldEnablePuck)
        assertFalse(
            decision.blockers.contains(TrackerMapUserLocationBlocker.OwnRecordedTrackerOnScreen)
        )
    }

    @Test
    fun evaluate_ownRecordedTrackerOnScreen_hidesPuck() {
        val decision = policy.evaluate(
            allowedInput(
                displayedTrackerId = "self",
                locallyRecordedTrackerId = "self",
            )
        )

        assertFalse(decision.shouldStreamGps)
        assertFalse(decision.shouldEnablePuck)
        assertTrue(
            decision.blockers.contains(TrackerMapUserLocationBlocker.OwnRecordedTrackerOnScreen)
        )
    }

    @Test
    fun evaluate_inactiveWithLocationIntent_streamsWithoutPuck() {
        val decision = policy.evaluate(
            allowedInput(isMapActive = false)
        )

        assertTrue(decision.shouldStreamGps)
        assertFalse(decision.shouldEnablePuck)
        assertTrue(decision.blockers.contains(TrackerMapUserLocationBlocker.MapInactive))
    }

    @Test
    fun evaluate_locationRequestDoesNotNeedFollowLock() {
        val decision = policy.evaluate(allowedInput())

        assertTrue(decision.shouldStreamGps)
        assertTrue(decision.shouldEnablePuck)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun evaluate_collectsAllExpectedBlockers() {
        val decision = policy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = false,
                hasLocationPermission = false,
                isMapReady = false,
                userLocationRequestedThisSession = false,
                displayedTrackerId = "self",
                locallyRecordedTrackerId = "self",
            )
        )

        assertEquals(
            setOf(
                TrackerMapUserLocationBlocker.MapInactive,
                TrackerMapUserLocationBlocker.MissingPermission,
                TrackerMapUserLocationBlocker.MapNotReady,
                TrackerMapUserLocationBlocker.LocationNotRequestedThisSession,
                TrackerMapUserLocationBlocker.OwnRecordedTrackerOnScreen,
            ),
            decision.blockers
        )
    }

    private fun allowedInput(
        isMapActive: Boolean = true,
        displayedTrackerId: String = "",
        locallyRecordedTrackerId: String = "",
    ): TrackerMapUserLocationInput {
        return TrackerMapUserLocationInput(
            isMapActive = isMapActive,
            hasLocationPermission = true,
            isMapReady = true,
            userLocationRequestedThisSession = true,
            displayedTrackerId = displayedTrackerId,
            locallyRecordedTrackerId = locallyRecordedTrackerId,
        )
    }
}
