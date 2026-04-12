package com.geovault.tracker.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RemoteStreamIngressPolicyTest {

    @Before
    fun setUp() {
        RemoteStreamIngressPolicy.resetForTests()
    }

    @Test
    fun process_acceptsFreshRemotePoint() {
        val now = 1_700_000_000_000L
        val accepted = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "t1", timestampMs = now - 1_000L, lon = 10.0, lat = 20.0),
            nowMs = now
        )
        assertNotNull(accepted)
        assertEquals("t1", accepted!!.trackId)
        assertEquals(true, accepted.orderingKey > 0L)
    }

    @Test
    fun process_rejectsStaleRemotePoint() {
        val now = 1_700_000_000_000L
        val stale = RemoteStreamIngressPolicy.process(
            event = remoteEvent(
                trackId = "t1",
                timestampMs = now - (31L * 60L * 1000L),
                lon = 10.0,
                lat = 20.0
            ),
            nowMs = now
        )
        assertNull(stale)
    }

    @Test
    fun process_rejectsOutOfOrderForSameTrack() {
        val now = 1_700_000_000_000L
        val first = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "t1", timestampMs = now - 1_000L, lon = 10.0, lat = 20.0),
            nowMs = now
        )
        val second = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "t1", timestampMs = now - 2_000L, lon = 10.1, lat = 20.1),
            nowMs = now
        )
        assertNotNull(first)
        assertNull(second)
    }

    @Test
    fun process_usesRemoteStreamPrevious_notCrossSourcePrevious_forPolicy() {
        val now = 1_700_000_000_000L
        TrackPointCrossSourceState.update(
            trackId = "t1",
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "t1",
                lon = -120.0,
                lat = 60.0,
                timestampMs = now - 10_000L,
                accuracyMeters = 5f
            )
        )

        val accepted = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "t1", timestampMs = now - 1_000L, lon = 10.0, lat = 20.0),
            nowMs = now
        )

        assertNotNull(accepted)
    }

    private fun remoteEvent(trackId: String, timestampMs: Long, lon: Double, lat: Double): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = trackId,
            lon = lon,
            lat = lat,
            timestampMs = timestampMs,
            accuracyMeters = 10f
        )
    }
}
