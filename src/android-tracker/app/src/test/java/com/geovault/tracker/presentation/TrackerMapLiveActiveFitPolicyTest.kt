package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapLiveActiveFitPolicyTest {

    @Test
    fun visibility_singleSessionNoTrailPoints_hidden() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_singleSessionWithTrailPoints_shown() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = true,
                isSelectedDefaultTracker = false,
            )
        )
        assertTrue(result.showButton)
        assertTrue(result.buttonEnabled)
    }

    @Test
    fun visibility_singleSessionDefaultTracker_hidden() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = true,
                isSelectedDefaultTracker = true,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_allQueueWithFollowArmed_hiddenBecauseLockFabOwnsLiveFit() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_groupWithFollowArmed_hiddenBecauseLockFabOwnsLiveFit() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                runtimeRunning = false,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_allQueueRuntimeRunningWithFollowArmed_hiddenBecauseLockFabOwnsLiveFit() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = true,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun resolveLockArmed_returnsSingleTrackerLockedAsIs() {
        assertTrue(TrackerMapLiveActiveFitPolicy.resolveLockArmed(singleTrackerLocked = true))
        assertFalse(TrackerMapLiveActiveFitPolicy.resolveLockArmed(singleTrackerLocked = false))
    }
}
