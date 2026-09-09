package com.geovault.tracker.policy

import com.geovault.tracker.domain.TrackPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerParamsPointAcceptancePolicyTest {

    @Test
    fun trackingLocalTracker_acceptsLocalOnly() {
        val local = TrackPoint(
            provenance = TrackPointSource.LOCAL_GPS,
            trackerId = "t1",
            longitude = 1.0,
            latitude = 2.0,
            timeMs = 1_000L,
        )
        val remote = local.copy(provenance = TrackPointSource.REMOTE_STREAM)
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
        val local = TrackPoint(
            provenance = TrackPointSource.LOCAL_GPS,
            trackerId = "t2",
            longitude = 1.0,
            latitude = 2.0,
            timeMs = 1_000L,
        )
        val remote = local.copy(provenance = TrackPointSource.REMOTE_STREAM)
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
        val event = TrackPoint(
            provenance = TrackPointSource.REMOTE_STREAM,
            trackerId = "other",
            longitude = 1.0,
            latitude = 2.0,
            timeMs = 1L,
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
