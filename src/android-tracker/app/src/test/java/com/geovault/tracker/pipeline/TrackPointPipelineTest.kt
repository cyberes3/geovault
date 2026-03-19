package com.geovault.tracker.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackPointPipelineTest {
    @Before
    fun resetPipeline() {
        TrackPointPipeline.resetForTests()
    }

    @Test
    fun process_assignsMonotonicOrderingKeys() {
        val first = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "t1",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1_000L
        )
        val second = first.copy(timestampMs = 2_000L, lon = 1.1)

        val firstDecision = TrackPointPipeline.process(first, nowMs = 2_500_000L)
        val secondDecision = TrackPointPipeline.process(second, nowMs = 2_500_000L)

        assertTrue(firstDecision.accepted)
        assertTrue(secondDecision.accepted)
        assertTrue((secondDecision.canonicalEvent?.orderingKey ?: 0L) > (firstDecision.canonicalEvent?.orderingKey ?: 0L))
    }

    @Test
    fun processLocalGps_rejectsStalePoint() {
        val stale = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 1_000L,
            accuracyMeters = 10f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = stale,
            maxAccuracyMeters = 50f,
            maxJumpSpeedMps = 100.0,
            freshnessTtlMs = 20_000L,
            nowMs = 100_000L
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
    }

    @Test
    fun process_marksPoorAccuracyAsDegradedNotRejectedWithinThreshold() {
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = 1.0,
            lat = 2.0,
            timestampMs = 99_000L,
            accuracyMeters = 70f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            maxJumpSpeedMps = 1000.0,
            freshnessTtlMs = 120_000L,
            nowMs = 100_000L
        )
        assertTrue(decision.accepted)
        assertNotNull(decision.canonicalEvent)
        assertEquals(TrackPointQuality.DEGRADED, decision.canonicalEvent?.quality)
    }
}
