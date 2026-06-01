package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.history.TrackerHistoryKey
import com.geovault.tracker.history.TrackerHistoryPoint
import com.geovault.tracker.history.TrackerHistoryProvenance
import com.geovault.tracker.history.TrackerHistorySnapshot
import com.geovault.tracker.history.TrackerHistoryWindow
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapHistoryUiSyncTest {
    @Test
    fun historyTrackerIdsForRender_groupModeUsesVisibleRosterNotFullGroupPlan() {
        val plan = TrackerMapStreamingPlan(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedTrackerId = "a",
            displayedTrackerId = "a",
            displayedTrackerName = "A",
            resolvedGroupId = "g1",
            groupTrackerIds = setOf("a", "b", "hidden"),
            visibleRosterTrackerIds = setOf("a", "b", "hidden"),
            locallyRecordedTrackerIds = emptySet(),
            remoteSubscriptionIds = emptySet(),
            acceptedRemoteTrackerIds = emptySet(),
            localOverlayTrackerIds = emptySet(),
            trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.MULTI_SERVER),
        )
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtime = TrackingRuntimeSnapshot(),
        )
        val ids = TrackerMapHistoryUiSync.historyTrackerIdsForRender(
            state = state,
            plan = plan,
            visibleTrackerIds = setOf("a", "b"),
        )
        assertEquals(setOf("a", "b"), ids)
    }

    @Test
    fun hasAuthoritativeServerTrunk_requiresNonEmptyNonDegradedTrunk() {
        val window = TrackerHistoryWindow("all")
        val key = TrackerHistoryKey("t1", window)
        val trunkPoint = TrackerHistoryPoint(
            trackerId = "t1",
            timestampMs = 1L,
            latitude = 1.0,
            longitude = 2.0,
            provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
        )
        val authoritative = TrackerHistorySnapshot(
            key = key,
            trunk = listOf(trunkPoint),
            overlay = emptyList(),
            points = listOf(trunkPoint),
            committedAtMs = 1L,
            generation = 1L,
            complete = true,
            degradedLocalOnly = false,
        )
        val trackers = listOf(
            Tracker(id = "t1", name = "T1", color = null, settings = mapOf("recent_data_window" to "all")),
        )
        val snapshots = mapOf(key to authoritative)
        assertTrue(TrackerMapHistoryUiSync.hasAuthoritativeServerTrunk(snapshots, trackers, "t1"))

        val degraded = authoritative.copy(degradedLocalOnly = true)
        assertFalse(TrackerMapHistoryUiSync.hasAuthoritativeServerTrunk(mapOf(key to degraded), trackers, "t1"))
    }
}
