package com.geovault.tracker.presentation

import com.geovault.tracker.map.MapTrailEngine
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCatalogSettings
import com.geovault.tracker.TrackerGeometryStatus
import com.geovault.tracker.history.TrackerHistoryKey
import com.geovault.tracker.history.TrackerHistoryPoint
import com.geovault.tracker.history.TrackerHistoryProvenance
import com.geovault.tracker.history.TrackerHistorySnapshot
import com.geovault.tracker.history.TrackerHistoryWindow
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val ids = MapTrailEngine.historyTrackerIdsForRender(
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
            Tracker(id = "t1", name = "T1", color = null, settings = TrackerCatalogSettings(recentDataWindow = "all")),
        )
        val snapshots = mapOf(key to authoritative)
        assertTrue(MapTrailEngine.hasAuthoritativeServerTrunk(snapshots, trackers, "t1"))

        val degraded = authoritative.copy(degradedLocalOnly = true)
        assertFalse(MapTrailEngine.hasAuthoritativeServerTrunk(mapOf(key to degraded), trackers, "t1"))
    }

    @Test
    fun shouldSkipClientRenderWindowFilter_neverSkipsSessionWindows() {
        val snapshot = completeSnapshot(TrackerHistoryWindow.KEY_CURRENT_SESSION)
        val tracker = trackerWithWindow(
            recentDataWindow = TrackerHistoryWindow.KEY_CURRENT_SESSION,
            statusWindow = TrackerHistoryWindow.KEY_ALL,
        )
        assertFalse(MapTrailEngine.shouldSkipClientRenderWindowFilter(snapshot, tracker))

        val sessionSnapshot = completeSnapshot(TrackerHistoryWindow.KEY_SESSION)
        val sessionTracker = trackerWithWindow(
            recentDataWindow = TrackerHistoryWindow.KEY_SESSION,
            statusWindow = TrackerHistoryWindow.KEY_ALL,
        )
        assertFalse(MapTrailEngine.shouldSkipClientRenderWindowFilter(sessionSnapshot, sessionTracker))
    }

    @Test
    fun shouldSkipClientRenderWindowFilter_skipsOnlyWhenStatusWindowMatchesSettings() {
        val snapshot = completeSnapshot(TrackerHistoryWindow.KEY_ALL)
        val matching = trackerWithWindow(
            recentDataWindow = TrackerHistoryWindow.KEY_ALL,
            statusWindow = TrackerHistoryWindow.KEY_ALL,
        )
        assertTrue(MapTrailEngine.shouldSkipClientRenderWindowFilter(snapshot, matching))

        val mismatched = trackerWithWindow(
            recentDataWindow = TrackerHistoryWindow.KEY_ALL,
            statusWindow = TrackerHistoryWindow.KEY_CURRENT_SESSION,
        )
        assertFalse(MapTrailEngine.shouldSkipClientRenderWindowFilter(snapshot, mismatched))

        val noStatus = Tracker(id = "t1", name = "T1", color = null, settings = TrackerCatalogSettings(recentDataWindow = "all"))
        assertTrue(MapTrailEngine.shouldSkipClientRenderWindowFilter(snapshot, noStatus))
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
        assertFalse(MapTrailEngine.shouldSkipClientRenderWindowFilter(incomplete, tracker))

        val degraded = incomplete.copy(complete = true, degradedLocalOnly = true)
        assertFalse(MapTrailEngine.shouldSkipClientRenderWindowFilter(degraded, tracker))
    }

    @Test
    fun activeSessionStartMsForTracker_isNullForSharedIdWhileRecordingAnother() {
        val runtime = TrackingRuntimeSnapshot(
            isRunning = true,
            recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "self"),
            selectedTrackerId = "self",
            sessionStartTimeMs = 9_000L,
        )
        assertEquals(
            9_000L,
            MapTrailEngine.activeSessionStartMsForTracker(runtime, "self"),
        )
        assertNull(MapTrailEngine.activeSessionStartMsForTracker(runtime, "shared"))
    }

    @Test
    fun trailsFromSnapshots_keepsNewerUnpublishedOverlayWhenSnapshotIsOlder() {
        val window = TrackerHistoryWindow(TrackerHistoryWindow.KEY_ALL)
        val key = TrackerHistoryKey("t1", window)
        val trunkPoint = TrackerHistoryPoint(
            trackerId = "t1",
            timestampMs = 1_000L,
            latitude = 1.0,
            longitude = 2.0,
            provenance = TrackerHistoryProvenance.SERVER_GEOMETRY,
        )
        val snapshot = TrackerHistorySnapshot(
            key = key,
            trunk = listOf(trunkPoint),
            overlay = emptyList(),
            points = listOf(trunkPoint),
            committedAtMs = 1L,
            generation = 1L,
            complete = true,
            degradedLocalOnly = false,
        )
        val overlay = listOf(
            QueuedLocation(
                trackerId = "t1",
                time = 4_000L,
                latitude = 5.0,
                longitude = 6.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
        )
        val plan = TrackerMapStreamingPlan(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "other",
            displayedTrackerId = "t1",
            displayedTrackerName = "T1",
            resolvedGroupId = "",
            groupTrackerIds = emptySet(),
            visibleRosterTrackerIds = setOf("t1"),
            locallyRecordedTrackerIds = emptySet(),
            remoteSubscriptionIds = emptySet(),
            acceptedRemoteTrackerIds = setOf("t1"),
            localOverlayTrackerIds = emptySet(),
            trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.SINGLE_SERVER),
        )
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "t1",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "other"),
        )
        val trails = MapTrailEngine.trailsFromSnapshots(
            state = state,
            plan = plan,
            snapshots = mapOf(key to snapshot),
            trackers = listOf(Tracker(id = "t1", name = "T1", color = null)),
            trailPointLimit = 10_000,
            unpublishedOverlaysByTracker = mapOf("t1" to overlay),
        )
        assertEquals(2, trails.trail.size)
        assertEquals(5.0, trails.trail.last().latitude, 0.0)
        assertEquals(6.0, trails.trail.last().longitude, 0.0)
        assertEquals(4_000L, trails.trail.last().time)
    }

    @Test
    fun trailsFromSnapshots_exposesDegradedTrackerIds() {
        val window = TrackerHistoryWindow(TrackerHistoryWindow.KEY_ALL)
        val key = TrackerHistoryKey("t1", window)
        val trunkPoint = trunkPoint()
        val snapshot = TrackerHistorySnapshot(
            key = key,
            trunk = listOf(trunkPoint),
            overlay = emptyList(),
            points = listOf(trunkPoint),
            committedAtMs = 1L,
            generation = 1L,
            complete = true,
            degradedLocalOnly = true,
        )
        val plan = TrackerMapStreamingPlan(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "t1",
            displayedTrackerId = "t1",
            displayedTrackerName = "T1",
            resolvedGroupId = "",
            groupTrackerIds = emptySet(),
            visibleRosterTrackerIds = setOf("t1"),
            locallyRecordedTrackerIds = emptySet(),
            remoteSubscriptionIds = emptySet(),
            acceptedRemoteTrackerIds = setOf("t1"),
            localOverlayTrackerIds = emptySet(),
            trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.SINGLE_SERVER),
        )
        val trails = MapTrailEngine.trailsFromSnapshots(
            state = TrackerMapUiState(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "t1",
                runtime = TrackingRuntimeSnapshot(selectedTrackerId = "t1"),
            ),
            plan = plan,
            snapshots = mapOf(key to snapshot),
            trackers = listOf(Tracker(id = "t1", name = "T1", color = null)),
            trailPointLimit = 10_000,
        )
        assertEquals(setOf("t1"), trails.degradedTrackerIds)
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
            settings = TrackerCatalogSettings(recentDataWindow = recentDataWindow),
            geometry_status = TrackerGeometryStatus(window = statusWindow),
        )
    }
}
