package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackersGroupsSearchFiltersTest {

    @Test
    fun filterVisibleOwnerTrackersForSearch_emptyQueryReturnsVisibleOwnerTrackers() {
        val trackers = listOf(
            Tracker(id = "owned", name = "Owned", color = null, is_owner = true),
            Tracker(id = "shared", name = "Shared", color = null, is_owner = false),
            Tracker(id = "hidden", name = "Hidden", color = null, settings = mapOf("hidden" to true), is_owner = true),
        )

        val filtered = filterVisibleOwnerTrackersForSearch(trackers, "")

        assertEquals(listOf("owned"), filtered.map { it.id })
    }

    @Test
    fun filterVisibleOwnerTrackersForSearch_matchesNameOrOwnerEmail() {
        val trackers = listOf(
            Tracker(id = "name", name = "Alpha beacon", color = null, is_owner = true),
            Tracker(
                id = "owner",
                name = "Field unit",
                color = null,
                is_owner = true,
                owner_email = "alpha-owner@example.com",
            ),
            Tracker(id = "other", name = "Bravo", color = null, is_owner = true),
        )

        val filtered = filterVisibleOwnerTrackersForSearch(trackers, "alpha")

        assertEquals(listOf("name", "owner"), filtered.map { it.id })
    }

    @Test
    fun filterVisibleOwnerGroupsForSearch_emptyQueryReturnsVisibleOwnerGroups() {
        val groups = listOf(
            Group(id = "owned", name = "Owned", is_owner = true),
            Group(id = "shared", name = "Shared", is_owner = false),
            Group(id = "hidden", name = "Hidden", hidden = true, is_owner = true),
        )

        val filtered = filterVisibleOwnerGroupsForSearch(groups, "")

        assertEquals(listOf("owned"), filtered.map { it.id })
    }

    @Test
    fun filterVisibleOwnerGroupsForSearch_matchesNameOrOwnerEmail() {
        val groups = listOf(
            Group(id = "name", name = "Alpha team", is_owner = true),
            Group(
                id = "owner",
                name = "Field crew",
                is_owner = true,
                owner_email = "alpha-owner@example.com",
            ),
            Group(id = "other", name = "Bravo", is_owner = true),
        )

        val filtered = filterVisibleOwnerGroupsForSearch(groups, "alpha")

        assertEquals(listOf("name", "owner"), filtered.map { it.id })
    }
}
