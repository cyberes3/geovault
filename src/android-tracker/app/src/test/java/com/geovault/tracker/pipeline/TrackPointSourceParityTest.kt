package com.geovault.tracker.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPointSourceParityTest {
    @Test
    fun shouldAcceptForParams_trackingLocalTracker_acceptsLocalOnly() {
        val local = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t1",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1_000L
        )
        val remote = local.copy(source = TrackPointSource.REMOTE_STREAM)
        assertTrue(
            TrackPointSourceResolver.shouldAcceptForParams(
                event = local,
                trackerId = "t1",
                trackingRunning = true,
                selectedTrackerId = "t1"
            )
        )
        assertFalse(
            TrackPointSourceResolver.shouldAcceptForParams(
                event = remote,
                trackerId = "t1",
                trackingRunning = true,
                selectedTrackerId = "t1"
            )
        )
    }

    @Test
    fun shouldAcceptForParams_nonTracking_acceptsRemoteOnly() {
        val local = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t2",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1_000L
        )
        val remote = local.copy(source = TrackPointSource.REMOTE_STREAM)
        assertFalse(
            TrackPointSourceResolver.shouldAcceptForParams(
                event = local,
                trackerId = "t2",
                trackingRunning = false,
                selectedTrackerId = "t1"
            )
        )
        assertTrue(
            TrackPointSourceResolver.shouldAcceptForParams(
                event = remote,
                trackerId = "t2",
                trackingRunning = false,
                selectedTrackerId = "t1"
            )
        )
    }
}
