package com.geovault.tracker.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
