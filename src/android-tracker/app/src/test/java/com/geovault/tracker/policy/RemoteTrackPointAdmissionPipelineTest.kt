package com.geovault.tracker.policy

import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import com.geovault.tracker.presentation.TrackerMapPointRouter
import com.geovault.tracker.presentation.TrackerMapSessionIntent
import com.geovault.tracker.presentation.TrackerMapSessionProjector
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteTrackPointAdmissionPipelineTest {

    @Before
    fun setUp() {
        RemoteTrackPointAdmissionPipeline.resetForTests()
        TrackingRuntimeStateStore.update {
            it.copy(
                isRunning = false,
                recordingRuntime = RecordingRuntime(),
                selectedTrackerId = "",
            )
        }
    }

    @Test
    fun process_notInSubscriptionScope_isDroppedAndCounted() {
        val result = RemoteTrackPointAdmissionPipeline.process(
            remoteEvent(trackId = "unsubscribed"),
            subscriptionScope = setOf("remote"),
            nowMs = NOW_MS,
        )

        assertNull(result)
        val snapshot = RemoteTrackPointAdmissionDiagnostics.snapshot()
        assertEquals(
            1L,
            snapshot.rejectedCount(RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "not_subscribed"),
        )
    }

    @Test
    fun process_invalidCoordinates_isDroppedAndCounted() {
        val result = RemoteTrackPointAdmissionPipeline.process(
            remoteEvent(lon = 500.0),
            subscriptionScope = setOf("remote"),
            nowMs = NOW_MS,
        )

        assertNull(result)
        val snapshot = RemoteTrackPointAdmissionDiagnostics.snapshot()
        assertEquals(
            1L,
            snapshot.rejectedCount(RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "invalid_payload"),
        )
    }

    @Test
    fun process_secondsTimestamp_normalizesBeforePolicy() {
        val result = RemoteTrackPointAdmissionPipeline.process(
            remoteEvent(timestampMs = NOW_MS / 1000L),
            subscriptionScope = setOf("remote"),
            nowMs = NOW_MS,
        )

        assertNotNull(result)
        assertEquals(NOW_MS, result!!.timestampMs)
        assertTrue(result.orderingKey > 0L)
    }

    @Test
    fun process_remoteForLocallyRecordedSelectedTracker_isDroppedAndCounted() {
        TrackingRuntimeStateStore.update {
            it.copy(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "selected"),
                selectedTrackerId = "selected",
            )
        }

        val result = RemoteTrackPointAdmissionPipeline.process(
            remoteEvent(trackId = "selected"),
            subscriptionScope = setOf("selected"),
            nowMs = NOW_MS,
        )

        assertNull(result)
        val snapshot = RemoteTrackPointAdmissionDiagnostics.snapshot()
        assertEquals(1L, snapshot.totalRejected(RemoteTrackPointAdmissionStage.LOCAL_ECHO))
    }

    @Test
    fun process_remoteForUiSelectedButNotRecordedTracker_isAccepted() {
        TrackingRuntimeStateStore.update {
            it.copy(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "selected",
            )
        }

        val result = RemoteTrackPointAdmissionPipeline.process(
            remoteEvent(trackId = "selected"),
            subscriptionScope = setOf("selected"),
            nowMs = NOW_MS,
        )

        assertNotNull(result)
        val snapshot = RemoteTrackPointAdmissionDiagnostics.snapshot()
        assertEquals(0L, snapshot.totalRejected(RemoteTrackPointAdmissionStage.LOCAL_ECHO))
    }

    @Test
    fun process_staleBeyondFreshnessTtl_isDroppedAtFreshnessOrderingStage() {
        // No `markConnected` call was made, so there is no reconnect-catchup grace window in
        // effect and the plain freshness TTL (30 minutes) applies.
        val staleTimestampMs = NOW_MS - java.util.concurrent.TimeUnit.MINUTES.toMillis(31)
        val result = RemoteTrackPointAdmissionPipeline.process(
            remoteEvent(timestampMs = staleTimestampMs),
            subscriptionScope = setOf("remote"),
            nowMs = NOW_MS,
        )

        assertNull(result)
        val snapshot = RemoteTrackPointAdmissionDiagnostics.snapshot()
        assertTrue(snapshot.totalRejected(RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING) > 0L)
    }

    /**
     * End-to-end regression test for the full ordered pipeline this class's doc comment
     * describes: SUBSCRIPTION_SCOPE -> LOCAL_ECHO -> FRESHNESS_ORDERING (this class), then
     * VISIBILITY_ROUTING -> PUBLISH ([TrackerMapPointRouter], normally invoked downstream by
     * `TrackPointReducer`). A regression in any one stage silently dropping a point that should
     * have survived is exactly the "streamed tracker not updating" failure mode this whole audit
     * was built to catch -- this proves a single point can travel through all five stages intact.
     */
    @Test
    fun process_acceptedPoint_survivesFullPipelineThroughVisibilityRoutingAndPublish() {
        TrackingRuntimeStateStore.update {
            it.copy(isRunning = false, recordingRuntime = RecordingRuntime(), selectedTrackerId = "")
        }

        val admitted = RemoteTrackPointAdmissionPipeline.process(
            remoteEvent(trackId = "remote"),
            subscriptionScope = setOf("remote"),
            nowMs = NOW_MS,
        )
        assertNotNull("Point must survive stages 1-3 (subscription/local-echo/freshness)", admitted)

        val plan = TrackerMapSessionProjector.project(
            TrackerMapSessionIntent(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtime = TrackingRuntimeSnapshot(),
                displayedTrackerId = "remote",
                displayedTrackerName = "Remote",
                rosterTrackerIds = setOf("remote"),
                groupSelection = TrackerMapGroupModeSelection(groupId = "", trackerIds = emptySet()),
                activeStreamedTrackerIds = setOf("remote"),
            ),
        )
        val route = TrackerMapPointRouter.route(admitted!!, plan)

        assertTrue("Stage 4 (VISIBILITY_ROUTING) must accept the displayed tracker's point", route.accepted)
        assertTrue("Stage 5 (PUBLISH) must update the remote-last-point cache", route.updateRemoteLastPoint)
        assertTrue(route.appendSingleTrail)
    }

    @Test
    fun process_acceptedButNotVisibleTracker_isRejectedAtVisibilityRoutingStage() {
        // A point can legitimately clear subscription/local-echo/freshness yet still not belong
        // on screen right now (e.g. the map is displaying a different single tracker) --
        // VISIBILITY_ROUTING is a distinct, later gate from the first three stages.
        TrackingRuntimeStateStore.update {
            it.copy(isRunning = false, recordingRuntime = RecordingRuntime(), selectedTrackerId = "")
        }
        val admitted = RemoteTrackPointAdmissionPipeline.process(
            remoteEvent(trackId = "remote"),
            subscriptionScope = setOf("remote"),
            nowMs = NOW_MS,
        )
        assertNotNull(admitted)

        val plan = TrackerMapSessionProjector.project(
            TrackerMapSessionIntent(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                runtime = TrackingRuntimeSnapshot(),
                displayedTrackerId = "someone-else",
                displayedTrackerName = "Someone Else",
                rosterTrackerIds = setOf("remote", "someone-else"),
                groupSelection = TrackerMapGroupModeSelection(groupId = "", trackerIds = emptySet()),
                activeStreamedTrackerIds = setOf("remote"),
            ),
        )
        val route = TrackerMapPointRouter.route(admitted!!, plan)

        assertFalse(route.accepted)
    }

    private fun remoteEvent(
        trackId: String = "remote",
        lon: Double = 20.0,
        lat: Double = 10.0,
        timestampMs: Long = NOW_MS,
    ): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = trackId,
            lon = lon,
            lat = lat,
            timestampMs = timestampMs,
        )
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}
