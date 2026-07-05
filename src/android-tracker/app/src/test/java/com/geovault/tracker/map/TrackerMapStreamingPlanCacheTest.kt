package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapTrailReloadPlan
import com.geovault.tracker.presentation.TrackerMapTrailSource
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TrackerMapStreamingPlanCacheTest {
    private fun plan(displayedTrackerId: String) = TrackerMapStreamingPlan(
        mode = TrackerMapDisplayMode.SINGLE_SESSION,
        selectedTrackerId = displayedTrackerId,
        displayedTrackerId = displayedTrackerId,
        displayedTrackerName = "",
        resolvedGroupId = "",
        groupTrackerIds = emptySet(),
        visibleRosterTrackerIds = emptySet(),
        locallyRecordedTrackerIds = emptySet(),
        remoteSubscriptionIds = setOf(displayedTrackerId),
        acceptedRemoteTrackerIds = setOf(displayedTrackerId),
        localOverlayTrackerIds = emptySet(),
        trailReloadPlan = TrackerMapTrailReloadPlan(
            source = TrackerMapTrailSource.SINGLE_SERVER,
            singleTrackerId = displayedTrackerId,
            activeTrackerId = displayedTrackerId,
        ),
    )

    @Test
    fun reusesCachedPlanWhenSignatureUnchanged() {
        val cache = TrackerMapStreamingPlanCache()
        val state = TrackerMapUiState(displayedTrackerId = "tracker1")
        var computeCalls = 0
        val compute: (TrackerMapUiState) -> TrackerMapStreamingPlan = {
            computeCalls++
            plan(it.displayedTrackerId)
        }

        val first = cache.resolve(state, compute)
        // A point event only changes the trail -- an irrelevant field for the plan -- so the
        // signature must still match and the cached plan must be reused verbatim.
        val second = cache.resolve(state.copy(trail = listOf()), compute)

        assertSame(first, second)
        assertEquals(1, computeCalls)
    }

    @Test
    fun recomputesWhenDisplayedTrackerChanges() {
        val cache = TrackerMapStreamingPlanCache()
        var computeCalls = 0
        val compute: (TrackerMapUiState) -> TrackerMapStreamingPlan = {
            computeCalls++
            plan(it.displayedTrackerId)
        }

        cache.resolve(TrackerMapUiState(displayedTrackerId = "tracker1"), compute)
        val second = cache.resolve(TrackerMapUiState(displayedTrackerId = "tracker2"), compute)

        assertEquals(2, computeCalls)
        assertEquals("tracker2", second.displayedTrackerId)
    }

    @Test
    fun recomputesWhenRosterFingerprintChanges() {
        // renderMetadataSignature is the proxy for roster/group/visibility structural changes;
        // a change there (e.g. a tracker deleted from the roster) must invalidate the cache even
        // though none of the other signature fields moved.
        val cache = TrackerMapStreamingPlanCache()
        var computeCalls = 0
        val compute: (TrackerMapUiState) -> TrackerMapStreamingPlan = {
            computeCalls++
            plan(it.displayedTrackerId)
        }
        val base = TrackerMapUiState(displayedTrackerId = "tracker1", renderMetadataSignature = "sig-a")

        cache.resolve(base, compute)
        cache.resolve(base.copy(renderMetadataSignature = "sig-b"), compute)

        assertEquals(2, computeCalls)
    }

    @Test
    fun recomputesWhenRuntimeRecordingStateChanges() {
        val cache = TrackerMapStreamingPlanCache()
        var computeCalls = 0
        val compute: (TrackerMapUiState) -> TrackerMapStreamingPlan = {
            computeCalls++
            plan(it.displayedTrackerId)
        }
        val base = TrackerMapUiState(
            displayedTrackerId = "tracker1",
            runtime = TrackingRuntimeSnapshot(
                recordingRuntime = RecordingRuntime(sessionActive = false, selectedTrackerId = "tracker1"),
            ),
        )
        val recording = base.copy(
            runtime = base.runtime.copy(
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "tracker1"),
            ),
        )

        cache.resolve(base, compute)
        cache.resolve(recording, compute)

        assertEquals(2, computeCalls)
    }

    @Test
    fun warmPopulatesCacheWithoutInvokingCompute() {
        val cache = TrackerMapStreamingPlanCache()
        val state = TrackerMapUiState(displayedTrackerId = "tracker1")
        val warmedPlan = plan("tracker1")

        cache.warm(state, warmedPlan)
        var computeCalls = 0
        val resolved = cache.resolve(state) { computeCalls++; plan(it.displayedTrackerId) }

        assertSame(warmedPlan, resolved)
        assertEquals(0, computeCalls)
    }
}
