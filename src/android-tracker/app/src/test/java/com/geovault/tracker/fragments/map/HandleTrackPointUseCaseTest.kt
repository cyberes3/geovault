package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleTrackPointUseCaseTest {
    @Test
    fun shouldAccept_allTrackers_acceptsActiveStreamedTracker() {
        val useCase = HandleTrackPointUseCase()
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "t1",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1000L
        )

        val accepted = useCase.shouldAccept(
            event = event,
            trackingRunning = false,
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = null,
            activeStreamedTrackerIds = setOf("t1")
        )

        assertTrue(accepted)
    }

    @Test
    fun shouldAccept_singleMode_rejectsDifferentTracker() {
        val useCase = HandleTrackPointUseCase()
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "t2",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1000L
        )

        val accepted = useCase.shouldAccept(
            event = event,
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "t1",
            activeStreamedTrackerIds = setOf("t1")
        )

        assertFalse(accepted)
    }
}

