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
    fun resolveStreamTargetIds_allQueue_notRunning_includesSelected() {
        // STREAMING EXCLUSION: when the user is NOT recording, the selected tracker is just
        // another roster member and should be streamed alongside the rest. Only `locallyRecorded`
        // is excluded, and that is empty when not recording.
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
    fun resolveStreamTargetIds_groupPlaceholder_notRunning_includesSelected() {
        // STREAMING EXCLUSION: same rationale as above for group mode.
        val ids = TrackerMapViewModel.resolveStreamTargetIds(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtimeRunning = false,
            selectedTrackerId = "tracker-1",
            displayedTrackerId = "",
            rosterTrackerIds = emptySet(),
            groupTrackerIds = setOf("tracker-1", "tracker-2"),
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
    fun resolveLiveHeadCoord_prefersTrailTailFromActiveSession_overFresherRuntime() {
        // CHEVRON-COHERENCE (Bug 1 root cause): the runtime store collector publishes a new
        // `runtime.lastTracked*` BEFORE the bus-reducer appends the same fix to `state.trail`.
        // If the camera reads runtime first, it leads the marker by one fix — the user sees
        // the world move and the chevron stay put. When the trail tail belongs to the active
        // recording session it is the authoritative live head and must win, even if the
        // runtime carries a (briefly) newer timestamp.
        val sessionStart = 1_000L
        val tail = com.geovault.tracker.db.QueuedLocation(
            id = 0L,
            trackerId = "tracker-1",
            time = 5_000L,
            latitude = 40.5,
            longitude = -74.5,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
            dist = null,
            startTimestampMs = sessionStart,
        )
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
                lastTrackedLatitude = 41.0,
                lastTrackedLongitude = -75.0,
                lastTrackedTimestampMs = 6_000L,
                sessionStartTimeMs = sessionStart,
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(tail),
        )

        val coord = TrackerMapViewModel.resolveLiveHeadCoord(state)

        assertEquals(40.5 to -74.5, coord)
    }

    @Test
    fun resolveLiveHeadCoord_usesRuntimeWhenTrailFromPriorSession() {
        // PRIOR-SESSION FALLBACK: just after starting a new session, `state.trail` may still
        // hold the previous session's tail until the reload completes. The new runtime fix
        // is from the current session and is the only correct camera target — the helper
        // must NOT pin the camera to the prior session's tail.
        val priorSession = 1_000L
        val activeSession = 2_000L
        val priorTail = com.geovault.tracker.db.QueuedLocation(
            id = 0L,
            trackerId = "tracker-1",
            time = 5_000L,
            latitude = 40.5,
            longitude = -74.5,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
            dist = null,
            startTimestampMs = priorSession,
        )
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
                lastTrackedLatitude = 41.0,
                lastTrackedLongitude = -75.0,
                lastTrackedTimestampMs = 6_000L,
                sessionStartTimeMs = activeSession,
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = listOf(priorTail),
        )

        val coord = TrackerMapViewModel.resolveLiveHeadCoord(state)

        assertEquals(41.0 to -75.0, coord)
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
        )

        assertEquals(emptyMap<String, List<com.geovault.tracker.db.QueuedLocation>>(), trails)
    }

    @Test
    fun allQueueTrailsWithLocalRuntimeOverlay_rejectsRuntimePointWhenSameSessionTailIsFresher() {
        // CHEVRON-COHERENCE (Bug 1): when the bus reducer has already appended a same-session
        // fix at >= runtime.lastTrackedTimestampMs, the trail tail is the authoritative live
        // point. The runtime overlay must not synthesize on top, otherwise the multi-trail
        // head paints a phantom point ahead of (or behind) the bus-driven marker.
        val sessionStart = 500L
        val newerFix = com.geovault.tracker.db.QueuedLocation(
            id = 0L,
            trackerId = "tracker-1",
            time = 5000L,
            latitude = 40.0,
            longitude = 30.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
            dist = null,
            startTimestampMs = sessionStart,
        )
        val initial = mapOf("tracker-1" to listOf(newerFix))

        val trails = TrackerMapViewModel.allQueueTrailsWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker-1"),
                selectedTrackerId = "tracker-1",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
                lastTrackedTimestampMs = 1000L,
                lastAccuracyMeters = 4f,
                sessionStartTimeMs = sessionStart,
            ),
            groupTrackerIds = setOf("tracker-1", "tracker-2"),
            allQueueTrailsByTracker = initial,
        )

        assertEquals(initial, trails)
    }

    @Test
    fun allQueueTrailsWithLocalRuntimeOverlay_usesRecordingTrackerWhenSelectedDiffers() {
        val trails = TrackerMapViewModel.allQueueTrailsWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "selected",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
                lastTrackedTimestampMs = 1234L,
            ),
            groupTrackerIds = setOf("local", "remote"),
            allQueueTrailsByTracker = emptyMap(),
        )

        assertEquals(setOf("local"), trails.keys)
    }

    @Test
    fun streamingActiveTargetsMatchDisplayed_groupModeWithMatchingStream_returnsTrue() {
        // STREAMING-RESUME SHORT-CIRCUIT (Bug 3): when the WS is already subscribed to exactly
        // the group's non-locally-recorded members, evaluateResumeAfterBackground should treat
        // resume as a no-op rather than triggering a redundant reload+reconcile pass.
        val match = TrackerMapViewModel.streamingActiveTargetsMatchDisplayed(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            displayedIds = setOf("a", "b", "self"),
            localRecordingActive = true,
            locallyRecordedTrackerId = "self",
            activeStreamTargets = setOf("a", "b"),
        )
        assertEquals(true, match)
    }

    @Test
    fun streamingActiveTargetsMatchDisplayed_groupModeWithMissingMember_returnsFalse() {
        val match = TrackerMapViewModel.streamingActiveTargetsMatchDisplayed(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            displayedIds = setOf("a", "b"),
            localRecordingActive = false,
            locallyRecordedTrackerId = "",
            activeStreamTargets = setOf("a"),
        )
        assertEquals(false, match)
    }

    @Test
    fun streamingActiveTargetsMatchDisplayed_singleSession_returnsFalse() {
        val match = TrackerMapViewModel.streamingActiveTargetsMatchDisplayed(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedIds = setOf("a"),
            localRecordingActive = false,
            locallyRecordedTrackerId = "",
            activeStreamTargets = setOf("a"),
        )
        assertEquals(false, match)
    }

    @Test
    fun displayedRosterHasLoadedTrails_groupModeWithPopulatedMember_returnsTrue() {
        val populated = mapOf(
            "a" to listOf(
                com.geovault.tracker.db.QueuedLocation(
                    id = 0L,
                    trackerId = "a",
                    time = 1L,
                    latitude = 0.0,
                    longitude = 0.0,
                    altitude = null,
                    speed = null,
                    bearing = null,
                    accuracy = null,
                    sat = null,
                    prov = TrackerMapPointProvenancePolicy.PROVENANCE_REMOTE_STREAM,
                    dist = null,
                )
            ),
            "b" to emptyList(),
        )
        val ready = TrackerMapViewModel.displayedRosterHasLoadedTrails(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            rosterIds = setOf("a", "b"),
            allQueueTrailsByTracker = populated,
        )
        assertEquals(true, ready)
    }

    @Test
    fun displayedRosterHasLoadedTrails_groupModeAllEmpty_returnsFalse() {
        val ready = TrackerMapViewModel.displayedRosterHasLoadedTrails(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            rosterIds = setOf("a", "b"),
            allQueueTrailsByTracker = mapOf("a" to emptyList(), "b" to emptyList()),
        )
        assertEquals(false, ready)
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
