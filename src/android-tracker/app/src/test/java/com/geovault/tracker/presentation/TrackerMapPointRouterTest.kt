package com.geovault.tracker.presentation

import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapPointRouterTest {

    @Test
    fun singleLocalWhileTracking_routesLocalToSingleTrail() {
        val plan = plan(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "local",
            displayedTrackerId = "local",
            runtimeRunning = true,
        )

        val local = TrackerMapPointRouter.route(event(TrackPointSource.LOCAL_GPS, "local"), plan)

        assertTrue(local.accepted)
        assertFalse(local.updateRemoteLastPoint)
        assertTrue(local.appendSingleTrail)
        assertFalse(local.appendMultiTrail)
    }

    @Test
    fun singleRemoteWhileTracking_routesRemoteToSingleTrailAndRejectsLocal() {
        val plan = plan(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "local",
            displayedTrackerId = "remote",
            runtimeRunning = true,
        )

        val remote = TrackerMapPointRouter.route(event(TrackPointSource.REMOTE_STREAM, "remote"), plan)
        val local = TrackerMapPointRouter.route(event(TrackPointSource.LOCAL_GPS, "local"), plan)

        assertTrue(remote.accepted)
        assertTrue(remote.updateRemoteLastPoint)
        assertTrue(remote.appendSingleTrail)
        assertFalse(remote.appendMultiTrail)
        assertTrue(local.accepted)
        assertFalse(local.appendSingleTrail)
        assertFalse(local.appendMultiTrail)
    }

    @Test
    fun groupWhileTrackingMember_routesLocalOverlayAndRemoteMembers() {
        val plan = plan(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedTrackerId = "local",
            displayedTrackerId = "",
            runtimeRunning = true,
            groupTrackerIds = setOf("local", "remote"),
        )

        val local = TrackerMapPointRouter.route(event(TrackPointSource.LOCAL_GPS, "local"), plan)
        val remote = TrackerMapPointRouter.route(event(TrackPointSource.REMOTE_STREAM, "remote"), plan)

        assertTrue(local.accepted)
        assertTrue(local.appendMultiTrail)
        assertFalse(local.appendSingleTrail)
        assertTrue(remote.accepted)
        assertTrue(remote.updateRemoteLastPoint)
        assertTrue(remote.appendMultiTrail)
    }

    @Test
    fun groupWhileTracking_rejectsRemoteEchoForLocalTracker() {
        val plan = plan(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedTrackerId = "local",
            displayedTrackerId = "",
            runtimeRunning = true,
            groupTrackerIds = setOf("local", "remote"),
        )

        val route = TrackerMapPointRouter.route(event(TrackPointSource.REMOTE_STREAM, "local"), plan)

        assertFalse(route.accepted)
    }

    private fun plan(
        mode: TrackerMapDisplayMode,
        selectedTrackerId: String,
        displayedTrackerId: String,
        runtimeRunning: Boolean,
        groupTrackerIds: Set<String> = emptySet(),
    ): TrackerMapStreamingPlan {
        return TrackerMapSessionProjector.project(
            TrackerMapSessionIntent(
                mode = mode,
                runtime = TrackingRuntimeSnapshot(
                    isRunning = runtimeRunning,
                    recordingRuntime = RecordingRuntime(
                        sessionActive = runtimeRunning,
                        selectedTrackerId = selectedTrackerId,
                    ),
                    selectedTrackerId = selectedTrackerId,
                ),
                displayedTrackerId = displayedTrackerId,
                displayedTrackerName = "",
                rosterTrackerIds = emptySet(),
                groupSelection = TrackerMapGroupModeSelection(groupId = "g1", trackerIds = groupTrackerIds),
                activeStreamedTrackerIds = emptySet(),
            )
        )
    }

    private fun event(source: TrackPointSource, trackId: String): TrackPointEvent {
        return TrackPointEvent(
            source = source,
            trackId = trackId,
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1000L,
        )
    }
}
