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
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
                hasMultipleTrackersOnMap = true,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_singleSessionWithTrailPointsAndMultipleTrackers_shown() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = true,
                isSelectedDefaultTracker = false,
                hasMultipleTrackersOnMap = true,
            )
        )
        assertTrue(result.showButton)
        assertTrue(result.buttonEnabled)
    }

    @Test
    fun visibility_singleSessionWithOnlyOneTrackerOnMap_hidden() {
        // Fitting bounds around a single point is indistinguishable from centering on it -- the
        // toggle has nothing to offer until a second tracker/position (own GPS puck) shares the
        // map. A locally-recorded tracker different from the displayed one deliberately does NOT
        // count here -- SINGLE_SESSION bounds/point-routing never render or union it (see
        // MapScreen.kt's hasMultipleTrackersOnMap comment), so gating on it would make the
        // toggle a no-op.
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = true,
                isSelectedDefaultTracker = false,
                hasMultipleTrackersOnMap = false,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_singleSessionDefaultTracker_hidden() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = true,
                isSelectedDefaultTracker = true,
                hasMultipleTrackersOnMap = true,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_allQueueWithFollowArmed_hiddenBecauseLockFabOwnsLiveFit() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
                hasMultipleTrackersOnMap = true,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_groupWithFollowArmed_hiddenBecauseLockFabOwnsLiveFit() {
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                followLockArmed = true,
                liveActiveFitEnabled = false,
                hasTrailPoints = false,
                isSelectedDefaultTracker = false,
                hasMultipleTrackersOnMap = true,
            )
        )
        assertFalse(result.showButton)
    }

    @Test
    fun visibility_singleSessionMultipleTrackersButFollowLockNotArmed_hiddenAndDisabled() {
        // STUCK-LIVE-FIT REGRESSION GUARD: the secondary FAB (and therefore the only UI path to
        // clear liveActiveFitEnabled through this toggle) is gone once followLockArmed is false,
        // even if liveActiveFitEnabled itself is still true -- callers must not rely on this FAB
        // alone to ever clear a stuck toggle (see MapScreen.kt's hasMultipleTrackersOnMap
        // LaunchedEffect, which auto-clears it instead).
        val result = TrackerMapLiveActiveFitPolicy.resolveVisibility(
            LiveActiveFitInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                followLockArmed = false,
                liveActiveFitEnabled = true,
                hasTrailPoints = true,
                isSelectedDefaultTracker = false,
                hasMultipleTrackersOnMap = true,
            )
        )
        assertFalse(result.showButton)
        assertFalse(result.buttonEnabled)
    }

    @Test
    fun resolveLockArmed_returnsSingleTrackerLockedAsIs() {
        assertTrue(TrackerMapLiveActiveFitPolicy.resolveLockArmed(singleTrackerLocked = true))
        assertFalse(TrackerMapLiveActiveFitPolicy.resolveLockArmed(singleTrackerLocked = false))
    }

    @Test
    fun composesWithSelectionLock_trueOnlyInSingleSession() {
        assertTrue(
            TrackerMapLiveActiveFitPolicy.composesWithSelectionLock(TrackerMapDisplayMode.SINGLE_SESSION)
        )
        assertFalse(
            TrackerMapLiveActiveFitPolicy.composesWithSelectionLock(TrackerMapDisplayMode.ALL_QUEUE)
        )
        assertFalse(
            TrackerMapLiveActiveFitPolicy.composesWithSelectionLock(TrackerMapDisplayMode.GROUP_PLACEHOLDER)
        )
    }
}
