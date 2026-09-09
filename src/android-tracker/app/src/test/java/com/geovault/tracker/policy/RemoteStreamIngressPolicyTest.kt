package com.geovault.tracker.policy

import com.geovault.tracker.domain.TrackPoint
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
        assertEquals("t1", accepted!!.trackerId)
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
            event = TrackPoint(
                provenance = TrackPointSource.LOCAL_GPS,
                trackerId = "t1",
                longitude = -120.0,
                latitude = 60.0,
                timeMs = now - 10_000L,
                accuracyMeters = 5f
            )
        )

        val accepted = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "t1", timestampMs = now - 1_000L, lon = 10.0, lat = 20.0),
            nowMs = now
        )

        assertNotNull(accepted)
    }

    @Test
    fun resetTracks_evictsRemovedTrackStateWithoutResettingRetainedTracks() {
        val now = 1_700_000_000_000L
        assertNotNull(
            RemoteStreamIngressPolicy.process(
                event = remoteEvent(trackId = "A", timestampMs = now - 1_000L, lon = 10.0, lat = 20.0),
                nowMs = now
            )
        )
        assertNotNull(
            RemoteStreamIngressPolicy.process(
                event = remoteEvent(trackId = "B", timestampMs = now - 1_000L, lon = 30.0, lat = 40.0),
                nowMs = now
            )
        )

        RemoteStreamIngressPolicy.resetTracks(listOf("A"))
        val acceptedAfterEviction = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "A", timestampMs = now - 2_000L, lon = 10.1, lat = 20.1),
            nowMs = now
        )
        val retainedOutOfOrder = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "B", timestampMs = now - 2_000L, lon = 30.1, lat = 40.1),
            nowMs = now
        )

        assertNotNull(acceptedAfterEviction)
        assertNull(retainedOutOfOrder)
    }

    @Test
    fun process_reconnectCatchupBacklog_isAcceptedWithinGraceWindow() {
        // RECONNECT-CATCHUP-BACKLOG: a backlog replayed right after (re)connect is, by
        // construction, older than the 30-minute freshness TTL for any stream that was down for a
        // while. Without a grace window this point would be silently dropped as stale.
        val connectedAtMs = 1_700_000_000_000L
        val now = connectedAtMs + 5_000L
        RemoteStreamIngressPolicy.markConnected(connectedAtMs)

        val backlogged = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "t1", timestampMs = now - (45L * 60L * 1000L), lon = 10.0, lat = 20.0),
            nowMs = now,
        )

        assertNotNull(backlogged)
    }

    @Test
    fun process_staleBeyondGraceWindow_isStillRejected() {
        // Steady-state freshness rejection must resume once the grace window elapses — the grace
        // window is only meant to cover the immediate post-reconnect catch-up, not disable
        // staleness detection for the rest of the session.
        val connectedAtMs = 1_700_000_000_000L
        val now = connectedAtMs + (3L * 60L * 1000L)
        RemoteStreamIngressPolicy.markConnected(connectedAtMs)

        val stale = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "t1", timestampMs = now - (45L * 60L * 1000L), lon = 10.0, lat = 20.0),
            nowMs = now,
        )

        assertNull(stale)
    }

    @Test
    fun resetRemoteSession_clearsRemoteTrackState() {
        val now = 1_700_000_000_000L
        assertNotNull(
            RemoteStreamIngressPolicy.process(
                event = remoteEvent(trackId = "B", timestampMs = now - 1_000L, lon = 30.0, lat = 40.0),
                nowMs = now
            )
        )

        RemoteStreamIngressPolicy.resetRemoteSession()
        val acceptedAfterSocketReset = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "B", timestampMs = now - 2_000L, lon = 30.1, lat = 40.1),
            nowMs = now
        )

        assertNotNull(acceptedAfterSocketReset)
    }

    private fun remoteEvent(trackId: String, timestampMs: Long, lon: Double, lat: Double): TrackPoint {
        return TrackPoint(
            provenance = TrackPointSource.REMOTE_STREAM,
            trackerId = trackId,
            longitude = lon,
            latitude = lat,
            timeMs = timestampMs,
            accuracyMeters = 10f
        )
    }
}
