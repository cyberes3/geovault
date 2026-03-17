package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTrackPointReducerTest {
    private fun stateFromContext(context: MapTrackPointContext): MapTrackPointState {
        return MapTrackPointReducer.stateFromContext(context)
    }

    @Test
    fun acceptsLocalGpsWhenTrackingSingleTrackerMatchesDisplayed() {
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t1",
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1000L
        )
        val context = MapTrackPointContext(
            trackingRunning = true,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "t1",
            activeStreamedTrackerIds = emptySet()
        )
        assertTrue(MapTrackPointReducer.shouldAcceptPoint(event, stateFromContext(context)))
    }

    @Test
    fun rejectsRemoteStreamWhileTracking() {
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "t1",
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1000L
        )
        val context = MapTrackPointContext(
            trackingRunning = true,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "t1",
            activeStreamedTrackerIds = emptySet()
        )
        assertFalse(MapTrackPointReducer.shouldAcceptPoint(event, stateFromContext(context)))
    }

    @Test
    fun acceptsRemoteStreamInMultiContextOnlyForActiveTrackerIds() {
        val acceptedEvent = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "t2",
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1000L
        )
        val rejectedEvent = acceptedEvent.copy(trackId = "other")
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = null,
            activeStreamedTrackerIds = setOf("t2")
        )
        val state = stateFromContext(context)
        assertTrue(MapTrackPointReducer.shouldAcceptPoint(acceptedEvent, state))
        assertFalse(MapTrackPointReducer.shouldAcceptPoint(rejectedEvent, state))
    }

    @Test
    fun rejectsLocalGpsWhenNotTracking() {
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t1",
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1000L
        )
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "t1",
            activeStreamedTrackerIds = emptySet()
        )
        assertFalse(MapTrackPointReducer.shouldAcceptPoint(event, stateFromContext(context)))
    }

    @Test
    fun rejectsRemoteStreamForDifferentSingleTracker() {
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "other",
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1000L
        )
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "t1",
            activeStreamedTrackerIds = emptySet()
        )
        assertFalse(MapTrackPointReducer.shouldAcceptPoint(event, stateFromContext(context)))
    }

    @Test
    fun modeMatrix_remoteStreamAcceptedForSingleGroupAndAllWhenRoutingMatches() {
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "track-a",
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1000L
        )

        val singleContext = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "track-a",
            activeStreamedTrackerIds = emptySet()
        )
        val groupContext = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.GROUP,
            displayedTrackerId = null,
            activeStreamedTrackerIds = setOf("track-a")
        )
        val allContext = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = null,
            activeStreamedTrackerIds = setOf("track-a")
        )

        assertTrue(MapTrackPointReducer.shouldAcceptPoint(event, stateFromContext(singleContext)))
        assertTrue(MapTrackPointReducer.shouldAcceptPoint(event, stateFromContext(groupContext)))
        assertTrue(MapTrackPointReducer.shouldAcceptPoint(event, stateFromContext(allContext)))
    }
}
