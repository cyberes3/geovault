package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapRecentDataWindowChangePolicyTest {

    @Test
    fun resolve_singleSession_displayedTracker_refreshesServer() {
        val action = TrackerMapRecentDataWindowChangePolicy.resolve(
            changedTrackerIds = setOf("tracker-1"),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
            selectedTrackerId = "tracker-1",
            rosterTrackerIds = setOf("tracker-1"),
            groupTrackerIds = emptySet(),
        )
        assertTrue(action.reprojectImmediately)
        assertEquals(setOf("tracker-1"), action.serverRefreshTrackerIds)
        assertEquals(action.serverRefreshTrackerIds, action.invalidateGeometryCache)
    }

    @Test
    fun resolve_singleSession_nonDisplayedTracker_doesNotRefreshServer() {
        val action = TrackerMapRecentDataWindowChangePolicy.resolve(
            changedTrackerIds = setOf("tracker-2"),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
            selectedTrackerId = "tracker-1",
            rosterTrackerIds = setOf("tracker-1", "tracker-2"),
            groupTrackerIds = emptySet(),
        )
        assertTrue(action.reprojectImmediately)
        assertTrue(action.serverRefreshTrackerIds.isEmpty())
    }

    @Test
    fun resolve_groupMode_refreshesChangedGroupMember() {
        val action = TrackerMapRecentDataWindowChangePolicy.resolve(
            changedTrackerIds = setOf("b"),
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            displayedTrackerId = "",
            selectedTrackerId = "a",
            rosterTrackerIds = emptySet(),
            groupTrackerIds = setOf("a", "b"),
        )
        assertEquals(setOf("b"), action.serverRefreshTrackerIds)
    }

    @Test
    fun resolve_allQueue_refreshesChangedRosterMember() {
        val action = TrackerMapRecentDataWindowChangePolicy.resolve(
            changedTrackerIds = setOf("z"),
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            displayedTrackerId = "",
            selectedTrackerId = "a",
            rosterTrackerIds = setOf("a", "z"),
            groupTrackerIds = emptySet(),
        )
        assertEquals(setOf("z"), action.serverRefreshTrackerIds)
    }

    @Test
    fun resolve_whileStreaming_stillRefreshesDisplayedTracker() {
        val action = TrackerMapRecentDataWindowChangePolicy.resolve(
            changedTrackerIds = setOf("tracker-1"),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker-1",
            selectedTrackerId = "tracker-1",
            rosterTrackerIds = setOf("tracker-1"),
            groupTrackerIds = emptySet(),
        )
        assertEquals(setOf("tracker-1"), action.serverRefreshTrackerIds)
    }
}
