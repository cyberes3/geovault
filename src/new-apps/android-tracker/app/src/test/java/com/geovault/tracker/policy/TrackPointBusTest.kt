package com.geovault.tracker.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TrackPointBusTest {

    @Before
    fun setUp() {
        TrackPointBus.resetForTests()
    }

    @Test
    fun publish_invalidCoordinates_isDropped() {
        TrackPointBus.publish(
            TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "t",
                lon = 500.0,
                lat = 0.0,
                timestampMs = 1_000L
            )
        )
        val diagnostics = TrackPointBus.diagnostics()
        assertEquals(0, diagnostics.pausedBufferSize)
        assertFalse(diagnostics.isLocalDeliveryPaused)
    }

    @Test
    fun pausedLocalDelivery_buffersAndReportsDiagnostics() {
        TrackPointBus.pauseLocalDelivery()
        TrackPointBus.publish(
            TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = "t",
                lon = 10.0,
                lat = 10.0,
                timestampMs = 1_000L
            )
        )
        val diagnostics = TrackPointBus.diagnostics()
        assertTrue(diagnostics.isLocalDeliveryPaused)
        assertEquals(1, diagnostics.pausedBufferSize)
    }

    @Test
    fun pausedLocalDelivery_overflowTracksDroppedCount() {
        TrackPointBus.pauseLocalDelivery()
        repeat(600) { index ->
            TrackPointBus.publish(
                TrackPointEvent(
                    source = TrackPointSource.LOCAL_GPS,
                    trackId = "t",
                    lon = 10.0 + (index * 0.00001),
                    lat = 10.0,
                    timestampMs = 1_000L + index
                )
            )
        }
        val diagnostics = TrackPointBus.diagnostics()
        assertEquals(512, diagnostics.pausedBufferSize)
        assertTrue(diagnostics.droppedPausedLocalEvents > 0L)
    }

    @Test
    fun publish_remoteWithoutOrderingKey_appliesIngressOrdering() = runBlocking {
        RemoteStreamIngressPolicy.resetForTests()
        val awaitEvent = async {
            withTimeout(2_000L) {
                TrackPointBus.remoteStreamEvents.first()
            }
        }
        TrackPointBus.publish(
            TrackPointEvent(
                source = TrackPointSource.REMOTE_STREAM,
                trackId = "remote-1",
                lon = 20.0,
                lat = 10.0,
                timestampMs = System.currentTimeMillis(),
                orderingKey = 0L
            )
        )
        val event = awaitEvent.await()
        assertTrue(event.orderingKey > 0L)
    }
}
