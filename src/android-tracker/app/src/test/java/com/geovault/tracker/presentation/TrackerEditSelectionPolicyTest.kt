package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerEditSelectionPolicyTest {

    @Test
    fun `already selected tracker save updates metadata without selection restart`() {
        val action = TrackerEditSelectionPolicy.resolve(
            TrackerEditSelectionInput(
                editedTrackerId = "tracker-1",
                selectedTrackerId = "tracker-1",
                setAsSelectedTracker = true,
            )
        )

        assertEquals(TrackerEditSelectionAction.NoSelectionChangeAlreadySelected, action)
    }

    @Test
    fun `checking set selected for a different tracker selects different tracker`() {
        val action = TrackerEditSelectionPolicy.resolve(
            TrackerEditSelectionInput(
                editedTrackerId = "tracker-2",
                selectedTrackerId = "tracker-1",
                setAsSelectedTracker = true,
            )
        )

        assertEquals(TrackerEditSelectionAction.SelectDifferentTracker, action)
    }

    @Test
    fun `unchecking selected tracker clears selected tracker`() {
        val action = TrackerEditSelectionPolicy.resolve(
            TrackerEditSelectionInput(
                editedTrackerId = "tracker-1",
                selectedTrackerId = "tracker-1",
                setAsSelectedTracker = false,
            )
        )

        assertEquals(TrackerEditSelectionAction.ClearSelectedTracker, action)
    }

    @Test
    fun `editing unselected tracker without selecting it has no selection side effect`() {
        val action = TrackerEditSelectionPolicy.resolve(
            TrackerEditSelectionInput(
                editedTrackerId = "tracker-2",
                selectedTrackerId = "tracker-1",
                setAsSelectedTracker = false,
            )
        )

        assertEquals(TrackerEditSelectionAction.NoSelectionChangeUnselected, action)
    }

    @Test
    fun `blank edited tracker id is ignored`() {
        val action = TrackerEditSelectionPolicy.resolve(
            TrackerEditSelectionInput(
                editedTrackerId = "   ",
                selectedTrackerId = "tracker-1",
                setAsSelectedTracker = true,
            )
        )

        assertEquals(TrackerEditSelectionAction.NoSelectionChangeUnselected, action)
    }
}
