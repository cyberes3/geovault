package com.geovault.tracker.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerParamsPointAcceptancePolicyTest {

    @Test
    fun trackingLocalTracker_acceptsLocalOnly() {
        val local = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t1",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1_000L,
        )
        val remote = local.copy(source = TrackPointSource.REMOTE_STREAM)
        assertTrue(
            TrackerParamsPointAcceptancePolicy.shouldAcceptForParams(
                event = local,
                trackerId = "t1",
                trackingRunning = true,
                selectedTrackerId = "t1",
            ),
        )
        assertFalse(
            TrackerParamsPointAcceptancePolicy.shouldAcceptForParams(
                event = remote,
                trackerId = "t1",
                trackingRunning = true,
                selectedTrackerId = "t1",
            ),
        )
    }

    @Test
    fun nonTracking_acceptsRemoteOnly() {
        val local = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t2",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1_000L,
        )
        val remote = local.copy(source = TrackPointSource.REMOTE_STREAM)
        assertFalse(
            TrackerParamsPointAcceptancePolicy.shouldAcceptForParams(
                event = local,
                trackerId = "t2",
                trackingRunning = false,
                selectedTrackerId = "t1",
            ),
        )
        assertTrue(
            TrackerParamsPointAcceptancePolicy.shouldAcceptForParams(
                event = remote,
                trackerId = "t2",
                trackingRunning = false,
                selectedTrackerId = "t1",
            ),
        )
    }

    @Test
    fun wrongTracker_rejected() {
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "other",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1L,
        )
        assertFalse(
            TrackerParamsPointAcceptancePolicy.shouldAcceptForParams(
                event = event,
                trackerId = "wanted",
                trackingRunning = false,
                selectedTrackerId = "",
            ),
        )
    }
}
