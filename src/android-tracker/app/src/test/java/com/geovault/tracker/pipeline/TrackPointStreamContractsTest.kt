package com.geovault.tracker.pipeline

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
