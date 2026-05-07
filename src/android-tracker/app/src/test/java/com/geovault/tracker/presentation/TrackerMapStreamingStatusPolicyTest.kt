package com.geovault.tracker.presentation

import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import com.geovault.tracker.services.StreamingHealth
import com.geovault.tracker.services.StreamingIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapStreamingStatusPolicyTest {

    @Test
    fun resolve_idleStopped_noTargets_returnsInactive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(),
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
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.Starting,
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
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.Starting,
                activeTrackerIds = setOf("t1"),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_reconnecting_alwaysReconnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.Reconnecting,
                activeTrackerIds = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
    }

    @Test
    fun resolve_running_singleTracker_returnsLive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.Running,
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
                intent = StreamingIntent.Wanted(setOf("t1", "t2", "t3")),
                health = StreamingHealth.Running,
                activeTrackerIds = setOf("t1", "t2", "t3"),
            ),
            streamTargetIds = setOf("t1", "t2", "t3"),
        )

        assertEquals(TrackerMapStreamingStatus.LIVE, result.status)
        assertEquals(3, result.activeCount)
    }

    @Test
    fun resolve_running_partialActiveIds_returnsReconnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Wanted(setOf("t1", "t2")),
                health = StreamingHealth.Running,
                activeTrackerIds = setOf("t1"),
            ),
            streamTargetIds = setOf("t1", "t2"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(1, result.activeCount)
    }

    @Test
    fun resolve_running_extraActiveIds_returnsReconnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.Running,
                activeTrackerIds = setOf("t1", "t2"),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.RECONNECTING, result.status)
        assertEquals(2, result.activeCount)
    }

    @Test
    fun resolve_running_noActiveIdsForDesiredTargets_returnsConnecting() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.Running,
                activeTrackerIds = emptySet(),
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.CONNECTING, result.status)
        assertEquals(0, result.activeCount)
    }

    @Test
    fun resolve_failedTransient_returnsFailedWithReason() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.FailedTransient,
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
    fun resolve_failedPermanent_returnsFailed() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Wanted(setOf("t1")),
                health = StreamingHealth.FailedPermanent,
                activeTrackerIds = emptySet(),
                failureReason = null,
            ),
            streamTargetIds = setOf("t1"),
        )

        assertEquals(TrackerMapStreamingStatus.FAILED, result.status)
        assertNull(result.failureReason)
    }

    @Test
    fun resolve_stopped_withTargets_returnsInactive() {
        val result = TrackerMapStreamingStatusPolicy.resolve(
            snapshot = LiveStreamRuntimeSnapshot(
                intent = StreamingIntent.Idle,
                health = StreamingHealth.Stopped,
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
