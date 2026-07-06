package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapSelectionCardPolicyTest {

    private fun selectionCard(trackerId: String) = TrackerMapSelectionCard(
        trackerId = trackerId,
        trackerName = "Tracker $trackerId",
        latitude = 1.0,
        longitude = 2.0,
        lastUpdatedMs = null,
        accuracyMeters = null,
        isOwned = true,
    )

    @Test
    fun applySelectionCard_noExistingLock_showsCardWithoutLocking() {
        val state = TrackerMapUiState(selectionLockTrackerId = "")

        val next = TrackerMapSelectionCardPolicy.applySelectionCard(state, selectionCard("tracker-1"))

        assertTrue(next.isBottomCardVisible)
        assertEquals("tracker-1", next.selectedMapTracker?.trackerId)
        assertEquals("", next.selectionLockTrackerId)
    }

    @Test
    fun applySelectionCard_tappingTheLockedTracker_preservesLockAndLiveFit() {
        val state = TrackerMapUiState(
            selectionLockTrackerId = "tracker-1",
            liveActiveFitEnabled = true,
        )

        val next = TrackerMapSelectionCardPolicy.applySelectionCard(state, selectionCard("tracker-1"))

        assertEquals("tracker-1", next.selectionLockTrackerId)
        assertTrue(
            "Tapping the SAME tracker that's already locked must not drop live active fit.",
            next.liveActiveFitEnabled,
        )
    }

    @Test
    fun applySelectionCard_tappingADifferentTracker_dropsMismatchedLockAndLiveFit() {
        // ORPHAN GUARD REGRESSION: live active fit in SINGLE_SESSION composes with an existing
        // selection lock. Tapping a different tracker's marker drops the mismatched lock; without
        // also clearing liveActiveFitEnabled it would be left stranded on with no lock left to
        // modify (see TrackerMapLiveActiveFitPolicy's class doc).
        val state = TrackerMapUiState(
            selectionLockTrackerId = "tracker-1",
            liveActiveFitEnabled = true,
        )

        val next = TrackerMapSelectionCardPolicy.applySelectionCard(state, selectionCard("tracker-2"))

        assertEquals("", next.selectionLockTrackerId)
        assertFalse(next.liveActiveFitEnabled)
        assertEquals("tracker-2", next.selectedMapTracker?.trackerId)
    }

    @Test
    fun applySelectionCard_noExistingLock_tappingAnyTracker_leavesLiveFitUntouched() {
        // liveActiveFitEnabled=true with no selection lock at all isn't the orphan scenario this
        // guard targets (e.g. ALL_QUEUE/GROUP_PLACEHOLDER, where live active fit is a standalone
        // toggle) -- only a genuine lock-tracker mismatch should clear it.
        val state = TrackerMapUiState(
            selectionLockTrackerId = "",
            liveActiveFitEnabled = true,
        )

        val next = TrackerMapSelectionCardPolicy.applySelectionCard(state, selectionCard("tracker-2"))

        assertTrue(next.liveActiveFitEnabled)
    }

    @Test
    fun applySelectionCard_trimsSelectionLockIdBeforeComparing() {
        val state = TrackerMapUiState(
            selectionLockTrackerId = " tracker-1 ",
            liveActiveFitEnabled = true,
        )

        val next = TrackerMapSelectionCardPolicy.applySelectionCard(state, selectionCard("tracker-1"))

        assertEquals("tracker-1", next.selectionLockTrackerId)
        assertTrue(next.liveActiveFitEnabled)
    }
}
