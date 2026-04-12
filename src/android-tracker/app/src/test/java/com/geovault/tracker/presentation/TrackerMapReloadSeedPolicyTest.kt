package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackerMapReloadSeedPolicyTest {

    @Test
    fun streamSeed_isStableAcrossRosterOrderAndWhitespace() {
        val selection = TrackerMapGroupModeSelection(
            groupId = "g1",
            trackerIds = setOf("b", " a ")
        )
        val first = TrackerMapReloadSeedPolicy.streamSeed(
            TrackerMapStreamSeedInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = true,
                selectedTrackerId = "sel",
                displayedTrackerId = "disp",
                rosterTrackerIds = listOf("z", " y ", "x"),
                groupSelection = selection
            )
        )
        val second = TrackerMapReloadSeedPolicy.streamSeed(
            TrackerMapStreamSeedInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = true,
                selectedTrackerId = "sel",
                displayedTrackerId = "disp",
                rosterTrackerIds = listOf("x", "z", "y"),
                groupSelection = TrackerMapGroupModeSelection(
                    groupId = "g1",
                    trackerIds = setOf("a", "b")
                )
            )
        )

        assertEquals(first, second)
    }

    @Test
    fun trailSeed_changesWhenBoundaryChanges() {
        val base = TrackerMapReloadSeedPolicy.trailSeed(
            TrackerMapTrailSeedInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                activeTrackerId = "t1",
                sessionVisibleBoundaryId = 1L,
                rosterTrackerIds = listOf("t1"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )
        val changed = TrackerMapReloadSeedPolicy.trailSeed(
            TrackerMapTrailSeedInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                activeTrackerId = "t1",
                sessionVisibleBoundaryId = 2L,
                rosterTrackerIds = listOf("t1"),
                groupSelection = TrackerMapGroupModeSelection(groupId = null, trackerIds = emptySet())
            )
        )

        assertNotEquals(base, changed)
    }
}
