package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapReopenOrchestratorInvariantsTest {

    @Test
    fun resolve_trackingWithoutSelected_reportsInvariantViolation() {
        val outcome = TrackerMapReopenOrchestrator().resolve(
            TrackerMapResumeInput(
                trackingRunning = true,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "",
                displayedTrackerId = "",
                hasTrailPoints = false,
                backgroundedDurationMs = 100L
            )
        )

        val selectedInvariant = outcome.invariants.first {
            it.invariant == TrackerMapRuntimeInvariant.TRACKING_REQUIRES_SELECTED_TRACKER
        }
        assertFalse(selectedInvariant.satisfied)
    }

    @Test
    fun resolve_trackingWithPointsAndDestructiveDecision_reportsInvariantViolation() {
        val outcome = TrackerMapReopenOrchestrator().resolve(
            TrackerMapResumeInput(
                trackingRunning = true,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "",
                displayedTrackerId = "",
                hasTrailPoints = true,
                backgroundedDurationMs = 100L
            )
        )

        val destructiveInvariant = outcome.invariants.first {
            it.invariant == TrackerMapRuntimeInvariant.TRACKING_WITH_POINTS_MUST_NOT_FORCE_DESTRUCTIVE_RELOAD
        }
        assertFalse(destructiveInvariant.satisfied)
    }

    @Test
    fun resolve_nonTrackingSingleLoad_hasIdempotentLoadInvariantSatisfied() {
        val outcome = TrackerMapReopenOrchestrator().resolve(
            TrackerMapResumeInput(
                trackingRunning = false,
                mapReady = true,
                showAllTrackers = false,
                mapViewContext = TrackerMapViewContext.SINGLE_TRACKER,
                activeStreamedTrackerIds = emptySet(),
                currentGroupTrackIds = emptySet(),
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                hasTrailPoints = false,
                backgroundedDurationMs = 100L
            )
        )

        val idempotentInvariant = outcome.invariants.first {
            it.invariant == TrackerMapRuntimeInvariant.SINGLE_LOAD_COMMANDS_MUST_BE_IDEMPOTENT
        }
        assertTrue(idempotentInvariant.satisfied)
    }
}
