package com.geovault.tracker.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.geovault.tracker.map.MapTrailEngine
class TrackerMapTrailReloadGuardPolicyTest {

    private fun input(
        mode: TrackerMapDisplayMode = TrackerMapDisplayMode.SINGLE_SESSION,
        trailSize: Int = 50,
        runtimeRunning: Boolean = false,
        displayedTrackerId: String = "tracker1",
        trailReloadPlan: TrackerMapTrailReloadPlan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.SINGLE_SERVER,
            singleTrackerId = displayedTrackerId.trim(),
            activeTrackerId = displayedTrackerId.trim(),
        ),
    ) = TrailReloadGuardInput(
        mode = mode,
        trailSize = trailSize,
        runtimeRunning = runtimeRunning,
        displayedTrackerId = displayedTrackerId,
        trailReloadPlan = trailReloadPlan,
    )

    @Test
    fun nonRecordingSourceProceedsEvenWhileRuntimeRunning() {
        // The active-recording protection is scoped to the SINGLE_QUEUE source only —
        // `runtimeRunning` alone (e.g. a different tracker is displayed while this device
        // records) must not block a reload.
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(runtimeRunning = true, trailSize = 100)
            )
        )
    }

    @Test
    fun emptyTrailAlwaysProceeds() {
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(trailSize = 0, runtimeRunning = true)
            )
        )
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(trailSize = 0)
            )
        )
    }

    @Test
    fun emptyDisplayedTrackerIdProceeds() {
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(displayedTrackerId = "", runtimeRunning = true, trailSize = 100)
            )
        )
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(displayedTrackerId = "   ", runtimeRunning = true, trailSize = 100)
            )
        )
    }

    @Test
    fun runtimeRunningSingleQueueSuppressesReload() {
        // REGRESSION: previously a `force=true` reload reason (MapContextChange,
        // RosterChanged, etc.) short-circuited straight past this check even when the
        // displayed trail was the actively-recorded tracker's own live-updating queue.
        // There is no longer any input capable of bypassing it.
        assertFalse(
            MapTrailEngine.shouldProceedReload(
                input(
                    runtimeRunning = true,
                    trailSize = 10,
                    trailReloadPlan = TrackerMapTrailReloadPlan(
                        source = TrackerMapTrailSource.SINGLE_QUEUE,
                        activeTrackerId = "tracker1",
                    )
                )
            )
        )
    }

    @Test
    fun runtimeRunningMultiServerProceeds() {
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(
                    mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                    runtimeRunning = true,
                    trailSize = 10,
                    trailReloadPlan = TrackerMapTrailReloadPlan(
                        source = TrackerMapTrailSource.MULTI_SERVER,
                        trackerIds = setOf("tracker1", "tracker2"),
                        activeTrackerId = "tracker1",
                    )
                )
            )
        )
    }

    @Test
    fun singleServerProceedsWhenTrailExists() {
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(trailSize = 10)
            )
        )
    }

    @Test
    fun noStreamingNoTrackingProceeds() {
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(runtimeRunning = false, trailSize = 10)
            )
        )
    }

    @Test
    fun displayedTrackerIdWhitespaceTrimmed() {
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(
                    displayedTrackerId = "  tracker1  ",
                    trailSize = 10,
                )
            )
        )
    }

    @Test
    fun groupModeStreamingProceedsForMultiServerReload() {
        assertTrue(
            MapTrailEngine.shouldProceedReload(
                input(
                    mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                    displayedTrackerId = "tracker1",
                    trailSize = 10,
                    trailReloadPlan = TrackerMapTrailReloadPlan(
                        source = TrackerMapTrailSource.MULTI_SERVER,
                        trackerIds = setOf("tracker1", "tracker2"),
                        activeTrackerId = "tracker1",
                    )
                )
            )
        )
    }
}
