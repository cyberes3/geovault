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
            )
        )

        assertEquals(TrackerMapStreamingCommand.NoOp, command)
    }

    @Test
    fun resolve_singleSession_emptyStreamTargets_returnsStop() {
        // When the projector emits an empty stream target set (e.g. SINGLE_SESSION on the
        // selected tracker, where the dedicated view is history-only), the coordinator stops.
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                streamTargetIds = emptySet(),
                displayedTrackerId = "t1",
                displayedTrackerName = "Name",
            )
        )

        assertEquals(TrackerMapStreamingCommand.Stop, command)
    }

    @Test
    fun resolve_multiContext_groupTrustsProjectedTargetsIncludingSelected() {
        // STREAMING-COORDINATOR: the projector is the single source of truth for which trackers
        // belong in the group/all-queue subscription set; the coordinator just forwards what the
        // projector produced.
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                streamTargetIds = setOf("self", "other"),
                displayedTrackerId = "",
                displayedTrackerName = "",
            )
        )

        assertTrue(command is TrackerMapStreamingCommand.Start)
        val start = command as TrackerMapStreamingCommand.Start
        assertEquals(setOf("self", "other"), start.trackerIds)
    }

    @Test
    fun resolve_singleSession_displayedDifferent_returnsStart() {
        // For a single-session view of a non-selected tracker the projector emits
        // {displayedTrackerId}; the coordinator forwards it and keeps the display name.
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                streamTargetIds = setOf("t2"),
                displayedTrackerId = "t2",
                displayedTrackerName = "Tracker 2",
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
            )
        )

        assertTrue(command is TrackerMapStreamingCommand.Start)
        val start = command as TrackerMapStreamingCommand.Start
        assertEquals(setOf("a", "b"), start.trackerIds)
        assertEquals(null, start.trackerName)
    }

    @Test
    fun resolve_multiContext_singleProjectedTarget_keepsName() {
        // STREAMING-COORDINATOR: when the projector excludes the locally-recorded tracker from a
        // group / all-queue stream, the coordinator receives the already-filtered set and just
        // forwards it. Per-mode filtering and the locally-recorded exclusion live one layer up.
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                streamTargetIds = setOf("other"),
                displayedTrackerId = "other",
                displayedTrackerName = "Other",
            )
        )

        assertTrue(command is TrackerMapStreamingCommand.Start)
        val start = command as TrackerMapStreamingCommand.Start
        assertEquals(setOf("other"), start.trackerIds)
        assertEquals("Other", start.trackerName)
    }
}
