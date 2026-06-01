package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerGeometryStatus
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

    @Test
    fun shouldSkipClientRenderWindowFilter_neverSkipsSessionWindows() {
        val snapshot = completeSnapshot(TrackerHistoryWindow.KEY_CURRENT_SESSION)
        val tracker = trackerWithWindow(
            recentDataWindow = TrackerHistoryWindow.KEY_CURRENT_SESSION,
            statusWindow = TrackerHistoryWindow.KEY_ALL,
        )
        assertFalse(TrackerMapHistoryUiSync.shouldSkipClientRenderWindowFilter(snapshot, tracker))

        val sessionSnapshot = completeSnapshot(TrackerHistoryWindow.KEY_SESSION)
        val sessionTracker = trackerWithWindow(
            recentDataWindow = TrackerHistoryWindow.KEY_SESSION,
            statusWindow = TrackerHistoryWindow.KEY_ALL,
        )
        assertFalse(TrackerMapHistoryUiSync.shouldSkipClientRenderWindowFilter(sessionSnapshot, sessionTracker))
    }

    @Test
    fun shouldSkipClientRenderWindowFilter_skipsOnlyWhenStatusWindowMatchesSettings() {
        val snapshot = completeSnapshot(TrackerHistoryWindow.KEY_ALL)
        val matching = trackerWithWindow(
            recentDataWindow = TrackerHistoryWindow.KEY_ALL,
            statusWindow = TrackerHistoryWindow.KEY_ALL,
        )
        assertTrue(TrackerMapHistoryUiSync.shouldSkipClientRenderWindowFilter(snapshot, matching))

        val mismatched = trackerWithWindow(
            recentDataWindow = TrackerHistoryWindow.KEY_ALL,
            statusWindow = TrackerHistoryWindow.KEY_CURRENT_SESSION,
        )
        assertFalse(TrackerMapHistoryUiSync.shouldSkipClientRenderWindowFilter(snapshot, mismatched))

        val noStatus = Tracker(id = "t1", name = "T1", color = null, settings = mapOf("recent_data_window" to "all"))
        assertTrue(TrackerMapHistoryUiSync.shouldSkipClientRenderWindowFilter(snapshot, noStatus))
    }

    @Test
    fun shouldSkipClientRenderWindowFilter_doesNotSkipIncompleteOrDegraded() {
        val key = TrackerHistoryKey("t1", TrackerHistoryWindow(TrackerHistoryWindow.KEY_ALL))
        val point = trunkPoint()
        val incomplete = TrackerHistorySnapshot(
            key = key,
            trunk = listOf(point),
            overlay = emptyList(),
            points = listOf(point),
            committedAtMs = 1L,
            generation = 1L,
            complete = false,
            degradedLocalOnly = false,
        )
        val tracker = trackerWithWindow(TrackerHistoryWindow.KEY_ALL, TrackerHistoryWindow.KEY_ALL)
        assertFalse(TrackerMapHistoryUiSync.shouldSkipClientRenderWindowFilter(incomplete, tracker))

        val degraded = incomplete.copy(complete = true, degradedLocalOnly = true)
        assertFalse(TrackerMapHistoryUiSync.shouldSkipClientRenderWindowFilter(degraded, tracker))
    }

    private fun completeSnapshot(windowKey: String): TrackerHistorySnapshot {
        val window = TrackerHistoryWindow(windowKey)
        val key = TrackerHistoryKey("t1", window)
        val point = trunkPoint()
        return TrackerHistorySnapshot(
            key = key,
            trunk = listOf(point),
            overlay = emptyList(),
            points = listOf(point),
            committedAtMs = 1L,
            generation = 1L,
            complete = true,
            degradedLocalOnly = false,
        )
    }

    private fun trunkPoint() = TrackerHistoryPoint(
        trackerId = "t1",
        timestampMs = 1L,
        latitude = 1.0,
        longitude = 2.0,
        provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
    )

    private fun trackerWithWindow(
        recentDataWindow: String,
        statusWindow: String,
    ): Tracker {
        return Tracker(
            id = "t1",
            name = "T1",
            color = null,
            settings = mapOf("recent_data_window" to recentDataWindow),
            geometry_status = TrackerGeometryStatus(window = statusWindow),
        )
    }
}
