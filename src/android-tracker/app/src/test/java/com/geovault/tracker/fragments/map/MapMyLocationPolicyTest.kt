package com.geovault.tracker.fragments.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMyLocationPolicyTest {

    @Test
    fun selectedDefaultTrackerMode_hidesButtonAndDisablesPuck() {
        val decision = MapMyLocationPolicy.compute(
            MyLocationPolicyInput(
                trackingRunning = false,
                showMyLocationEnabledIntent = true,
                isSelectedDefaultTracker = true,
                gpsLockRequested = true,
                trackerOrLiveLockActive = false
            )
        )

        assertFalse(decision.myLocationModeActive)
        assertFalse(decision.shouldEnablePuck)
        assertFalse(decision.shouldTrackGpsCamera)
        assertFalse(decision.shouldShowButton)
    }

    @Test
    fun gpsLockTracksCamera_onlyWhenModeAndLockAreActive() {
        val enabledDecision = MapMyLocationPolicy.compute(
            MyLocationPolicyInput(
                trackingRunning = false,
                showMyLocationEnabledIntent = true,
                isSelectedDefaultTracker = false,
                gpsLockRequested = true,
                trackerOrLiveLockActive = false
            )
        )

        assertTrue(enabledDecision.myLocationModeActive)
        assertTrue(enabledDecision.shouldEnablePuck)
        assertTrue(enabledDecision.shouldTrackGpsCamera)
        assertTrue(enabledDecision.shouldShowButton)

        val lockSuppressedDecision = MapMyLocationPolicy.compute(
            MyLocationPolicyInput(
                trackingRunning = false,
                showMyLocationEnabledIntent = true,
                isSelectedDefaultTracker = false,
                gpsLockRequested = true,
                trackerOrLiveLockActive = true
            )
        )

        assertTrue(lockSuppressedDecision.myLocationModeActive)
        assertTrue(lockSuppressedDecision.shouldEnablePuck)
        assertFalse(lockSuppressedDecision.shouldTrackGpsCamera)
    }

    @Test
    fun trackingRunning_disablesPuckAndButtonRegardlessOfIntent() {
        val decision = MapMyLocationPolicy.compute(
            MyLocationPolicyInput(
                trackingRunning = true,
                showMyLocationEnabledIntent = true,
                isSelectedDefaultTracker = false,
                gpsLockRequested = true,
                trackerOrLiveLockActive = false
            )
        )

        assertTrue(decision.myLocationModeActive)
        assertFalse(decision.shouldEnablePuck)
        assertFalse(decision.shouldTrackGpsCamera)
        assertFalse(decision.shouldShowButton)
    }
}
