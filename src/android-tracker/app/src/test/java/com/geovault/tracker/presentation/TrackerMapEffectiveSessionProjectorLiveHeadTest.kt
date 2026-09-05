package com.geovault.tracker.presentation

import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackerMapEffectiveSessionProjectorLiveHeadTest {
    @Test
    fun resolveLiveHead_viewingSharedWhileRecording_usesSharedRemoteNotNull() {
        val remote = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "shared",
            lon = 6.0,
            lat = 5.0,
            timestampMs = 2_000L,
        )
        val trail = listOf(
            QueuedLocation(
                trackerId = "shared",
                time = 1_000L,
                latitude = 1.0,
                longitude = 2.0,
                altitude = null,
                speed = null,
                bearing = null,
                accuracy = null,
            ),
        )
        val snapshot = TrackerMapSessionSnapshot(
            uiState = TrackerMapUiState(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayedTrackerId = "shared",
                trail = trail,
                remoteLastPoints = mapOf("shared" to remote),
            ),
            plan = TrackerMapStreamingPlan(
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "self",
                displayedTrackerId = "shared",
                displayedTrackerName = "Shared",
                resolvedGroupId = "",
                groupTrackerIds = emptySet(),
                visibleRosterTrackerIds = setOf("shared"),
                locallyRecordedTrackerIds = setOf("self"),
                remoteSubscriptionIds = setOf("shared"),
                acceptedRemoteTrackerIds = setOf("shared"),
                localOverlayTrackerIds = emptySet(),
                trailReloadPlan = TrackerMapTrailReloadPlan(source = TrackerMapTrailSource.SINGLE_SERVER),
            ),
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "self"),
                selectedTrackerId = "self",
                lastTrackedLatitude = 30.0,
                lastTrackedLongitude = 40.0,
                lastTrackedTimestampMs = 9_000L,
            ),
            singleTrail = trail,
            tracks = mapOf("shared" to TrackerTrackModel(trackerId = "shared", renderTrail = trail, remoteHead = remote)),
            acceptedRemoteLastPoints = mapOf("shared" to remote),
        )

        val head = TrackerMapEffectiveSessionProjector.resolveLiveHead(snapshot)

        assertNotNull(head)
        assertEquals(5.0 to 6.0, head)
    }
}
