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
    fun trackingAll_acceptsRemoteStreamForActiveOtherTracker() {
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
        assertTrue(accepted)
    }

    @Test
    fun trackingAll_rejectsRemoteStreamForSelectedTracker() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "selected"),
            input = input(
                trackingRunning = true,
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = setOf("selected", "other")
            )
        )
        assertFalse(accepted)
    }

    @Test
    fun trackingSingleOtherTracker_acceptsDisplayedRemoteStream() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "remote"),
            input = input(
                trackingRunning = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "selected",
                displayedTrackerId = "remote",
                activeStreamedTrackerIds = setOf("remote")
            )
        )
        assertTrue(accepted)
    }

    @Test
    fun trackingSingleOtherTracker_rejectsLocalGpsForSingleRemoteView() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.LOCAL_GPS, trackId = "selected"),
            input = input(
                trackingRunning = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "selected",
                displayedTrackerId = "remote",
                activeStreamedTrackerIds = setOf("remote")
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

    @Test
    fun trackingSingle_withBlankSelected_acceptsLocalForAnyTracker() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.LOCAL_GPS, trackId = "any-id"),
            input = input(
                trackingRunning = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "",
                displayedTrackerId = "",
                activeStreamedTrackerIds = emptySet()
            )
        )
        assertTrue(accepted)
    }

    @Test
    fun trackingSingle_rejectsLocalForDifferentSelectedTracker() {
        val accepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.LOCAL_GPS, trackId = "other"),
            input = input(
                trackingRunning = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = emptySet()
            )
        )
        assertFalse(accepted)
    }

    @Test
    fun browsingGroupPlaceholder_acceptsOnlyRemoteFromActiveIds() {
        val remoteAccepted = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "active"),
            input = input(
                trackingRunning = false,
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = setOf("active")
            )
        )
        val localRejected = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.LOCAL_GPS, trackId = "active"),
            input = input(
                trackingRunning = false,
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = setOf("active")
            )
        )
        val remoteRejected = TrackerMapPointAcceptancePolicy.shouldAccept(
            event = event(source = TrackPointSource.REMOTE_STREAM, trackId = "inactive"),
            input = input(
                trackingRunning = false,
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                selectedTrackerId = "selected",
                displayedTrackerId = "selected",
                activeStreamedTrackerIds = setOf("active")
            )
        )
        assertTrue(remoteAccepted)
        assertFalse(localRejected)
        assertFalse(remoteRejected)
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
