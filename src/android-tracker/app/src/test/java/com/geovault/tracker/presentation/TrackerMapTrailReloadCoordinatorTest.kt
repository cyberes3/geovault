package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapTrailReloadCoordinatorTest {

    @Test
    fun resolvePlan_singleSessionStopped_withActiveTracker_loadsSingleFromServer() {
        val plan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                selectedTrackerId = "t1",
                activeTrackerId = "t1",
                rosterTrackerIds = setOf("t1", "t2"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )

        assertEquals(TrackerMapTrailSource.SINGLE_SERVER, plan.source)
        assertEquals("t1", plan.singleTrackerId)
        assertEquals(emptySet<String>(), plan.trackerIds)
    }

    @Test
    fun resolvePlan_singleSessionRunningForDifferentDisplayedTracker_loadsDisplayedFromServer() {
        val plan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = true,
                selectedTrackerId = "local",
                activeTrackerId = "remote",
                rosterTrackerIds = setOf("local", "remote"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )

        assertEquals(TrackerMapTrailSource.SINGLE_SERVER, plan.source)
        assertEquals("remote", plan.singleTrackerId)
        assertEquals("remote", plan.activeTrackerId)
    }

    @Test
    fun resolvePlan_singleSessionRunningForSelectedTracker_loadsServerWithLocalOverlay() {
        val plan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = true,
                selectedTrackerId = "local",
                activeTrackerId = "local",
                rosterTrackerIds = setOf("local", "remote"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )

        assertEquals(TrackerMapTrailSource.SINGLE_SERVER, plan.source)
        assertEquals("local", plan.singleTrackerId)
        assertEquals("local", plan.overlayTrackerId)
        assertEquals("local", plan.activeTrackerId)
    }

    @Test
    fun resolvePlan_allQueueRunning_loadsServerHistoryForFullRosterIncludingSelected() {
        // GROUP / ALL-QUEUE TRAIL HISTORY: load history for every visible tracker, including the
        // user's own / locally-recorded one. The local-overlay path is additive on top of server
        // history; it is not a substitute, and the previous "exclude selected/locallyRecorded
        // from server history" rule left the user's own trail blank in roster mode whenever the
        // queue did not contain enough recent points.
        val plan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = true,
                selectedTrackerId = "active",
                activeTrackerId = "active",
                rosterTrackerIds = setOf("active", "t2", " "),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )

        assertEquals(TrackerMapTrailSource.MULTI_SERVER, plan.source)
        assertEquals(setOf("active", "t2"), plan.trackerIds)
        assertEquals("active", plan.overlayTrackerId)
        assertEquals("active", plan.activeTrackerId)
    }

    @Test
    fun resolvePlan_allQueueNotRunning_includesSelectedInServerHistory() {
        // GROUP / ALL-QUEUE TRAIL HISTORY: same rationale as above for the not-running case;
        // the user expects to see their own trail alongside the rest of the roster.
        val plan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = false,
                selectedTrackerId = "selected",
                activeTrackerId = "selected",
                rosterTrackerIds = setOf("selected", "remote"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )

        assertEquals(TrackerMapTrailSource.MULTI_SERVER, plan.source)
        assertEquals(setOf("selected", "remote"), plan.trackerIds)
        assertNull(plan.overlayTrackerId)
    }

    @Test
    fun resolvePlan_groupMode_stopped_includesSelectedInServerHistoryAndCarriesGroupId() {
        // GROUP TRAIL HISTORY: in group mode the user explicitly chose a multi-tracker view that
        // may include their own tracker. Server history must include the selected tracker so the
        // user actually sees their own trail on the group map.
        val plan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                runtimeRunning = false,
                selectedTrackerId = "t1",
                activeTrackerId = "t1",
                rosterTrackerIds = setOf("x"),
                groupSelection = TrackerMapGroupModeSelection(
                    groupId = "g1",
                    trackerIds = setOf("t1", "t2")
                )
            )
        )

        assertEquals(TrackerMapTrailSource.MULTI_SERVER, plan.source)
        assertEquals(setOf("t1", "t2"), plan.trackerIds)
        assertNull(plan.overlayTrackerId)
        assertEquals("g1", plan.resolvedGroupId)
    }

    @Test
    fun resolvePlan_groupModeRunning_overlayOnlyWhenActiveInGroup() {
        val notMemberPlan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                runtimeRunning = true,
                selectedTrackerId = "t9",
                activeTrackerId = "t9",
                rosterTrackerIds = setOf("x"),
                groupSelection = TrackerMapGroupModeSelection(
                    groupId = "g1",
                    trackerIds = setOf("t1", "t2")
                )
            )
        )

        assertNull(notMemberPlan.overlayTrackerId)
    }
}
