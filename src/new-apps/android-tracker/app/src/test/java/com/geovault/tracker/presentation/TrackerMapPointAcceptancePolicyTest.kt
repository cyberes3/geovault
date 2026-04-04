package com.geovault.tracker.presentation

import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapPointAcceptancePolicyTest {

    @Test
    fun trackingSingle_acceptsLocalForSelectedTracker() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.LOCAL_GPS, trackId = "selected"),
            input = input(
                trackingRunning = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = emptySet()
            )
        )
        assertTrue(accepted)
    }

    @Test
    fun trackingSingle_rejectsRemoteStream() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "other"),
            input = input(
                trackingRunning = true,
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = setOf("other")
            )
        )
        assertFalse(accepted)
    }

    @Test
    fun browsingAll_acceptsOnlyActiveStreamIds() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "b"),
            input = input(
                trackingRunning = false,
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                selectedTrackerId = "a",
                displayedTrackerId = "a",
                activeStreamedTrackerIds = setOf("b")
            )
        )
        val rejected = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "c"),
            input = input(
                trackingRunning = false,
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                selectedTrackerId = "a",
                displayedTrackerId = "a",
                activeStreamedTrackerIds = setOf("b")
            )
        )
        assertTrue(accepted)
        assertFalse(rejected)
    }

    @Test
    fun browsingSingle_acceptsOnlyDisplayedTrackerRemote() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "displayed"),
            input = input(
                trackingRunning = false,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "selected",
                displayedTrackerId = "displayed",
                activeStreamedTrackerIds = emptySet()
            )
        )
        val rejected = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "other"),
            input = input(
                trackingRunning = false,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "selected",
                displayedTrackerId = "displayed",
                activeStreamedTrackerIds = emptySet()
            )
        )
        assertTrue(accepted)
        assertFalse(rejected)
    }

    private fun input(
        trackingRunning: Boolean,
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String,
        selectedTrackerId: String,
        activeStreamedTrackerIds: Set<String>
    ): TrackerMapPointAcceptanceInput {
        return TrackerMapPointAcceptanceInput(
            trackingRunning = trackingRunning,
            mode = mode,
            displayedTrackerId = displayedTrackerId,
            selectedTrackerId = selectedTrackerId,
            activeStreamedTrackerIds = activeStreamedTrackerIds
        )
    }

    private fun event(source: TrackPointSource, trackId: String): TrackPointEvent {
        return TrackPointEvent(
            source = source,
            trackId = trackId,
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1L,
            quality = TrackPointQuality.HIGH_CONFIDENCE
        )
    }
}
