package com.geovault.tracker.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val nowMs = 1_800_000_000_000L
        val stale = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = 1.0,
            lat = 2.0,
            timestampMs = nowMs - 100_000L,
            accuracyMeters = 10f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = stale,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 20_000L,
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
    }

    @Test
    fun processLocalGps_rejectsPointAboveAccuracyThreshold() {
        val nowMs = 1_800_000_000_000L
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = 1.0,
            lat = 2.0,
            timestampMs = nowMs - 1_000L,
            accuracyMeters = 70f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.BAD_ACCURACY, decision.rejectReason)
    }

    @Test
    fun processLocalGps_rejectsPointWithoutAccuracy() {
        val nowMs = 1_800_000_000_000L
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "local",
            lon = 1.0,
            lat = 2.0,
            timestampMs = nowMs - 1_000L,
            accuracyMeters = null
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.BAD_ACCURACY, decision.rejectReason)
    }

    @Test
    fun process_rejectsOlderPointAcrossSourcesForSameTrack() {
        val nowMs = 1_800_000_000_000L
        val newerLocal = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "shared-track",
            lon = -104.8,
            lat = 38.9,
            timestampMs = nowMs - 10_000L
        )
        val olderRemote = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "shared-track",
            lon = -104.7,
            lat = 38.8,
            timestampMs = nowMs - 20_000L
        )

        val accepted = TrackPointPipeline.process(newerLocal, nowMs = nowMs)
        val rejected = TrackPointPipeline.process(olderRemote, nowMs = nowMs)

        assertTrue(accepted.accepted)
        assertFalse(rejected.accepted)
        assertEquals(TrackPointRejectReason.OUT_OF_ORDER, rejected.rejectReason)
    }

    @Test
    fun resetLocalSession_allowsNewSessionPointAfterJump() {
        val trackId = "session-reset"
        val nowMs = 1_800_000_000_000L

        val oldPoint = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -104.8,
            lat = 38.9,
            timestampMs = nowMs - 5_000L,
            accuracyMeters = 5f
        )
        val oldDecision = TrackPointPipeline.processLocalGps(
            event = oldPoint,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = nowMs
        )
        assertTrue(oldDecision.accepted)

        val newSessionNowMs = nowMs + 2_000L
        val farAwayPoint = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = -80.0,
            lat = 40.0,
            timestampMs = newSessionNowMs - 1_000L,
            accuracyMeters = 5f
        )

        val withoutReset = TrackPointPipeline.processLocalGps(
            event = farAwayPoint,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = newSessionNowMs
        )
        assertFalse("Should reject as JUMP without session reset", withoutReset.accepted)
        assertEquals(TrackPointRejectReason.JUMP, withoutReset.rejectReason)

        TrackPointPipeline.resetLocalSession(trackId)

        val afterReset = TrackPointPipeline.processLocalGps(
            event = farAwayPoint,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = newSessionNowMs
        )
        assertTrue("Should accept after session reset", afterReset.accepted)
    }

    @Test
    fun processLocalGps_mockTimestampSkew_isCanonicalizedToNow() {
        val nowMs = 1_800_000_000_000L
        val staleMockTs = nowMs - (2 * 60 * 60 * 1000L)
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "mock-skew",
            lon = -104.8,
            lat = 38.9,
            timestampMs = staleMockTs,
            accuracyMeters = 5f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            isMockLocation = true,
            nowMs = nowMs
        )
        assertTrue(decision.accepted)
        assertEquals(nowMs, decision.canonicalEvent?.timestampMs)
    }

    @Test
    fun processLocalGps_realTimestampSkew_rejectsAsStale() {
        val nowMs = 1_800_000_000_000L
        val staleRealTs = nowMs - (2 * 60 * 60 * 1000L)
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "real-skew",
            lon = -104.8,
            lat = 38.9,
            timestampMs = staleRealTs,
            accuracyMeters = 5f
        )
        val decision = TrackPointPipeline.processLocalGps(
            event = event,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            isMockLocation = false,
            nowMs = nowMs
        )
        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
    }

    @Test
    fun processLocalGps_mockBypassesJumpFilterButRealDoesNot() {
        val baseNowMs = 1_800_000_000_000L
        val previous = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "jump-mode",
            lon = -104.8,
            lat = 38.9,
            timestampMs = baseNowMs - 2_000L,
            accuracyMeters = 5f
        )
        val previousDecision = TrackPointPipeline.processLocalGps(
            event = previous,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = baseNowMs
        )
        assertTrue(previousDecision.accepted)

        val farAway = previous.copy(
            lon = -104.72,
            lat = 38.92,
            timestampMs = baseNowMs - 1_000L
        )
        val realDecision = TrackPointPipeline.processLocalGps(
            event = farAway,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            isMockLocation = false,
            nowMs = baseNowMs
        )
        assertFalse(realDecision.accepted)
        assertEquals(TrackPointRejectReason.JUMP, realDecision.rejectReason)

        TrackPointPipeline.resetLocalSession("jump-mode")
        val previousAgain = TrackPointPipeline.processLocalGps(
            event = previous,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            nowMs = baseNowMs
        )
        assertTrue(previousAgain.accepted)

        val mockDecision = TrackPointPipeline.processLocalGps(
            event = farAway,
            maxAccuracyMeters = 50f,
            freshnessTtlMs = 120_000L,
            isMockLocation = true,
            nowMs = baseNowMs
        )
        assertTrue(mockDecision.accepted)
    }

    @Test
    fun process_rejectsRemotePointOutsideFreshnessWindow() {
        val nowMs = 1_800_000_000_000L
        val staleRemote = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "remote-stale",
            lon = -104.8,
            lat = 38.9,
            timestampMs = nowMs - (31L * 60L * 1000L)
        )

        val decision = TrackPointPipeline.process(staleRemote, nowMs = nowMs)

        assertFalse(decision.accepted)
        assertEquals(TrackPointRejectReason.STALE, decision.rejectReason)
    }
}
