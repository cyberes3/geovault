package com.geovault.tracker.presentation

import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapReopenOrchestratorTest {

    @Test
    fun trackingWithPoints_doesNotEmitDestructiveInvariantViolation() {
        val outcome = TrackerMapReopenOrchestrator().resolve(
            TrackerMapResumeInput(
                trackingRunning = true,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                hasTrailPoints = true,
                backgroundedDurationMs = 500L
            )
        )

        val destructiveInvariant = outcome.invariants.first {
            it.invariant == TrackerMapRuntimeInvariant.TRACKING_WITH_POINTS_MUST_NOT_FORCE_DESTRUCTIVE_RELOAD
        }
        assertTrue(destructiveInvariant.satisfied)
    }
}
