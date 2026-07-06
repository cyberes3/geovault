package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapLockFabPolicyTest {

    private fun input(
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String = "",
        selectionLockTrackerId: String = "",
        liveActiveFitEnabled: Boolean = false,
        followLockEnabled: Boolean = false,
    ) = TrackerMapLockFabInput(
        mode = mode,
        displayedTrackerId = displayedTrackerId,
        selectionLockTrackerId = selectionLockTrackerId,
        liveActiveFitEnabled = liveActiveFitEnabled,
        followLockEnabled = followLockEnabled,
    )

    @Test
    fun resolve_singleSessionWithDisplayedTracker_returnsSelectionLockUnlocked() {
        val behavior = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "tracker-1",
                selectionLockTrackerId = "",
            )
        )
        val selectionLock = behavior as TrackerMapLockFabBehavior.SelectionLock
        assertEquals("tracker-1", selectionLock.displayedTrackerId)
        assertFalse(selectionLock.isLocked)
    }

    @Test
    fun resolve_singleSessionWithMatchingSelectionLock_returnsSelectionLockLocked() {
        val behavior = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "tracker-1",
                selectionLockTrackerId = "tracker-1",
            )
        )
        val selectionLock = behavior as TrackerMapLockFabBehavior.SelectionLock
        assertTrue(selectionLock.isLocked)
    }

    @Test
    fun resolve_singleSessionWithMismatchedSelectionLock_returnsSelectionLockUnlocked() {
        val behavior = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "tracker-1",
                selectionLockTrackerId = "tracker-2",
            )
        )
        val selectionLock = behavior as TrackerMapLockFabBehavior.SelectionLock
        assertFalse(selectionLock.isLocked)
    }

    @Test
    fun resolve_singleSessionTrimsDisplayedAndSelectionLockIdsBeforeComparing() {
        val behavior = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "  tracker-1  ",
                selectionLockTrackerId = " tracker-1 ",
            )
        )
        val selectionLock = behavior as TrackerMapLockFabBehavior.SelectionLock
        assertEquals("tracker-1", selectionLock.displayedTrackerId)
        assertTrue(selectionLock.isLocked)
    }

    @Test
    fun resolve_singleSessionWithBlankDisplayedTrackerId_returnsFollowLock() {
        val behavior = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "",
                followLockEnabled = true,
            )
        )
        assertEquals(TrackerMapLockFabBehavior.FollowLock(isEnabled = true), behavior)
    }

    @Test
    fun resolve_singleSessionWithWhitespaceOnlyDisplayedTrackerId_returnsFollowLock() {
        // Whitespace-only ids must be treated the same as blank -- the trimmed check is what
        // decides between the SelectionLock and FollowLock branches.
        val behavior = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "   ",
                followLockEnabled = false,
            )
        )
        assertEquals(TrackerMapLockFabBehavior.FollowLock(isEnabled = false), behavior)
    }

    @Test
    fun resolve_allQueue_returnsLiveActiveFitRegardlessOfDisplayedTracker() {
        val behaviorWithTracker = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                displayedTrackerId = "tracker-1",
                liveActiveFitEnabled = true,
            )
        )
        assertEquals(TrackerMapLockFabBehavior.LiveActiveFit(isEnabled = true), behaviorWithTracker)

        val behaviorWithoutTracker = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                displayedTrackerId = "",
                liveActiveFitEnabled = false,
            )
        )
        assertEquals(TrackerMapLockFabBehavior.LiveActiveFit(isEnabled = false), behaviorWithoutTracker)
    }

    @Test
    fun resolve_groupPlaceholder_returnsLiveActiveFitRegardlessOfDisplayedTracker() {
        val behaviorWithTracker = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                displayedTrackerId = "tracker-1",
                liveActiveFitEnabled = true,
            )
        )
        assertEquals(TrackerMapLockFabBehavior.LiveActiveFit(isEnabled = true), behaviorWithTracker)

        val behaviorWithoutTracker = TrackerMapLockFabPolicy.resolve(
            input(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                displayedTrackerId = "",
                liveActiveFitEnabled = false,
            )
        )
        assertEquals(TrackerMapLockFabBehavior.LiveActiveFit(isEnabled = false), behaviorWithoutTracker)
    }
}
