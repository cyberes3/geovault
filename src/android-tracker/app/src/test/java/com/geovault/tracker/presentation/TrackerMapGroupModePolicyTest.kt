package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapGroupModePolicyTest {

    @Test
    fun resolveSelection_prefersGroupContainingPreferredTracker() {
        val a = group(id = "a", name = "A", trackIds = listOf("t1"))
        val b = group(id = "b", name = "B", trackIds = listOf("t2", "t3"))
        val selection = TrackerMapGroupModePolicy.resolveSelection(
            groups = listOf(a, b),
            hiddenGroupIds = emptySet(),
            hiddenTrackIds = emptySet(),
            hiddenOwnerTrackerIds = emptySet(),
            rosterTrackerIds = setOf("t1", "t2", "t3"),
            preferredGroupId = null,
            preferredTrackerId = "t3"
        )
        assertEquals("b", selection.groupId)
        assertEquals(setOf("t2", "t3"), selection.trackerIds)
    }

    @Test
    fun resolveSelection_fallsBackToFirstSortedEligibleGroup() {
        val z = group(id = "z", name = "Zulu", trackIds = listOf("t9"))
        val a = group(id = "a", name = "Alpha", trackIds = listOf("t1", "t2"))
        val selection = TrackerMapGroupModePolicy.resolveSelection(
            groups = listOf(z, a),
            hiddenGroupIds = emptySet(),
            hiddenTrackIds = setOf("t2"),
            hiddenOwnerTrackerIds = emptySet(),
            rosterTrackerIds = setOf("t1", "t2", "t9"),
            preferredGroupId = null,
            preferredTrackerId = null
        )
        assertEquals("a", selection.groupId)
        assertEquals(setOf("t1"), selection.trackerIds)
    }

    @Test
    fun resolveSelection_filtersHiddenAndUnacceptedGroups() {
        val acceptedHidden = group(id = "gh", name = "Hidden", trackIds = listOf("x"))
        val unaccepted = group(id = "gu", name = "Unaccepted", trackIds = listOf("y"), isAccepted = false)
        val visible = group(id = "gv", name = "Visible", trackIds = listOf("v1"))
        val selection = TrackerMapGroupModePolicy.resolveSelection(
            groups = listOf(acceptedHidden, unaccepted, visible),
            hiddenGroupIds = setOf("gh"),
            hiddenTrackIds = emptySet(),
            hiddenOwnerTrackerIds = emptySet(),
            rosterTrackerIds = setOf("x", "y", "v1"),
            preferredGroupId = null,
            preferredTrackerId = "x"
        )
        assertEquals("gv", selection.groupId)
        assertEquals(setOf("v1"), selection.trackerIds)
    }

    @Test
    fun resolveSelection_prefersExplicitGroupIdWhenStillEligible() {
        val a = group(id = "ga", name = "Alpha", trackIds = listOf("t1"))
        val b = group(id = "gb", name = "Beta", trackIds = listOf("t2"))
        val selection = TrackerMapGroupModePolicy.resolveSelection(
            groups = listOf(a, b),
            hiddenGroupIds = emptySet(),
            hiddenTrackIds = emptySet(),
            hiddenOwnerTrackerIds = emptySet(),
            rosterTrackerIds = setOf("t1", "t2"),
            preferredGroupId = "gb",
            preferredTrackerId = "t1"
        )
        assertEquals("gb", selection.groupId)
        assertEquals(setOf("t2"), selection.trackerIds)
    }

    @Test
    fun resolveSelection_filtersOwnerHiddenTrackers() {
        val group = group(id = "ga", name = "Alpha", trackIds = listOf("t1", "t2"))
        val selection = TrackerMapGroupModePolicy.resolveSelection(
            groups = listOf(group),
            hiddenGroupIds = emptySet(),
            hiddenTrackIds = emptySet(),
            hiddenOwnerTrackerIds = setOf("t2"),
            rosterTrackerIds = setOf("t1", "t2"),
            preferredGroupId = null,
            preferredTrackerId = null
        )
        assertEquals("ga", selection.groupId)
        assertEquals(setOf("t1"), selection.trackerIds)
    }

    @Test
    fun resolveSelection_excludesGroupMemberThatFellOutOfLiveRoster() {
        // Regression test: `group.track_ids` is fetched independently of (and can lag behind)
        // the tracker roster, so a deleted/unshared member can still be listed on the group
        // for a while. Without intersecting against the live roster, that stale id would stay
        // "eligible" -- re-subscribing the websocket to a dead tracker and leaving a ghost
        // trail on screen for something TrackerMapRosterRemovalPolicy already tore down.
        val group = group(id = "ga", name = "Alpha", trackIds = listOf("t1", "t2", "deleted"))
        val selection = TrackerMapGroupModePolicy.resolveSelection(
            groups = listOf(group),
            hiddenGroupIds = emptySet(),
            hiddenTrackIds = emptySet(),
            hiddenOwnerTrackerIds = emptySet(),
            rosterTrackerIds = setOf("t1", "t2"), // "deleted" no longer exists on the roster
            preferredGroupId = null,
            preferredTrackerId = null
        )
        assertEquals("ga", selection.groupId)
        assertEquals(setOf("t1", "t2"), selection.trackerIds)
    }

    @Test
    fun resolveSelection_groupBecomesIneligibleWhenAllMembersFellOutOfRoster() {
        val stale = group(id = "gs", name = "Stale", trackIds = listOf("gone1", "gone2"))
        val fresh = group(id = "gf", name = "Fresh", trackIds = listOf("t1"))
        val selection = TrackerMapGroupModePolicy.resolveSelection(
            groups = listOf(stale, fresh),
            hiddenGroupIds = emptySet(),
            hiddenTrackIds = emptySet(),
            hiddenOwnerTrackerIds = emptySet(),
            rosterTrackerIds = setOf("t1"),
            preferredGroupId = "gs",
            preferredTrackerId = null
        )
        // "gs" is explicitly preferred but has zero live members left, so it must not be
        // selected -- resolution falls through to the only remaining eligible group.
        assertEquals("gf", selection.groupId)
        assertEquals(setOf("t1"), selection.trackerIds)
    }

    private fun group(
        id: String,
        name: String,
        trackIds: List<String>,
        isAccepted: Boolean = true
    ) = Group(
        id = id,
        name = name,
        track_ids = trackIds,
        is_accepted = isAccepted
    )
}
