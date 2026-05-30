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
    fun resolveLiveHeadCoord_usesOverlaidRuntimeHeadWhenRuntimeLeadsBus() {
        // EFFECTIVE-SNAPSHOT COHERENCE: when runtime leads the bus trail, render synthesizes a
        // runtime overlay. Camera follow must use that same effective head so marker, line, and
        // camera all move from one datum instead of splitting across raw state sources.
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

        assertEquals(41.0 to -75.0, coord)
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
    fun effectiveProject_currentSessionNearServerStart_usesRuntimeOverlayHead() {
        val trackerId = "tracker-1"
        val sessionStart = 1_779_901_252_502L
        val roundedServerStart = 1_779_901_253_000L
        val serverTail = sessionPoint(
            trackerId = trackerId,
            time = sessionStart + 100L,
            latitude = 40.5,
            longitude = -74.5,
            startTimestampMs = roundedServerStart,
        )
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = trackerId),
                selectedTrackerId = trackerId,
                lastTrackedLatitude = 41.0,
                lastTrackedLongitude = -75.0,
                lastTrackedTimestampMs = sessionStart + 200L,
                sessionStartTimeMs = sessionStart,
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = trackerId,
            trail = listOf(serverTail),
        )

        val projected = TrackerMapEffectiveSessionProjector.project(
            TrackerMapEffectiveSessionInput(
                state = state,
                plan = singlePlan(trackerId),
                trailPointLimit = 100,
                sessionWindows = TrackerMapSessionWindowState(
                    recentDataWindowByTracker = mapOf(trackerId to "current_session"),
                    currentSessionStartByTracker = mapOf(trackerId to sessionStart),
                ),
                nowMs = sessionStart + 1_000L,
            )
        )

        assertEquals(2, projected.snapshot.singleTrail.size)
        assertEquals(41.0 to -75.0, projected.liveHead)
        assertEquals(41.0, projected.snapshot.singleTrail.last().latitude, 0.0)
        assertEquals(-75.0, projected.snapshot.singleTrail.last().longitude, 0.0)
    }

    @Test
    fun effectiveProject_sessionNearServerStart_keepsPreviousAndRuntimeOverlayHead() {
        val trackerId = "tracker-1"
        val older = 1_000L
        val previous = 2_000L
        val sessionStart = 10_000L
        val roundedServerStart = 10_498L
        val olderPoint = sessionPoint(trackerId, time = 1_100L, latitude = 1.0, longitude = 1.0, startTimestampMs = older)
        val previousPoint = sessionPoint(trackerId, time = 2_100L, latitude = 2.0, longitude = 2.0, startTimestampMs = previous)
        val serverCurrentPoint = sessionPoint(
            trackerId = trackerId,
            time = 10_100L,
            latitude = 3.0,
            longitude = 3.0,
            startTimestampMs = roundedServerStart,
        )
        val state = TrackerMapUiState(
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = trackerId),
                selectedTrackerId = trackerId,
                lastTrackedLatitude = 4.0,
                lastTrackedLongitude = 4.0,
                lastTrackedTimestampMs = 10_200L,
                sessionStartTimeMs = sessionStart,
            ),
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = trackerId,
            trail = listOf(olderPoint, previousPoint, serverCurrentPoint),
        )

        val projected = TrackerMapEffectiveSessionProjector.project(
            TrackerMapEffectiveSessionInput(
                state = state,
                plan = singlePlan(trackerId),
                trailPointLimit = 100,
                sessionWindows = TrackerMapSessionWindowState(
                    recentDataWindowByTracker = mapOf(trackerId to "session"),
                    currentSessionStartByTracker = mapOf(trackerId to sessionStart),
                ),
                nowMs = 11_000L,
            )
        )

        assertEquals(listOf(previousPoint.time, serverCurrentPoint.time, 10_200L), projected.snapshot.singleTrail.map { it.time })
        assertEquals(4.0 to 4.0, projected.liveHead)
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
    fun singleTrailWithLocalRuntimeOverlay_emptyTrail_addsRuntimeHead() {
        val trail = TrackerMapViewModel.singleTrailWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "local",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
                lastTrackedTimestampMs = 1234L,
                lastAccuracyMeters = 4f,
                sessionStartTimeMs = 500L,
            ),
            displayedTrackerId = "local",
            trail = emptyList(),
        )

        assertEquals(1, trail.size)
        assertEquals("local", trail.first().trackerId)
        assertEquals(TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS_RUNTIME, trail.first().prov)
        assertEquals(500L, trail.first().startTimestampMs)
    }

    @Test
    fun singleTrailWithLocalRuntimeOverlay_priorSessionTail_addsRuntimeHeadAndKeepsSplit() {
        val priorTail = com.geovault.tracker.db.QueuedLocation(
            id = 0L,
            trackerId = "local",
            time = 1000L,
            latitude = 1.0,
            longitude = 2.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
            dist = null,
            startTimestampMs = 100L,
        )
        val trail = TrackerMapViewModel.singleTrailWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "local",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
                lastTrackedTimestampMs = 1000L,
                sessionStartTimeMs = 200L,
            ),
            displayedTrackerId = "local",
            trail = listOf(priorTail),
        )

        assertEquals(2, trail.size)
        assertEquals(100L, trail.first().startTimestampMs)
        assertEquals(200L, trail.last().startTimestampMs)
        assertEquals(20.0, trail.last().latitude, 0.0)
    }

    @Test
    fun singleTrailWithLocalRuntimeOverlay_sameSessionTailNewer_doesNotDuplicate() {
        val sessionStart = 500L
        val sameSessionTail = com.geovault.tracker.db.QueuedLocation(
            id = 0L,
            trackerId = "local",
            time = 2000L,
            latitude = 1.0,
            longitude = 2.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
            dist = null,
            startTimestampMs = sessionStart,
        )
        val original = listOf(sameSessionTail)

        val trail = TrackerMapViewModel.singleTrailWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "local",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
                lastTrackedTimestampMs = 1000L,
                sessionStartTimeMs = sessionStart,
            ),
            displayedTrackerId = "local",
            trail = original,
        )

        assertEquals(original, trail)
    }

    @Test
    fun singleTrailWithLocalRuntimeOverlay_displayedRemote_doesNotOverlayLocal() {
        val trail = TrackerMapViewModel.singleTrailWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "local",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
                lastTrackedTimestampMs = 1000L,
            ),
            displayedTrackerId = "remote",
            trail = emptyList(),
        )

        assertEquals(emptyList<com.geovault.tracker.db.QueuedLocation>(), trail)
    }

    @Test
    fun singleRenderMarker_usesOverlaidRuntimeHeadWhenBusLags() {
        val trail = TrackerMapViewModel.singleTrailWithLocalRuntimeOverlay(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "local",
                selectedTrackerName = "Local",
                lastTrackedLatitude = 20.0,
                lastTrackedLongitude = 10.0,
                lastTrackedTimestampMs = 1000L,
                sessionStartTimeMs = 500L,
            ),
            displayedTrackerId = "local",
            trail = emptyList(),
        )
        val renderState = TrackerMapStateTransforms.buildRenderState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            trail = trail,
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "local",
                selectedTrackerName = "Local",
            ),
            displayedTrackerId = "local",
        )

        assertEquals(1, renderState.points.size)
        assertEquals(20.0, renderState.points.first().latitude, 0.0)
        assertEquals(10.0, renderState.points.first().longitude, 0.0)
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
    fun displayedRosterHasServerHistory_groupModeWithPartialRoster_returnsFalse() {
        val partial = mapOf(
            "a" to listOf(serverPoint("a", time = 1L)),
            "b" to emptyList(),
        )
        val ready = TrackerMapViewModel.displayedRosterHasServerHistory(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            rosterIds = setOf("a", "b"),
            allQueueTrailsByTracker = partial,
        )
        assertEquals(false, ready)
    }

    @Test
    fun displayedRosterHasServerHistory_groupModeWithFullRoster_returnsTrue() {
        val populated = mapOf(
            "a" to listOf(serverPoint("a", time = 1L)),
            "b" to listOf(serverPoint("b", time = 2L)),
        )
        val ready = TrackerMapViewModel.displayedRosterHasServerHistory(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            rosterIds = setOf("a", "b"),
            allQueueTrailsByTracker = populated,
        )
        assertEquals(true, ready)
    }

    @Test
    fun displayedRosterHasServerHistory_groupModeAllEmpty_returnsFalse() {
        val ready = TrackerMapViewModel.displayedRosterHasServerHistory(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            rosterIds = setOf("a", "b"),
            allQueueTrailsByTracker = mapOf("a" to emptyList(), "b" to emptyList()),
        )
        assertEquals(false, ready)
    }

    @Test
    fun displayedRosterHasServerHistory_localQueueOnly_returnsFalse() {
        // Resume short-circuit: roster must include at least one PROVENANCE_SERVER_GEOMETRY point
        // per tracker. Overlay-only rows (e.g. local GPS / stream) must not count as "loaded"
        // or background resume skips the reload that restores full geometry.
        val queueOnly = mapOf(
            "a" to listOf(queuedPoint("a", time = 1L)),
            "b" to listOf(queuedPoint("b", time = 2L)),
        )
        val ready = TrackerMapViewModel.displayedRosterHasServerHistory(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            rosterIds = setOf("a", "b"),
            allQueueTrailsByTracker = queueOnly,
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

    // Filter-driven reload decisions live in TrackerMapFilterChangeReactor — see
    // TrackerMapFilterChangeReactorTest for the per-tracker change semantics that used to
    // be tested here against the now-removed shouldReloadForRecentDataWindowChange helper.

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

    private fun queuedPoint(trackerId: String, time: Long): com.geovault.tracker.db.QueuedLocation {
        return com.geovault.tracker.db.QueuedLocation(
            id = time,
            trackerId = trackerId,
            time = time,
            latitude = time.toDouble(),
            longitude = time.toDouble(),
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS,
            dist = null,
        )
    }

    private fun serverPoint(trackerId: String, time: Long): com.geovault.tracker.db.QueuedLocation {
        return com.geovault.tracker.db.QueuedLocation(
            id = time,
            trackerId = trackerId,
            time = time,
            latitude = time.toDouble(),
            longitude = time.toDouble(),
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY,
            dist = null,
        )
    }

    private fun sessionPoint(
        trackerId: String,
        time: Long,
        latitude: Double,
        longitude: Double,
        startTimestampMs: Long,
    ): com.geovault.tracker.db.QueuedLocation {
        return com.geovault.tracker.db.QueuedLocation(
            id = time,
            trackerId = trackerId,
            time = time,
            latitude = latitude,
            longitude = longitude,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_SERVER_GEOMETRY,
            dist = null,
            startTimestampMs = startTimestampMs,
        )
    }

    private fun singlePlan(trackerId: String): TrackerMapStreamingPlan {
        return TrackerMapStreamingPlan(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = trackerId,
            displayedTrackerId = trackerId,
            displayedTrackerName = "Tracker",
            resolvedGroupId = "",
            groupTrackerIds = emptySet(),
            visibleRosterTrackerIds = emptySet(),
            locallyRecordedTrackerIds = setOf(trackerId),
            remoteSubscriptionIds = emptySet(),
            acceptedRemoteTrackerIds = emptySet(),
            localOverlayTrackerIds = setOf(trackerId),
            trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.SINGLE_QUEUE),
        )
    }
}
