package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapStreamTargetCoordinatorTest {

    @Test
    fun resolve_allQueueRunning_excludesSelectedTracker() {
        val result = TrackerMapStreamTargetCoordinator.resolve(
            TrackerMapStreamTargetInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = true,
                selectedTrackerId = "t1",
                displayedTrackerId = "",
                rosterTrackerIds = setOf("t1", "t2", "t3"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )

        assertEquals(setOf("t2", "t3"), result.streamTargetIds)
        assertEquals("", result.resolvedGroupId)
    }

    @Test
    fun resolve_groupMode_usesGroupTrackerIdsAndGroupId() {
        val result = TrackerMapStreamTargetCoordinator.resolve(
            TrackerMapStreamTargetInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                runtimeRunning = false,
                selectedTrackerId = "t1",
                displayedTrackerId = "t2",
                rosterTrackerIds = setOf("x"),
                groupSelection = TrackerMapGroupModeSelection(
                    groupId = "g1",
                    trackerIds = setOf("a", "b")
                )
            )
        )

        assertEquals(setOf("a", "b"), result.streamTargetIds)
        assertEquals("g1", result.resolvedGroupId)
    }

    @Test
    fun resolve_singleSession_targetsDisplayedWhenDifferentFromSelected() {
        val result = TrackerMapStreamTargetCoordinator.resolve(
            TrackerMapStreamTargetInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = true,
                selectedTrackerId = "t1",
                displayedTrackerId = "t2",
                rosterTrackerIds = setOf("ignored"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )

        assertEquals(setOf("t2"), result.streamTargetIds)
        assertEquals("", result.resolvedGroupId)
    }
}
