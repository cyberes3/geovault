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
                runtimeRunning = false
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
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = true,
                runtimeRunning = false
            )
        )

        assertTrue(decision.shouldStreamGps)
        assertTrue(decision.shouldEnablePuck)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun evaluate_blocksWhenTrackingRuntimeIsActive() {
        val decision = policy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = true,
                runtimeRunning = true
            )
        )

        assertFalse(decision.shouldStreamGps)
        assertTrue(decision.blockers.contains(TrackerMapUserLocationBlocker.RuntimeTrackingActive))
    }

    @Test
    fun evaluate_locationRequestDoesNotNeedFollowLock() {
        val decision = policy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = true,
                runtimeRunning = false
            )
        )

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
                runtimeRunning = true
            )
        )

        assertEquals(
            setOf(
                TrackerMapUserLocationBlocker.MapInactive,
                TrackerMapUserLocationBlocker.MissingPermission,
                TrackerMapUserLocationBlocker.MapNotReady,
                TrackerMapUserLocationBlocker.LocationNotRequestedThisSession,
                TrackerMapUserLocationBlocker.RuntimeTrackingActive,
            ),
            decision.blockers
        )
    }
}
