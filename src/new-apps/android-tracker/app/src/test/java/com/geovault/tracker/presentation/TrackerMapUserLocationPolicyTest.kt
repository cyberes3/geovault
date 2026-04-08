package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapUserLocationPolicyTest {
    private val policy = TrackerMapUserLocationPolicy()

    @Test
    fun evaluate_blocksWhenFollowLockNotArmedInSession() {
        val decision = policy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userFollowLockArmedThisSession = false,
                followLockEnabled = true,
                runtimeRunning = false
            )
        )

        assertFalse(decision.shouldStreamGps)
        assertFalse(decision.shouldEnablePuck)
        assertFalse(decision.shouldEnableFollowCamera)
        assertTrue(
            decision.blockers.contains(TrackerMapUserLocationBlocker.FollowLockNotArmedThisSession)
        )
    }

    @Test
    fun evaluate_allowsStreamingOnlyWhenAllGuardsPass() {
        val decision = policy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userFollowLockArmedThisSession = true,
                followLockEnabled = true,
                runtimeRunning = false
            )
        )

        assertTrue(decision.shouldStreamGps)
        assertTrue(decision.shouldEnablePuck)
        assertTrue(decision.shouldEnableFollowCamera)
        assertTrue(decision.blockers.isEmpty())
    }

    @Test
    fun evaluate_blocksWhenTrackingRuntimeIsActive() {
        val decision = policy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userFollowLockArmedThisSession = true,
                followLockEnabled = true,
                runtimeRunning = true
            )
        )

        assertFalse(decision.shouldStreamGps)
        assertTrue(decision.blockers.contains(TrackerMapUserLocationBlocker.RuntimeTrackingActive))
    }

    @Test
    fun evaluate_collectsAllExpectedBlockers() {
        val decision = policy.evaluate(
            TrackerMapUserLocationInput(
                isMapActive = false,
                hasLocationPermission = false,
                isMapReady = false,
                userFollowLockArmedThisSession = false,
                followLockEnabled = false,
                runtimeRunning = true
            )
        )

        assertEquals(
            setOf(
                TrackerMapUserLocationBlocker.MapInactive,
                TrackerMapUserLocationBlocker.MissingPermission,
                TrackerMapUserLocationBlocker.MapNotReady,
                TrackerMapUserLocationBlocker.FollowLockNotArmedThisSession,
                TrackerMapUserLocationBlocker.FollowLockDisabled,
                TrackerMapUserLocationBlocker.RuntimeTrackingActive,
            ),
            decision.blockers
        )
    }
}
