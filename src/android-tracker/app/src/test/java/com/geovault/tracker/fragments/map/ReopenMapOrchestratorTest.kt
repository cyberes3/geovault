package com.geovault.tracker.fragments.map

import org.junit.Assert.assertTrue
import org.junit.Test

class ReopenMapOrchestratorTest {
    private val orchestrator = ReopenMapOrchestrator()

    @Test
    fun resolve_trackingWithPointsAndMissingDisplayedId_keepsNoOp() {
        val outcome = orchestrator.resolve(
            MapResumeInput(
                trackingRunning = true,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = MapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "tracker-1",
                displayedTrackerId = null,
                hasTrackPoints = true,
                hasPendingInitialTracker = false,
                backgroundedDurationMs = 0L
            )
        )
        assertTrue(outcome.command is MapReopenCommand.NoOp)
        assertTrue(outcome.invariants.all { it.satisfied })
    }

    @Test
    fun resolve_trackingWithoutSelectedTracker_reportsInvariantViolation() {
        val outcome = orchestrator.resolve(
            MapResumeInput(
                trackingRunning = true,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = MapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "",
                displayedTrackerId = "tracker-2",
                hasTrackPoints = false,
                hasPendingInitialTracker = false,
                backgroundedDurationMs = 0L
            )
        )
        assertTrue(
            outcome.invariants.any {
                it.invariant == MapRuntimeInvariant.TRACKING_REQUIRES_SELECTED_TRACKER &&
                    !it.satisfied
            }
        )
    }
}
