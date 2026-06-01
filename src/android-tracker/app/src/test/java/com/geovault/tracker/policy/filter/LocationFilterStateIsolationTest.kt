package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the internal-state-pollution class of bugs that
 * the rubber-banding fix exposed. These properties are why the filter can
 * recover from a noisy standstill cluster instead of letting the jitter
 * permanently inflate its rolling baseline:
 *
 *  1. Rejected fixes must not mutate the metrics ring buffer, the
 *     `previousAccepted` snap target, or the Kalman state.
 *  2. The ring buffer's stored displacement must reflect the *committed*
 *     polyline (0 for adjust-to-anchor, the cap for clip), so a noisy
 *     standstill does not poison the burst window that subsequent
 *     decisions are made against.
 *
 * Note: post-Phase-3 a rejected fix *does* advance `lastSeenFix` (so the
 * metrics engine sees per-frame deltas instead of an unbounded stale-
 * anchor delta on consecutive rejects). A single wild teleport therefore
 * disturbs at most two frames of decisions; this is the deliberate
 * tradeoff that eliminates the multi-minute driving stall.
 */
class LocationFilterStateIsolationTest {

    @Test
    fun rejectedFix_doesNotAdvanceAnchor() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        val firstFix = LocationInput(
            latitude = 0.0,
            longitude = 0.0,
            timestampMs = 0L,
            accuracyMeters = 5f,
            speedMps = 1f,
        )
        filter.evaluate(firstFix)
        val anchorAfterFirst = filter.lastAcceptedLatLon

