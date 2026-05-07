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
 *  1. Rejected fixes must not mutate the metrics ring buffer or anchor
 *     accumulators.
 *  2. The Kalman filter must only see committed measurements, never
 *     rejected ones.
 *  3. The ring buffer's stored displacement must reflect the *committed*
 *     polyline (0 for adjust-to-anchor, the cap for clip), so a noisy
 *     standstill does not poison the burst window that subsequent
 *     decisions are made against.
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
        assertEquals(LocationFilterResult.Decision.Rejected, rejection.decision)
        assertEquals(anchorAfterFirst, filter.lastAcceptedLatLon)
    }

    @Test
    fun rejectedFix_doesNotPollutePostRejectionDecisions() {
        // Establish a stable slow-walk baseline.
        val filter = LocationFilter(LocationFilterConfig.Default)
        var lat = 0.0
        var ts = 0L
        repeat(8) {
            ts += 1_000L
            lat += 0.000005
            filter.evaluate(
                LocationInput(
                    latitude = lat,
                    longitude = 0.0,
                    timestampMs = ts,
                    accuracyMeters = 5f,
                    speedMps = 0.6f,
                    bearingDegrees = 0f,
                )
            )
        }

        // Inject a clear teleport (will reject under Conservative).
        ts += 1_000L
        val teleport = filter.evaluate(
            LocationInput(
                latitude = 5.0,
                longitude = 5.0,
                timestampMs = ts,
                accuracyMeters = 5f,
                speedMps = 0.6f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Rejected, teleport.decision)

        // The next legitimate slow-walk fix must still be accepted.
        ts += 1_000L
        lat += 0.000005
        val recovery = filter.evaluate(
            LocationInput(
                latitude = lat,
                longitude = 0.0,
                timestampMs = ts,
                accuracyMeters = 5f,
                speedMps = 0.6f,
                bearingDegrees = 0f,
            )
        )
        assertEquals(LocationFilterResult.Decision.Accepted, recovery.decision)
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
            if (r.decision == LocationFilterResult.Decision.Rejected) rejections++
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
    fun applyConfig_resetsAllInternalState() {
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
            LocationFilterConfig.Default.copy(policy = LocationFilterPolicy.Adjust)
        )
        assertEquals(null, filter.lastAcceptedTimestampMs)
        assertEquals(null, filter.lastAcceptedLatLon)
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
        assertEquals(LocationFilterResult.Decision.Accepted, r.decision)
        assertEquals("first-fix", r.reason)
    }
}
