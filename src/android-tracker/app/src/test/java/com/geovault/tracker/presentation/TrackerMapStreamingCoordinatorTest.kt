package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapStreamingCoordinatorTest {

    @Test
    fun resolve_singleSession_blankDisplayed_returnsNoOp() {
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                streamTargetIds = emptySet(),
                displayedTrackerId = " ",
                displayedTrackerName = "Name",
                selectedTrackerId = "selected"
            )
        )

        assertEquals(TrackerMapStreamingCommand.NoOp, command)
    }

    @Test
    fun resolve_singleSession_displayedEqualsSelected_returnsStop() {
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                streamTargetIds = emptySet(),
                displayedTrackerId = "t1",
                displayedTrackerName = "Name",
                selectedTrackerId = "t1"
            )
        )

        assertEquals(TrackerMapStreamingCommand.Stop, command)
    }

    @Test
    fun resolve_singleSession_displayedDifferent_returnsStart() {
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                streamTargetIds = emptySet(),
                displayedTrackerId = "t2",
                displayedTrackerName = "Tracker 2",
                selectedTrackerId = "t1"
            )
        )

        assertTrue(command is TrackerMapStreamingCommand.Start)
        val start = command as TrackerMapStreamingCommand.Start
        assertEquals(setOf("t2"), start.trackerIds)
        assertEquals("Tracker 2", start.trackerName)
    }

    @Test
    fun resolve_multiContext_emptyTargets_returnsStop() {
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                streamTargetIds = emptySet(),
                displayedTrackerId = "t1",
                displayedTrackerName = "Name",
                selectedTrackerId = "t1"
            )
        )

        assertEquals(TrackerMapStreamingCommand.Stop, command)
    }

    @Test
    fun resolve_multiContext_multipleTargets_stripsName() {
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                streamTargetIds = setOf("a", "b"),
                displayedTrackerId = "a",
                displayedTrackerName = "A",
                selectedTrackerId = "z"
            )
        )

        assertTrue(command is TrackerMapStreamingCommand.Start)
        val start = command as TrackerMapStreamingCommand.Start
        assertEquals(setOf("a", "b"), start.trackerIds)
        assertEquals(null, start.trackerName)
    }
}
