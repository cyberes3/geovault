package com.geovault.tracker.policy

import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapGroupModeSelection
import com.geovault.tracker.presentation.TrackerMapPointRouter
import com.geovault.tracker.map.MapSessionEngine
import com.geovault.tracker.presentation.TrackerMapSessionIntent
import com.geovault.tracker.positioning.RecordingRuntime
import com.geovault.tracker.positioning.TrackingRuntimeSnapshot
import com.geovault.tracker.runtime.TrackerRuntimeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdmissionPipelineTest {
    private val pipeline = AdmissionPipeline()

    @Before
    fun setUp() {
        AdmissionPipeline.resetForTests()
        TrackerRuntimeStore.updateRecording {
            it.copy(
                isRunning = false,
                recordingRuntime = RecordingRuntime(),
                selectedTrackerId = "",
            )
        }
    }

    @Test
    fun process_notInSubscriptionScope_isDroppedAndCounted() {
        val result = admit(
            remoteEvent(trackId = "unsubscribed"),
            subscriptionScope = setOf("remote"),
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
        val result = admit(
            remoteEvent(lon = 500.0),
            subscriptionScope = setOf("remote"),
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
        val result = admit(
            remoteEvent(timestampMs = NOW_MS / 1000L),
            subscriptionScope = setOf("remote"),
        )

        assertNotNull(result)
        assertEquals(NOW_MS, result!!.timeMs)
        assertTrue(result.orderingKey > 0L)
    }

    @Test
    fun process_remoteForLocallyRecordedSelectedTracker_isDroppedAndCounted() {
        TrackerRuntimeStore.updateRecording {
            it.copy(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "selected"),
                selectedTrackerId = "selected",
            )
        }

        val result = admit(
            remoteEvent(trackId = "selected"),
            subscriptionScope = setOf("selected"),
        )

        assertNull(result)
        val snapshot = RemoteTrackPointAdmissionDiagnostics.snapshot()
        assertEquals(1L, snapshot.totalRejected(RemoteTrackPointAdmissionStage.LOCAL_ECHO))
    }

    @Test
    fun process_remoteForUiSelectedButNotRecordedTracker_isAccepted() {
        TrackerRuntimeStore.updateRecording {
            it.copy(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                selectedTrackerId = "selected",
            )
        }

        val result = admit(
            remoteEvent(trackId = "selected"),
            subscriptionScope = setOf("selected"),
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
        val result = admit(
            remoteEvent(timestampMs = staleTimestampMs),
            subscriptionScope = setOf("remote"),
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
        TrackerRuntimeStore.updateRecording {
            it.copy(isRunning = false, recordingRuntime = RecordingRuntime(), selectedTrackerId = "")
        }

        val admitted = admit(
            remoteEvent(trackId = "remote"),
            subscriptionScope = setOf("remote"),
        )
        assertNotNull("Point must survive stages 1-3 (subscription/local-echo/freshness)", admitted)

        val plan = MapSessionEngine.project(
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
        TrackerRuntimeStore.updateRecording {
            it.copy(isRunning = false, recordingRuntime = RecordingRuntime(), selectedTrackerId = "")
        }
        val admitted = admit(
            remoteEvent(trackId = "remote"),
            subscriptionScope = setOf("remote"),
        )
        assertNotNull(admitted)

        val plan = MapSessionEngine.project(
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

    private fun admit(
        event: TrackPoint,
        subscriptionScope: Set<String>,
    ): TrackPoint? {
        return when (
            val outcome = pipeline.admit(
                profile = AdmissionProfile.RemoteStream,
                event = event,
                subscriptionScope = subscriptionScope,
                nowMs = NOW_MS,
            )
        ) {
            is AdmissionOutcome.Admitted -> outcome.event
            is AdmissionOutcome.Rejected -> null
        }
    }

    private fun remoteEvent(
        trackId: String = "remote",
        lon: Double = 20.0,
        lat: Double = 10.0,
        timestampMs: Long = NOW_MS,
    ): TrackPoint {
        return TrackPoint(
            provenance = TrackPointSource.REMOTE_STREAM,
            trackerId = trackId,
            longitude = lon,
            latitude = lat,
            timeMs = timestampMs,
        )
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}
