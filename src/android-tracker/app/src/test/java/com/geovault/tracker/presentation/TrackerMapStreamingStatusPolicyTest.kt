package com.geovault.tracker.presentation

import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapStreamingStatusPolicyTest {

    @Test
    fun resolve_notRunning_noTargets_returnsInactive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(isRunning = false),
            streamTargetIds = emptySet(),
        )

        assertEquals(TrackerMapStreamingStatus.INACTIVE, result.status)
        assertEquals(0, result.activeCount)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_starting_noActiveIds_returnsConnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                isRunning = true,
                lifecycleState = TrackingLifecycleState.STARTING,
                activeTrackerIds = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
        assertEquals(0, result.activeCount)
    }

    @Test
    fun resolve_starting_withActiveIds_returnsReconnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                isRunning = true,
                lifecycleState = TrackingLifecycleState.STARTING,
                activeTrackerIds = setOf("t1"),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_running_singleTracker_returnsLive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                isRunning = true,
                lifecycleState = TrackingLifecycleState.RUNNING,
                activeTrackerIds = setOf("t1"),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(1, result.activeCount)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_running_multipleTrackers_returnsLiveWithCount() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                isRunning = true,
                lifecycleState = TrackingLifecycleState.RUNNING,
                activeTrackerIds = setOf("t1", "t2", "t3"),
            ),
            streamTargetIds = setOf("t1", "t2", "t3"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(3, result.activeCount)
    }

    @Test
    fun resolve_failed_returnsFailedWithReason() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                isRunning = true,
                lifecycleState = TrackingLifecycleState.FAILED,
                activeTrackerIds = setOf("t1"),
                failureReason = "Auth expired",
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.FAILED, result.status)
        assertEquals(1, result.activeCount)
        assertEquals("Auth expired", result.failureReason)
    }

    @Test
    fun resolve_failed_noReason_returnsFailedNullReason() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                isRunning = true,
                lifecycleState = TrackingLifecycleState.FAILED,
                activeTrackerIds = emptySet(),
                failureReason = null,
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.FAILED, result.status)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_stopped_withTargetsAndRunning_returnsConnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                isRunning = true,
                lifecycleState = TrackingLifecycleState.STOPPED,
                activeTrackerIds = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
    }

    @Test
    fun resolve_stopped_withTargetsNotRunning_returnsInactive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                isRunning = false,
                lifecycleState = TrackingLifecycleState.STOPPED,
                activeTrackerIds = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.INACTIVE, result.status)
    }

    @Test
    fun resolve_defaultModel_hasCorrectDefaults() {
        val model = TrackerMapStreamingStatusUiModel()

        assertEquals(TrackerMapStreamingStatus.INACTIVE, model.status)
        assertEquals(0, model.activeCount)
        assertNull(model.failureReason)
    }
}
