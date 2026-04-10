package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapTrailReloadGuardPolicyTest {

    private fun input(
        force: Boolean = false,
        mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
        trailSize: Int = 50,
        runtimeRunning: Boolean = false,
        activeStreamedTrackerIds: Set<String> = emptySet(),
        displayedTrackerId: String = "tracker1",
    ) = TrailReloadGuardInput(
        force = force,
        mode = mode,
        trailSize = trailSize,
        runtimeRunning = runtimeRunning,
        activeStreamedTrackerIds = activeStreamedTrackerIds,
        displayedTrackerId = displayedTrackerId,
    )

    @Test
    fun forceAlwaysProceeds() {
        assertTrue(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(force = true, runtimeRunning = true, trailSize = 100)
            )
        )
        assertTrue(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(force = true, activeStreamedTrackerIds = setOf("tracker1"), trailSize = 100)
            )
        )
    }

    @Test
    fun emptyTrailAlwaysProceeds() {
        assertTrue(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(trailSize = 0, runtimeRunning = true)
            )
        )
        assertTrue(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(trailSize = 0, activeStreamedTrackerIds = setOf("tracker1"))
            )
        )
    }

    @Test
    fun emptyDisplayedTrackerIdProceeds() {
        assertTrue(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(displayedTrackerId = "", runtimeRunning = true, trailSize = 100)
            )
        )
        assertTrue(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(displayedTrackerId = "   ", runtimeRunning = true, trailSize = 100)
            )
        )
    }

    @Test
    fun runtimeRunningSuppressesReload() {
        assertFalse(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(runtimeRunning = true, trailSize = 10)
            )
        )
    }

    @Test
    fun streamingActiveSuppressesReload() {
        assertFalse(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(activeStreamedTrackerIds = setOf("tracker1"), trailSize = 10)
            )
        )
    }

    @Test
    fun streamingActiveDifferentTrackerProceeds() {
        assertTrue(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(
                    activeStreamedTrackerIds = setOf("other_tracker"),
                    displayedTrackerId = "tracker1",
                    trailSize = 10,
                )
            )
        )
    }

    @Test
    fun noStreamingNoTrackingProceeds() {
        assertTrue(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(runtimeRunning = false, activeStreamedTrackerIds = emptySet(), trailSize = 10)
            )
        )
    }

    @Test
    fun displayedTrackerIdWhitespaceTrimmed() {
        assertFalse(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(
                    displayedTrackerId = "  tracker1  ",
                    activeStreamedTrackerIds = setOf("tracker1"),
                    trailSize = 10,
                )
            )
        )
    }

    @Test
    fun groupModeStreamingSuppressesReload() {
        assertFalse(
            TrackerMapTrailReloadGuardPolicy.shouldProceed(
                input(
                    mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                    displayedTrackerId = "tracker1",
                    activeStreamedTrackerIds = setOf("tracker1"),
                    trailSize = 10,
                )
            )
        )
    }
}
