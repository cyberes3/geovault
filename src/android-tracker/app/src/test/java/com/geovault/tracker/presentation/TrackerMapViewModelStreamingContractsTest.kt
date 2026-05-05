package com.geovault.tracker.presentation

import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapViewModelStreamingContractsTest {

    @Test
    fun resolveStreamTargetIds_singleSession_sameAsSelected_returnsEmpty() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtimeRunning = true,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-1",
            rosterTrackerIds = emptySet()
        )
        assertEquals(emptySet<String>(), ids)
    }

    @Test
    fun resolveStreamTargetIds_singleSession_differentFromSelected_returnsDisplayedOnly() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtimeRunning = true,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-2",
            rosterTrackerIds = emptySet()
        )
        assertEquals(setOf("tracker-2"), ids)
    }

    @Test
    fun resolveStreamTargetIds_groupPlaceholder_usesGroupTrackerIds() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtimeRunning = false,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-2",
            rosterTrackerIds = setOf("tracker-2", "tracker-3"),
            groupTrackerIds = setOf("group-1", "group-2"),
        )
        assertEquals(setOf("group-1", "group-2"), ids)
    }

    @Test
    fun resolveStreamTargetIds_allQueue_whileRunning_excludesSelectedAndBlanks() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            runtimeRunning = true,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "",
            rosterTrackerIds = setOf("tracker-1", "tracker-2", " ", "tracker-3")
        )
        assertEquals(setOf("tracker-2", "tracker-3"), ids)
    }

    @Test
    fun resolveStreamTargetIds_allQueue_notRunning_keepsAllNormalized() {
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            runtimeRunning = false,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "",
            rosterTrackerIds = setOf("tracker-1", "tracker-2", " ")
        )
        assertEquals(setOf("tracker-1", "tracker-2"), ids)
    }

    @Test
    fun filterRemoteLastPointsForAcceptedIds_dropsStaleRemoteHeads() {
        val filtered = TrackerMapViewModel.filterRemoteLastPointsForAcceptedIds(
            remoteLastPoints = mapOf(
                "accepted" to remotePoint("accepted"),
                "stale" to remotePoint("stale"),
            ),
            acceptedRemoteTrackerIds = setOf("accepted"),
        )

        assertEquals(setOf("accepted"), filtered.keys)
    }

    @Test
    fun allQueueTrailsWithLocalRuntimeOverlay_groupWhileTracking_addsSelectedLocalPoint() {
        val trails = TrackerMapViewModel.allQueueTrailsWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
                lastTrackedTimestampMs = 1234L,
                lastAccuracyMeters = 4f,
            ),
            groupTrackerIds = setOf("tracker-1", "tracker-2"),
            allQueueTrailsByTracker = emptyMap(),
            nowMs = 2000L,
        )

        val selectedTrail = trails["tracker-1"].orEmpty()
        assertEquals(1, selectedTrail.size)
        assertEquals("local_gps_runtime", selectedTrail.first().prov)
        assertEquals(20.0, selectedTrail.first().latitude, 0.0)
        assertEquals(10.0, selectedTrail.first().longitude, 0.0)
    }

    @Test
    fun allQueueTrailsWithLocalRuntimeOverlay_groupWithoutSelected_doesNotAddPoint() {
        val trails = TrackerMapViewModel.allQueueTrailsWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
            ),
            groupTrackerIds = setOf("tracker-2"),
            allQueueTrailsByTracker = emptyMap(),
            nowMs = 2000L,
        )

        assertEquals(emptyMap<String, List<com.geovault.tracker.db.QueuedLocation>>(), trails)
    }

    @Test
    fun resolveHistoryClearRefreshAction_singleMode_otherTracker_noOp() {
        val action = TrackerMapViewModel.resolveHistoryClearRefreshAction(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "displayed",
            selectedTrackerId = "selected",
            clearedTrackerId = "other"
        )
        assertEquals(TrackerMapViewModel.HistoryClearRefreshAction.NO_OP, action)
    }

    @Test
    fun resolveHistoryClearRefreshAction_singleMode_displayedTracker_refreshes() {
        val action = TrackerMapViewModel.resolveHistoryClearRefreshAction(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "displayed",
            selectedTrackerId = "selected",
            clearedTrackerId = "displayed"
        )
        assertEquals(TrackerMapViewModel.HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE, action)
    }

    @Test
    fun resolveHistoryClearRefreshAction_groupMode_refreshesGroupOrAll() {
        val action = TrackerMapViewModel.resolveHistoryClearRefreshAction(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            displayedTrackerId = "",
            selectedTrackerId = "selected",
            clearedTrackerId = "any"
        )
        assertEquals(TrackerMapViewModel.HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL, action)
    }

    @Test
    fun shouldReloadForRecentDataWindowChange_selectedSingleNotStreaming_returnsTrue() {
        val shouldReload = TrackerMapViewModel.shouldReloadForRecentDataWindowChange(
            oldWindow = "1h",
            newWindow = "session",
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-1",
            runtimeRunning = false,
            activeStreamedTrackerIds = emptySet(),
            changedTrackerId = "tracker-1"
        )
        assertEquals(true, shouldReload)
    }

    @Test
    fun shouldReloadForRecentDataWindowChange_nonSelectedTracker_returnsFalse() {
        val shouldReload = TrackerMapViewModel.shouldReloadForRecentDataWindowChange(
            oldWindow = "1h",
            newWindow = "session",
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-2",
            runtimeRunning = false,
            activeStreamedTrackerIds = emptySet(),
            changedTrackerId = "tracker-2"
        )
        assertEquals(false, shouldReload)
    }

    @Test
    fun shouldReloadForRecentDataWindowChange_selectedWhileRunningButNotStreaming_returnsTrue() {
        val shouldReload = TrackerMapViewModel.shouldReloadForRecentDataWindowChange(
            oldWindow = "1h",
            newWindow = "session",
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "tracker-1",
            runtimeRunning = true,
            activeStreamedTrackerIds = emptySet(),
            changedTrackerId = "tracker-1"
        )
        assertEquals(true, shouldReload)
    }

    @Test
    fun resolveBottomCardVisibilityForMarkerTap_withSelection_showsCard() {
        val visible = TrackerMapViewModel.resolveBottomCardVisibilityForMarkerTap(
            hasSelectionCard = true
        )
        assertEquals(true, visible)
    }

    @Test
    fun resolveBackgroundTapShouldCloseBottomCard_hiddenAndNoSelection_noClose() {
        val shouldClose = TrackerMapViewModel.resolveBackgroundTapShouldCloseBottomCard(
            isBottomCardVisible = false,
            hasSelectionCard = false
        )
        assertEquals(false, shouldClose)
    }

    @Test
    fun resolveBackgroundTapShouldCloseBottomCard_visible_closes() {
        val shouldClose = TrackerMapViewModel.resolveBackgroundTapShouldCloseBottomCard(
            isBottomCardVisible = true,
            hasSelectionCard = true
        )
        assertEquals(true, shouldClose)
    }

    @Test
    fun resolveRenderSelectedMapTrackerId_hiddenCard_dropsSelectionHighlight() {
        val selectedId = TrackerMapViewModel.resolveRenderSelectedMapTrackerId(
            isBottomCardVisible = false,
            selectedMapTrackerId = "tracker-1"
        )
        assertEquals(null, selectedId)
    }

    @Test
    fun resolveRenderSelectedMapTrackerId_visibleCard_keepsSelectionHighlight() {
        val selectedId = TrackerMapViewModel.resolveRenderSelectedMapTrackerId(
            isBottomCardVisible = true,
            selectedMapTrackerId = "tracker-1"
        )
        assertEquals("tracker-1", selectedId)
    }

    @Test
    fun resolveFocusActionVisible_singleSession_hidesFocusAction() {
        val visible = TrackerMapViewModel.resolveFocusActionVisible(TrackerMapDisplayMode.SINGLE_SESSION)
        assertEquals(false, visible)
    }

    @Test
    fun resolveFocusActionVisible_allAndGroup_showFocusAction() {
        val allVisible = TrackerMapViewModel.resolveFocusActionVisible(TrackerMapDisplayMode.ALL_QUEUE)
        val groupVisible = TrackerMapViewModel.resolveFocusActionVisible(TrackerMapDisplayMode.GROUP_PLACEHOLDER)
        assertEquals(true, allVisible)
        assertEquals(true, groupVisible)
    }

    @Test
    fun resolveAllowedFallbackTrackerIds_singleSession_prefersDisplayedTrackerWhenVisible() {
        val allowed = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtimeRunning = false,
                selectedTrackerId = "tracker-1",
                displayedTrackerId = "tracker-2",
                visibleTrackerIds = setOf("tracker-1", "tracker-2")
            )
        )
        assertEquals(setOf("tracker-2"), allowed)
    }

    @Test
    fun resolveAllowedFallbackTrackerIds_allQueue_allVisibleTrackersAllowed() {
        val allowed = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                runtimeRunning = false,
                selectedTrackerId = "tracker-2",
                displayedTrackerId = "",
                visibleTrackerIds = setOf("tracker-1", "tracker-2", "tracker-3")
            )
        )
        assertEquals(setOf("tracker-1", "tracker-2", "tracker-3"), allowed)
    }

    @Test
    fun resolveAllowedFallbackTrackerIds_groupMode_allVisibleTrackersAllowed() {
        val allowed = TrackerAccuracyFallbackPolicy.resolveAllowedFallbackTrackerIds(
            TrackerAccuracyFallbackPolicyInput(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                runtimeRunning = true,
                selectedTrackerId = "tracker-2",
                displayedTrackerId = "",
                visibleTrackerIds = setOf("tracker-1")
            )
        )
        assertEquals(setOf("tracker-1"), allowed)
    }

    private fun remotePoint(trackerId: String): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = trackerId,
            lon = 2.0,
            lat = 1.0,
            timestampMs = 1_000L,
        )
    }
}
