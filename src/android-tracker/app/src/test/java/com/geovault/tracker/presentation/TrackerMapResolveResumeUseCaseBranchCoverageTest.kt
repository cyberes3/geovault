package com.geovault.tracker.presentation

import com.geovault.tracker.map.MapSessionEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapResolveResumeUseCaseBranchCoverageTest {

    @Test
    fun resolve_mapNotReady_returnsNoOp() {
        val decision = MapSessionEngine.resolveResume(baseInput(mapReady = false))
        assertEquals(TrackerMapResumeDecision.NoOp, decision)
    }

    @Test
    fun resolve_trackingGroup_prefersActiveStreamsIncludingSelected() {
        // GROUP STREAMING: while locally tracking and viewing a group, the active streamed set
        // (which already excludes the locally-recorded tracker upstream) is taken verbatim. The
        // selected tracker is NOT scrubbed out at the resume layer because group mode is an
        // explicit multi-tracker subscription that may legitimately include it.
        val decision = MapSessionEngine.resolveResume(
            baseInput(
                trackingRunning = true,
                mapViewContext = TrackerMapViewContext.GROUP,
                activeStreamedTrackerIds = setOf("selected", "x", "y"),
                currentGroupTrackIds = setOf("g1"),
                selectedTrackerId = "selected"
            )
        )
        assertEquals(TrackerMapResumeDecision.StartMultiContextStreaming(setOf("selected", "x", "y")), decision)
    }

    @Test
    fun resolve_trackingGroup_usesFallbackGroupIdsIncludingSelected() {
        // GROUP STREAMING: same rationale — the fallback group set keeps the selected tracker.
        val decision = MapSessionEngine.resolveResume(
            baseInput(
                trackingRunning = true,
                mapViewContext = TrackerMapViewContext.GROUP,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = setOf("selected", "g1", "g2"),
                selectedTrackerId = "selected"
            )
        )
        assertEquals(TrackerMapResumeDecision.StartMultiContextStreaming(setOf("selected", "g1", "g2")), decision)
    }

    @Test
    fun resolve_trackingSingle_noIdsAndNoPending_clearsState() {
        val decision = MapSessionEngine.resolveResume(
            baseInput(
                trackingRunning = true,
                selectedTrackerId = "",
                displayedTrackerId = "",
                hasPendingInitialTracker = false
            )
        )
        assertEquals(TrackerMapResumeDecision.ClearSingleTrackerState, decision)
    }

    @Test
    fun resolve_trackingSingle_displayedInOtherStream_restartsDisplayedStreaming() {
        val decision = MapSessionEngine.resolveResume(
            baseInput(
                trackingRunning = true,
                selectedTrackerId = "selected",
                displayedTrackerId = "other",
                activeStreamedTrackerIds = setOf("other"),
                hasTrailPoints = false
            )
        )
        assertEquals(TrackerMapResumeDecision.RestartDisplayedTrackerStreaming, decision)
    }

    @Test
    fun resolve_nonTrackingGroup_withoutStreams_returnsNoStreaming() {
        val decision = MapSessionEngine.resolveResume(
            baseInput(
                trackingRunning = false,
                mapViewContext = TrackerMapViewContext.GROUP,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet()
            )
        )
        assertEquals(TrackerMapResumeDecision.MultiContextNoStreaming, decision)
    }

    @Test
    fun resolve_initialNonTrackingSingleNoTrail_usesBootstrapWhenAlreadyStreamed() {
        val decision = MapSessionEngine.resolveResume(
            baseInput(
                trackingRunning = false,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = setOf("selected"),
                hasTrailPoints = false,
                hasPendingInitialTracker = true,
                backgroundedDurationMs = 0L
            )
        )
        assertEquals(TrackerMapResumeDecision.LoadSingleTrackerBootstrap("selected"), decision)
    }

    @Test
    fun resolve_initialNonTrackingSingleWithNoTrail_loadsRuntime() {
        val decision = MapSessionEngine.resolveResume(
            baseInput(
                trackingRunning = false,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                hasTrailPoints = false,
                activeStreamedTrackerIds = emptySet(),
                hasPendingInitialTracker = true,
            )
        )
        assertEquals(TrackerMapResumeDecision.LoadSingleTrackerRuntime("selected"), decision)
    }

    @Test
    fun resolve_resumeNonTrackingSingleWithNoTrail_doesNotLoadHistory() {
        val decision = MapSessionEngine.resolveResume(
            baseInput(
                trackingRunning = false,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                hasTrailPoints = false,
                activeStreamedTrackerIds = emptySet(),
                hasPendingInitialTracker = false,
            )
        )
        assertEquals(TrackerMapResumeDecision.RestartDisplayedTrackerStreaming, decision)
    }

    private fun baseInput(
        trackingRunning: Boolean = false,
        mapReady: Boolean = true,
        showAllTrackers: Boolean = false,
        mapViewContext: TrackerMapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
        activeStreamedTrackerIds: Set<String> = emptySet(),
        currentGroupTrackIds: Set<String> = emptySet(),
        selectedTrackerId: String = "selected",
        displayedTrackerId: String = "selected",
        hasTrailPoints: Boolean = true,
        hasPendingInitialTracker: Boolean = false,
        backgroundedDurationMs: Long = 1_000L
    ): TrackerMapResumeInput {
        return TrackerMapResumeInput(
            trackingRunning = trackingRunning,
            mapReady = mapReady,
            showAllTrackers = showAllTrackers,
            mapViewContext = mapViewContext,
            activeStreamedTrackerIds = activeStreamedTrackerIds,
            currentGroupTrackIds = currentGroupTrackIds,
            selectedTrackerId = selectedTrackerId,
            displayedTrackerId = displayedTrackerId,
            hasTrailPoints = hasTrailPoints,
            hasPendingInitialTracker = hasPendingInitialTracker,
            backgroundedDurationMs = backgroundedDurationMs
        )
    }
}
