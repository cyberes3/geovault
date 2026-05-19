package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerEditSelectionPolicyTest {

    @Test
    fun resolve_alreadySelectedDefault_doesNotRestartTracking() {
        val decision = TrackerEditSelectionPolicy.resolve(
            editedTrackerId = "tracker-1",
            selectedTrackerId = "tracker-1",
            setAsSelectedTracker = true,
        )

        assertEquals(TrackerEditSelectionAction.None, decision.action)
        assertFalse(decision.shouldRestartTracking)
    }

    @Test
    fun resolve_newDefault_selectsAndRestartsTracking() {
        val decision = TrackerEditSelectionPolicy.resolve(
            editedTrackerId = "tracker-2",
            selectedTrackerId = "tracker-1",
            setAsSelectedTracker = true,
        )

        assertEquals(TrackerEditSelectionAction.SelectEditedTracker, decision.action)
        assertTrue(decision.shouldRestartTracking)
    }

    @Test
    fun resolve_uncheckedCurrentDefault_clearsSelectionWithoutRestartFlag() {
        val decision = TrackerEditSelectionPolicy.resolve(
            editedTrackerId = "tracker-1",
            selectedTrackerId = "tracker-1",
            setAsSelectedTracker = false,
        )

        assertEquals(TrackerEditSelectionAction.ClearSelectedTracker, decision.action)
        assertFalse(decision.shouldRestartTracking)
    }
}