        val teleport = LocationInput(
            latitude = 1.0,
            longitude = 1.0,
            timestampMs = 1_000L,
            accuracyMeters = 5f,
            speedMps = 1f,
        )
        val rejection = filter.evaluate(teleport)
        assertEquals(LocationFilterResult.Decision.Reject, rejection.decision)
        assertEquals(anchorAfterFirst, filter.lastAcceptedLatLon)
    }

    @Test
    fun rejectedFix_doesNotPollutePostRejectionDecisions() {
        // Establish a stable brisk-walk baseline (above the standstill
        // motion floor, so the filter commits each fix verbatim and we
        // have a real `previousAccepted` to compare against).
        val filter = LocationFilter(LocationFilterConfig.Default)
        var lat = 0.0
        var ts = 0L
        repeat(8) {
            ts += 1_000L
            lat += 0.00006 // ~6.7 m at the equator
            filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = 0.0,
                    timestampMs = ts,
                    accuracyMeters = 3f,
                    speedMps = 6.7f,
                    bearingDegrees = 0f,
                )
            )
        }

        // Inject a clear teleport (rejected under Conservative).
        ts += 1_000L
        val teleport = filter.evaluate(
            LocationInput(
                latitude = 5.0,
                longitude = 5.0,
                timestampMs = ts,
                accuracyMeters = 3f,
                speedMps = 6.7f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Reject, teleport.decision)

        // First post-teleport fix is still measured against the bad
        // lastSeenFix (the teleport position) so it is rejected too --
        // this is the deliberate one-frame extra cost that buys
        // immunity against multi-minute consecutive-reject stalls.
        ts += 1_000L
        lat += 0.00006
        val firstAfter = filter.evaluate(
            LocationInput(
                latitude = lat,
                longitude = 0.0,
                timestampMs = ts,
                accuracyMeters = 3f,
                speedMps = 6.7f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Reject, firstAfter.decision)

        // Second post-teleport fix sees lastSeenFix back at the real
        // path; metrics are clean and the fix accepts. The accepted
        // anchor (`previousAccepted`) was never advanced through the
        // teleport, so the snap-to-anchor invariant still holds.
        ts += 1_000L
        lat += 0.00006
        val recovery = filter.evaluate(
            LocationInput(
                latitude = lat,
                longitude = 0.0,
                timestampMs = ts,
                accuracyMeters = 3f,
                speedMps = 6.7f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Commit, recovery.decision)
    }

    @Test
    fun standstillJitter_doesNotPoisonBurstWindowForFollowOnDrive() {
        // 12 fixes of GPS-reported standstill (speed=0) at a lat/lon that
        // wanders by the chipset's noise envelope. The conservative policy
        // adjusts these to anchor (committed displacement = 0).
        val filter = LocationFilter(LocationFilterConfig.Default)
        var ts = 0L
        val baseLat = 24.7097
        val baseLon = -81.1011
        repeat(12) { idx ->
            ts += 1_000L
            val noiseLat = baseLat + (if (idx % 2 == 0) 5e-5 else -5e-5)
            val noiseLon = baseLon - (if (idx % 2 == 0) 5e-5 else -5e-5)
            filter.evaluate(
                LocationInput(
                    latitude = noiseLat,
                    longitude = noiseLon,
                    timestampMs = ts,
                    accuracyMeters = 55f,
                    speedMps = 0f,
                    bearingDegrees = (idx * 75).toFloat() % 360f,
                )
            )
        }

        // Now drive arrives. The burst window should be CLEAN -- no
        // accumulated 30 m/sample noise should be carried forward, even
        // though raw haversine of those standstill fixes was nontrivial.
        var driveLat = baseLat
        var driveLon = baseLon
        var rejections = 0
        repeat(6) {
            ts += 1_000L
            driveLat += 0.000150
            driveLon += 0.000050
            val r = filter.evaluate(
                LocationInput(
                    latitude = driveLat,
                    longitude = driveLon,
                    timestampMs = ts,
                    accuracyMeters = 5f,
                    speedMps = 18f,
                    bearingDegrees = 45f,
                )
            )
            if (r.decision == LocationFilterResult.Decision.Reject) rejections++
        }
        assertEquals("standstill noise must not poison the burst window", 0, rejections)
    }

    @Test
    fun adjustedToAnchor_recordsZeroCommittedDisplacement() {
        // Drive the metrics engine through a noisy-standstill that gets
        // suppressed to anchor. The rolling-step (drawn from committed
        // displacement) must stay near the floor, NOT inflate to the
        // raw haversine of the suppressed motion.
        val engine = LocationMetricsEngine()
        var ts = 0L
        var previous: LocationInput? = null
        repeat(5) { idx ->
            val sample = LocationInput(
                latitude = 0.0 + if (idx % 2 == 0) 5e-5 else -5e-5,
                longitude = 0.0,
                timestampMs = ts,
                accuracyMeters = 50f,
                speedMps = 0f,
            )
            val metrics = engine.compute(current = sample, previous = previous)
            // Simulate the production "adjust to anchor" commit path:
            // committed displacement is 0 even though raw haversine is large.
            engine.commit(
                current = sample,
                metrics = metrics,
                committedDisplacementMeters = 0.0,
            )
            previous = sample
            ts += 1_000L
        }

        // Now an in-policy slow walk fix should observe a small rollingCap,
        // because the "history" the engine remembers is anchor stillness.
        val nextSample = LocationInput(
            latitude = 6e-5,
            longitude = 0.0,
            timestampMs = ts,
            accuracyMeters = 6f,
            speedMps = 0.6f,
        )
        val freshMetrics = engine.compute(current = nextSample, previous = previous)
        assertTrue(
            "rolling cap must not inflate from suppressed standstill jitter," +
                " observed ${freshMetrics.rollingCap}",
            freshMetrics.rollingCap < 25.0,
        )
    }

    @Test
    fun applyConfig_changingPhysicsField_preservesCommittedAnchor() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 1.0,
                longitude = 1.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
            )
        )
        assertEquals(0L, filter.lastAcceptedTimestampMs)
        val anchorLatLon = filter.lastAcceptedLatLon

        filter.applyConfig(
            LocationFilterConfig.Default.copy(policy = LocationFilterPolicy.Adjust)
        )
        assertEquals(0L, filter.lastAcceptedTimestampMs)
        assertEquals(anchorLatLon, filter.lastAcceptedLatLon)
    }

    @Test
    fun applyConfig_changingOnlyAccuracyThreshold_preservesAnchor() {
        // The motion profile flipping (Walking <-> Biking <-> Driving)
        // historically swapped trackingAccuracyThresholdMeters on every
        // fix, which used to wipe the filter's anchor and accept the
        // next fix as `first-fix` verbatim. Selective applyConfig must
        // treat the gate as a live-swap.
        val filter = LocationFilter(LocationFilterConfig.Default)
        val anchor = LocationInput(
            latitude = 0.0,
            longitude = 0.0,
            timestampMs = 0L,
            accuracyMeters = 10f,
            speedMps = 1f,
        )
        filter.evaluate(anchor)
        val anchorTs = filter.lastAcceptedTimestampMs
        val anchorLatLon = filter.lastAcceptedLatLon
        assertEquals(0L, anchorTs)

        filter.applyConfig(
            LocationFilterConfig.Default.copy(trackingAccuracyThresholdMeters = 50.0)
        )
        // Anchor MUST survive the gate-only update.
        assertEquals(anchorTs, filter.lastAcceptedTimestampMs)
        assertEquals(anchorLatLon, filter.lastAcceptedLatLon)

        // A 200 m teleport at 1 s would have been first-fix-accepted
        // pre-fix; with the anchor preserved it gets rejected.
        val teleport = LocationInput(
            latitude = 0.002,
            longitude = 0.0,
            timestampMs = 1_000L,
            accuracyMeters = 10f,
            speedMps = 1f,
        )
        val result = filter.evaluate(teleport)
        assertEquals(LocationFilterResult.Decision.Reject, result.decision)
    }

    @Test
    fun applyConfig_changingFreshnessTtl_preservesAnchor() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 5.0,
                longitude = 5.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
            )
        )
        val anchorTs = filter.lastAcceptedTimestampMs
        filter.applyConfig(
            LocationFilterConfig.Default.copy(freshnessTtlMs = 5_000L)
        )
        assertEquals(anchorTs, filter.lastAcceptedTimestampMs)
    }

    @Test
    fun applyConfig_changingMaxFutureSkew_preservesAnchor() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 5.0,
                longitude = 5.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
            )
        )
        val anchorTs = filter.lastAcceptedTimestampMs
        filter.applyConfig(
            LocationFilterConfig.Default.copy(maxFutureSkewMs = 999_999L)
        )
        assertEquals(anchorTs, filter.lastAcceptedTimestampMs)
    }

    @Test
    fun applyConfig_changingKalmanProfile_preservesAnchor() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 1.0,
                longitude = 1.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
            )
        )
        assertEquals(0L, filter.lastAcceptedTimestampMs)

        filter.applyConfig(
            LocationFilterConfig.Default.copy(kalmanProfile = KalmanProfile.Conservative)
        )
        assertEquals(0L, filter.lastAcceptedTimestampMs)
    }

    @Test
    fun applyConfig_changingRollingWindowSeconds_preservesAnchor() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 1.0,
                longitude = 1.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
            )
        )
        filter.applyConfig(
            LocationFilterConfig.Default.copy(rollingWindowSeconds = 10.0)
        )
        assertEquals(0L, filter.lastAcceptedTimestampMs)
    }

    @Test
    fun reset_returnsFilterToFreshState() {
        val filter = LocationFilter(LocationFilterConfig.Default)
        filter.evaluate(
            LocationInput(
                latitude = 1.0,
                longitude = 1.0,
                timestampMs = 0L,
                accuracyMeters = 5f,
            )
        )
        filter.reset()
        assertEquals(null, filter.lastAcceptedTimestampMs)

        // After reset the next fix must be accepted as the new first-fix.
        val r = filter.evaluate(
            LocationInput(
                latitude = 2.0,
                longitude = 2.0,
                timestampMs = 1_000L,
                accuracyMeters = 5f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Commit, r.decision)
        assertEquals(FilterReason.FIRST_FIX, r.reason)
    }
}
