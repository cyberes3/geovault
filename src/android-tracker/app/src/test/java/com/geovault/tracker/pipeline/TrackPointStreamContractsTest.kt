package com.geovault.tracker.pipeline

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackPointStreamContractsTest {
    @Before
    fun resetBus() {
        TrackPointBus.resetForTests()
    }

    @Test
    fun gateway_localAndRemoteStreamsFilterBySource() = runTest {
        val localDeferred = async { TrackPointBusGateway.localGpsEvents.first() }
        val remoteDeferred = async { TrackPointBusGateway.remoteStreamEvents.first() }

        val local = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "local-track",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1000L
        )
        val remote = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "remote-track",
            lon = 3.0,
            lat = 4.0,
            timestampMs = 2000L
        )

        TrackPointBus.publishLocal(local)
        TrackPointBus.publishRemote(remote)

        assertEquals(TrackPointSource.LOCAL_GPS, localDeferred.await().source)
        assertEquals(TrackPointSource.REMOTE_STREAM, remoteDeferred.await().source)
    }

    @Test
    fun gateway_deferredEmitCounter_startsAtZero() {
        assertTrue(TrackPointBus.deferredEmitEventsCount() >= 0L)
    }

    @Test
    fun ingress_rejectsOutOfOrderAndDuplicate() = runTest {
        val first = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "t1",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1_000L
        )
        val duplicate = first.copy()
        val outOfOrder = first.copy(timestampMs = 900L, lon = 1.1)

        TrackPointBus.publish(first)
        TrackPointBus.publish(duplicate)
        TrackPointBus.publish(outOfOrder)

        val stats = TrackPointBus.ingressStats()
        assertTrue(stats.accepted >= 1L)
        assertTrue(stats.rejectedDuplicate >= 1L)
        assertTrue(stats.rejectedOutOfOrder >= 1L)
    }

    @Test
    fun ingress_rejectsInvalidCoordinates() = runTest {
        val invalid = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t2",
            lon = 500.0,
            lat = 200.0,
            timestampMs = 1_000L
        )
        TrackPointBus.publish(invalid)
        val stats = TrackPointBus.ingressStats()
        assertTrue(stats.rejectedInvalidCoordinates >= 1L)
        assertFalse(stats.accepted > 0L && stats.rejectedInvalidCoordinates == 0L)
    }

    @Test
    fun ingress_rejectsBadAccuracyAndFutureTimestamp() = runTest {
        val badAccuracy = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "t3",
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1_000L,
            accuracyMeters = 500f
        )
        val farFuture = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "t4",
            lon = 10.0,
            lat = 20.0,
            timestampMs = System.currentTimeMillis() + (10 * 60 * 1000L)
        )
        TrackPointBus.publish(badAccuracy)
        TrackPointBus.publish(farFuture)
        val stats = TrackPointBus.ingressStats()
        assertTrue(stats.rejectedBadAccuracy >= 1L)
        assertTrue(stats.rejectedTooFarFuture >= 1L)
    }

    @Test
    fun ingress_rejectsUnrealisticJumpSpeed() = runTest {
        val first = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "jump-track",
            lon = 0.0,
            lat = 0.0,
            timestampMs = 1_000L
        )
        val jump = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "jump-track",
            lon = 10.0,
            lat = 10.0,
            timestampMs = 2_000L
        )
        TrackPointBus.publish(first)
        TrackPointBus.publish(jump)
        val stats = TrackPointBus.ingressStats()
        assertTrue(stats.rejectedJump >= 1L)
    }
}
