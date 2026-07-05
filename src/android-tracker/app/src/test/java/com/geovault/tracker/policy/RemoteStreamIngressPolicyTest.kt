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

    @Test
    fun updateSubscribedTracks_evictsRemovedTrackStateWithoutResettingRetainedTracks() {
        val now = 1_700_000_000_000L
        RemoteStreamIngressPolicy.updateSubscribedTracks(listOf("A", "B"))
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

        RemoteStreamIngressPolicy.updateSubscribedTracks(listOf("B", "C"))
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
    fun startSubscriptionSession_resetsCurrentSubscribedTrackState() {
        val now = 1_700_000_000_000L
        RemoteStreamIngressPolicy.updateSubscribedTracks(listOf("B"))
        assertNotNull(
            RemoteStreamIngressPolicy.process(
                event = remoteEvent(trackId = "B", timestampMs = now - 1_000L, lon = 30.0, lat = 40.0),
                nowMs = now
            )
        )

        RemoteStreamIngressPolicy.startSubscriptionSession(listOf("B"))
        val acceptedAfterSocketReset = RemoteStreamIngressPolicy.process(
            event = remoteEvent(trackId = "B", timestampMs = now - 2_000L, lon = 30.1, lat = 40.1),
            nowMs = now
        )

        assertNotNull(acceptedAfterSocketReset)
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
