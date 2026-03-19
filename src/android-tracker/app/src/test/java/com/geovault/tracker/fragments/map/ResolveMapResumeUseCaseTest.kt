package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveMapResumeUseCaseTest {
    private val useCase = ResolveMapResumeUseCase()

    @Test
    fun resolve_returnsNoOpWhenMapNotReady() {
        val decision = useCase.resolve(
            baseInput().copy(mapReady = false)
        )
        assertTrue(decision is MapResumeDecision.NoOp)
    }

    @Test
    fun resolve_startsMultiStreamingFromActiveIds() {
        val decision = useCase.resolve(
            baseInput().copy(
                showAllTrackers = true,
                activeStreamedTrackerIds = setOf("a", "b")
            )
        )
        assertEquals(
            MapResumeDecision.StartMultiContextStreaming(setOf("a", "b")),
            decision
        )
    }

    @Test
    fun resolve_loadsSingleTrackerWhenTrackingRunningWithActiveId() {
        val decision = useCase.resolve(
            baseInput().copy(
                trackingRunning = true,
                selectedTrackerId = "tracker-1",
                displayedTrackerId = null
            )
        )
        assertEquals(MapResumeDecision.LoadSingleTrackerRuntime("tracker-1"), decision)
    }

    @Test
    fun resolve_noOpWhenTrackingRunningAndDisplayedHasPoints() {
        val decision = useCase.resolve(
            baseInput().copy(
                trackingRunning = true,
                selectedTrackerId = "tracker-1",
                displayedTrackerId = "tracker-1",
                hasTrackPoints = true
            )
        )
        assertTrue(decision is MapResumeDecision.NoOp)
    }

    @Test
    fun resolve_noOpWhenTrackingRunningWithoutTrackerButPendingInitial() {
        val decision = useCase.resolve(
            baseInput().copy(
                trackingRunning = true,
                selectedTrackerId = "",
                displayedTrackerId = null,
                hasPendingInitialTracker = true
            )
        )
        assertTrue(decision is MapResumeDecision.NoOp)
    }

    @Test
    fun resolve_clearsSingleStateWhenNoTrackerAndNoPendingInitial() {
        val decision = useCase.resolve(
            baseInput().copy(
                selectedTrackerId = "",
                displayedTrackerId = null,
                hasPendingInitialTracker = false
            )
        )
        assertTrue(decision is MapResumeDecision.ClearSingleTrackerState)
    }

    @Test
    fun resolve_groupStartsStreamingFromGroupIdsWhenNoActiveStreams() {
        val decision = useCase.resolve(
            baseInput().copy(
                mapViewContext = MapViewContext.GROUP,
                currentGroupTrackIds = setOf("g1", "g2"),
                activeStreamedTrackerIds = emptySet()
            )
        )
        assertEquals(
            MapResumeDecision.StartMultiContextStreaming(setOf("g1", "g2")),
            decision
        )
    }

    @Test
    fun resolve_allTrackersWithNoStreams_returnsMultiContextNoStreaming() {
        val decision = useCase.resolve(
            baseInput().copy(
                showAllTrackers = true,
                activeStreamedTrackerIds = emptySet()
            )
        )
        assertTrue(decision is MapResumeDecision.MultiContextNoStreaming)
    }

    @Test
    fun resolve_restartsDisplayedStreamingWhenSingleHasTrackPoints() {
        val decision = useCase.resolve(
            baseInput().copy(
                selectedTrackerId = "tracker-1",
                displayedTrackerId = "tracker-1",
                hasTrackPoints = true,
                backgroundedDurationMs = 1_000L
            )
        )
        assertTrue(decision is MapResumeDecision.RestartDisplayedTrackerStreaming)
    }

    @Test
    fun resolve_backfillBoundaryAt15Seconds_loadsRuntime() {
        val decision = useCase.resolve(
            baseInput().copy(
                selectedTrackerId = "tracker-1",
                displayedTrackerId = "tracker-1",
                hasTrackPoints = true,
                backgroundedDurationMs = 15_000L
            )
        )
        assertEquals(MapResumeDecision.LoadSingleTrackerRuntime("tracker-1"), decision)
    }

    @Test
    fun resolve_backfillsGeometryWhenSingleHasTrackPointsAfterLongBackground() {
        val decision = useCase.resolve(
            baseInput().copy(
                selectedTrackerId = "tracker-1",
                displayedTrackerId = "tracker-1",
                hasTrackPoints = true,
                backgroundedDurationMs = 20_000L
            )
        )
        assertEquals(MapResumeDecision.LoadSingleTrackerRuntime("tracker-1"), decision)
    }

    @Test
    fun resolve_loadsRuntimeWhenNoPointsAndNotInActiveStreamSet() {
        val decision = useCase.resolve(
            baseInput().copy(
                selectedTrackerId = "tracker-1",
                displayedTrackerId = "tracker-1",
                hasTrackPoints = false,
                activeStreamedTrackerIds = setOf("other-tracker")
            )
        )
        assertEquals(
            MapResumeDecision.LoadSingleTrackerRuntime(trackerId = "tracker-1"),
            decision
        )
    }

    @Test
    fun resolve_loadsBootstrapWhenStreamingNeedsBootstrap() {
        val decision = useCase.resolve(
            baseInput().copy(
                selectedTrackerId = "tracker-1",
                displayedTrackerId = "tracker-1",
                hasTrackPoints = false,
                activeStreamedTrackerIds = setOf("tracker-1")
            )
        )
        assertEquals(
            MapResumeDecision.LoadSingleTrackerBootstrap(trackerId = "tracker-1"),
            decision
        )
    }

    private fun baseInput() = MapResumeInput(
        trackingRunning = false,
        mapReady = true,
        showAllTrackers = false,
        mapViewContext = MapViewContext.SINGLE_TRACKER,
        activeStreamedTrackerIds = emptySet(),
        currentGroupTrackIds = emptySet(),
        selectedTrackerId = "tracker-1",
        displayedTrackerId = null,
        hasTrackPoints = false,
        hasPendingInitialTracker = false,
        backgroundedDurationMs = 0L
    )
}
