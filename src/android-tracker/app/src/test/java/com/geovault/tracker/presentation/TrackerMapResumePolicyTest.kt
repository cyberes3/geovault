package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapResumePolicyTest {

    private val resolver = TrackerMapResolveResumeUseCase()

    @Test
    fun longBackgroundGap_nonTracking_single_returnsRuntimeReload() {
        val decision = resolver.resolve(
            TrackerMapResumeInput(
                trackingRunning = false,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "a",
                displayedTrackerId = "a",
                hasTrailPoints = true,
                backgroundedDurationMs = TrackerMapResolveResumeUseCase.BACKFILL_MIN_BACKGROUND_MS
            )
        )
        assertEquals(TrackerMapResumeDecision.LoadSingleTrackerRuntime("a"), decision)
    }

    @Test
    fun trackingWithPointsSameTracker_returnsNoOp() {
        val decision = resolver.resolve(
            TrackerMapResumeInput(
                trackingRunning = true,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "a",
                displayedTrackerId = "a",
                hasTrailPoints = true,
                backgroundedDurationMs = 1_000L
            )
        )
        assertEquals(TrackerMapResumeDecision.NoOp, decision)
    }

    @Test
    fun groupContext_prefersActiveStreams() {
        val decision = resolver.resolve(
            TrackerMapResumeInput(
                trackingRunning = false,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.GROUP,
                activeStreamedTrackerIds = setOf("x", "y"),
                currentGroupTrackIds = setOf("z"),
                selectedTrackerId = "a",
                displayedTrackerId = "",
                hasTrailPoints = false,
                backgroundedDurationMs = 2_000L
            )
        )
        assertEquals(TrackerMapResumeDecision.StartMultiContextStreaming(setOf("x", "y")), decision)
    }
}
