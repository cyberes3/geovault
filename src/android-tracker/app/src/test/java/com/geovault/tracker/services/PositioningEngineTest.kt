package com.geovault.tracker.services

import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositioningEngineTest {

    @Test
    fun evaluate_recordsRecentAcceptedAndRejectedDecisions() {
        val engine = PositioningEngine()
        val config = LocationFilterConfig.Default.copy(
            trackingAccuracyThresholdMeters = 25.0,
            normalizeSecondsTimestamps = false,
        )

        val accepted = engine.evaluate(
            trackId = "tracker-1",
            event = event(time = 100_000L, accuracy = 5f),
            nowMs = 100_000L,
            nowElapsedRealtimeNanos = 100_000_000L,
            config = config,
        )
        val rejected = engine.evaluate(
            trackId = "tracker-1",
            event = event(time = 101_000L, accuracy = 80f),
            nowMs = 101_000L,
            nowElapsedRealtimeNanos = 101_000_000L,
            config = config,
        )

        assertTrue(accepted.accepted)
        assertFalse(rejected.accepted)
        assertEquals(TrackPointRejectReason.BAD_ACCURACY, rejected.rejectReason)
        val trace = engine.recentDecisionTrace()
        assertEquals(2, trace.size)
        assertEquals(listOf(true, false), trace.map { it.decision.accepted })
        assertEquals("tracker-1", trace.last().trackId)
    }

    private fun event(time: Long, accuracy: Float): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "tracker-1",
            lon = -105.0 + (time / 1_000_000.0),
            lat = 39.0 + (time / 1_000_000.0),
            timestampMs = time,
            accuracyMeters = accuracy,
            elapsedRealtimeNanos = time * 1_000L,
        )
    }
}
