package com.geovault.tracker.policy

import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.LocationFilterPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that the [TrackPointPolicyEngine] facade keeps per-stream
 * filter state isolated and that mid-session config swaps retune the
 * filter atomically without leaking state across streams.
 */
class TrackPointPolicyEngineStreamIsolationTest {

    private val baseConfig = LocationFilterConfig.Default.copy(
        normalizeSecondsTimestamps = false,
        freshnessTtlMs = 0L,
    )

    @Before
    fun setUp() = TrackPointPolicyEngine.resetAll()

    @After
    fun tearDown() = TrackPointPolicyEngine.resetAll()

    @Test
    fun differentTrackIds_doNotShareAnchorState() {
        // Stream A is a slow walker around lat 10; stream B is the same
        // tracker on a different track ID at lat 30. Stream B's first fix
        // must NOT be classified as a teleport just because stream A is
        // anchored elsewhere.
        TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "A", lat = 10.0, lon = 10.0, ts = 1_000L),
            nowMs = 1_000L,
            config = baseConfig,
        )
        val streamBFirst = TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "B", lat = 30.0, lon = 30.0, ts = 1_000L),
            nowMs = 1_000L,
            config = baseConfig,
        )
        assertTrue(
            "stream B's first fix must be accepted regardless of stream A's anchor",
            streamBFirst.accepted,
        )
    }

    @Test
    fun differentSources_doNotShareAnchorStateForSameTrackId() {
        val track = "shared-id"
        TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = track,
                lon = 10.0,
                lat = 10.0,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
            ),
            nowMs = 1_000L,
            config = baseConfig,
        )
        val remoteFirst = TrackPointPolicyEngine.evaluate(
            event = TrackPointEvent(
                source = TrackPointSource.REMOTE_STREAM,
                trackId = track,
                lon = 30.0,
                lat = 30.0,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
            ),
            nowMs = 1_000L,
            config = baseConfig,
        )
        assertTrue(
            "remote stream must not inherit local stream's anchor for the same track id",
            remoteFirst.accepted,
        )
    }

    @Test
    fun configSwap_retunesActiveFilterForSubsequentDecisions() {
        // Seed an anchor under Conservative.
        val track = "config-swap"
        TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = track, lat = 0.0, lon = 0.0, ts = 1_000L),
            nowMs = 1_000L,
            config = baseConfig,
        )

        // Now request the same stream under PassThrough. The engine must
        // adopt the new policy; a teleport that would have been rejected
        // under Conservative must now sail through.
        val passThroughConfig = baseConfig.copy(policy = LocationFilterPolicy.PassThrough)
        val r = TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = track, lat = 1.0, lon = 1.0, ts = 2_000L),
            nowMs = 2_000L,
            config = passThroughConfig,
        )
        assertTrue("policy swap to PassThrough must allow the teleport", r.accepted)
    }

    @Test
    fun resetAll_clearsAllStreams() {
        TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "X", lat = 10.0, lon = 10.0, ts = 1_000L),
            nowMs = 1_000L,
            config = baseConfig,
        )
        TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "Y", lat = 20.0, lon = 20.0, ts = 1_000L),
            nowMs = 1_000L,
            config = baseConfig,
        )
        TrackPointPolicyEngine.resetAll()

        // Both streams behave as fresh first-fixes.
        val xAccept = TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "X", lat = 10.0, lon = 10.0, ts = 500L),
            nowMs = 1_000L,
            config = baseConfig,
        )
        val yAccept = TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "Y", lat = 20.0, lon = 20.0, ts = 500L),
            nowMs = 1_000L,
            config = baseConfig,
        )
        assertTrue(xAccept.accepted)
        assertTrue(yAccept.accepted)
    }

    @Test
    fun resetStream_doesNotAffectSiblingStreams() {
        TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "keep", lat = 10.0, lon = 10.0, ts = 1_000L),
            nowMs = 1_000L,
            config = baseConfig,
        )
        TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "drop", lat = 20.0, lon = 20.0, ts = 1_000L),
            nowMs = 1_000L,
            config = baseConfig,
        )

        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, "drop")

        // Sibling stream should still see the earlier-ts as out-of-order
        // because its anchor remains intact.
        val keepRejection = TrackPointPolicyEngine.evaluate(
            event = makeEvent(track = "keep", lat = 10.0, lon = 10.0, ts = 500L),
            nowMs = 1_000L,
            config = baseConfig,
        )
        assertEquals(TrackPointRejectReason.OUT_OF_ORDER, keepRejection.rejectReason)
        assertNotEquals(true, keepRejection.accepted)
    }

    private fun makeEvent(track: String, lat: Double, lon: Double, ts: Long): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = track,
            lon = lon,
            lat = lat,
            timestampMs = ts,
            accuracyMeters = 5f,
        )
    }
}
