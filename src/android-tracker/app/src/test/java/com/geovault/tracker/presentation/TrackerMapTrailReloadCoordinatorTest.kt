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
    fun resolvePlan_singleSessionRunningForSelectedTracker_usesLocalQueue() {
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

        assertEquals(TrackerMapTrailSource.SINGLE_QUEUE, plan.source)
        assertEquals("local", plan.activeTrackerId)
    }

    @Test
    fun resolvePlan_allQueueRunning_usesRosterWithOverlayOnActive() {
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
    fun resolvePlan_groupMode_stopped_hasNoOverlay_andCarriesGroupId() {
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
