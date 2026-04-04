package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapResolveResumeUseCaseBranchCoverageTest {

    private val resolver = TrackerMapResolveResumeUseCase()

    @Test
    fun resolve_mapNotReady_returnsNoOp() {
        val decision = resolver.resolve(baseInput(mapReady = false))
        assertEquals(TrackerMapResumeDecision.NoOp, decision)
    }

    @Test
    fun resolve_trackingGroup_prefersActiveStreamsWithoutSelected() {
        val decision = resolver.resolve(
            baseInput(
                trackingRunning = true,
                mapViewContext = TrackerMapViewContext.GROUP,
                activeStreamedTrackerIds = setOf("selected", "x", "y"),
                currentGroupTrackIds = setOf("g1"),
                selectedTrackerId = "selected"
            )
        )
        assertEquals(TrackerMapResumeDecision.StartMultiContextStreaming(setOf("x", "y")), decision)
    }

    @Test
    fun resolve_trackingGroup_usesFallbackGroupIdsWhenNoActiveStreams() {
        val decision = resolver.resolve(
            baseInput(
                trackingRunning = true,
                mapViewContext = TrackerMapViewContext.GROUP,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = setOf("selected", "g1", "g2"),
                selectedTrackerId = "selected"
            )
        )
        assertEquals(TrackerMapResumeDecision.StartMultiContextStreaming(setOf("g1", "g2")), decision)
    }

    @Test
    fun resolve_trackingSingle_noIdsAndNoPending_clearsState() {
        val decision = resolver.resolve(
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
        val decision = resolver.resolve(
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
        val decision = resolver.resolve(
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
    fun resolve_nonTrackingSingle_noTrail_usesBootstrapWhenAlreadyStreamed() {
        val decision = resolver.resolve(
            baseInput(
                trackingRunning = false,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = setOf("selected"),
                hasTrailPoints = false,
                backgroundedDurationMs = 0L
            )
        )
        assertEquals(TrackerMapResumeDecision.LoadSingleTrackerBootstrap("selected"), decision)
    }

    @Test
    fun resolve_nonTrackingSingle_withNoTrail_loadsRuntime() {
        val decision = resolver.resolve(
            baseInput(
                trackingRunning = false,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                hasTrailPoints = false,
                activeStreamedTrackerIds = emptySet()
            )
        )
        assertEquals(TrackerMapResumeDecision.LoadSingleTrackerRuntime("selected"), decision)
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
