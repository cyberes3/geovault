package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class LocationMetricsEngineTest {

    /** Mirrors the production "compute + commit" pattern for an accepted fix. */
    private fun LocationMetricsEngine.nextAccepted(
        current: LocationInput,
        previous: LocationInput?,
    ): LocationMetrics {
        val metrics = compute(current = current, previous = previous)
        commit(
            current = current,
            metrics = metrics,
            committedDisplacementMeters = metrics.rawDistanceMeters,
        )
        return metrics
    }


    @Test
    fun firstFix_producesZeroDistanceMetrics() {
        val engine = LocationMetricsEngine()
        val metrics = engine.nextAccepted(
            current = LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = 0L,
                accuracyMeters = 5f,
            ),
            previous = null,
        )
        assertEquals(0.0, metrics.rawDistanceMeters, 1e-9)
        assertEquals(0.0, metrics.effectiveDistanceMeters, 1e-9)
        assertEquals(0.0, metrics.dtSeconds, 1e-9)
    }

    @Test
    fun rssEffectiveDistance_combinesAccuracyAsRootSumSquare() {
        val engine = LocationMetricsEngine()
        val previous = LocationInput(
            latitude = 24.7097,
            longitude = -81.1011,
            timestampMs = 0L,
            accuracyMeters = 30f,
        )
        engine.nextAccepted(current = previous, previous = null)
        val current = LocationInput(
            latitude = 24.70972,
            longitude = -81.1011,
            timestampMs = 1_000L,
            accuracyMeters = 40f,
        )
        val metrics = engine.nextAccepted(current = current, previous = previous)
        val expectedRss = sqrt(30.0 * 30.0 + 40.0 * 40.0)
        val expectedEffective = (metrics.rawDistanceMeters - expectedRss).coerceAtLeast(0.0)
        assertEquals(expectedEffective, metrics.effectiveDistanceMeters, 0.05)
    }

    @Test
    fun accCap_isMaxAccuracyTimesThree_notSum() {
        val engine = LocationMetricsEngine()
        val previous = LocationInput(
            latitude = 24.7097,
            longitude = -81.1011,
            timestampMs = 0L,
            accuracyMeters = 30f,
        )
        engine.nextAccepted(current = previous, previous = null)
        val current = LocationInput(
            latitude = 24.70972,
            longitude = -81.1011,
            timestampMs = 1_000L,
            accuracyMeters = 50f,
        )
        val metrics = engine.nextAccepted(current = current, previous = previous)
        assertEquals(150.0, metrics.accCap, 1e-6)
    }

    @Test
    fun kinCap_isDrivenByGpsSpeed_notAccuracy() {
        val engine = LocationMetricsEngine()
        val previous = LocationInput(
            latitude = 24.7097,
            longitude = -81.1011,
            timestampMs = 0L,
            accuracyMeters = 65f,
            speedMps = 0f,
        )
        engine.nextAccepted(current = previous, previous = null)
        val current = LocationInput(
            latitude = 24.70972,
            longitude = -81.1011,
            timestampMs = 1_000L,
            accuracyMeters = 65f,
            speedMps = 0f,
        )
        val metrics = engine.nextAccepted(current = current, previous = previous)
        assertEquals(0.0, metrics.kinCap, 1e-9)
    }

    @Test
    fun kinCap_scalesWithDtAndMaxSpeed() {
        val engine = LocationMetricsEngine()
        val previous = LocationInput(
            latitude = 24.7097,
            longitude = -81.1011,
            timestampMs = 0L,
            speedMps = 5f,
        )
        engine.nextAccepted(current = previous, previous = null)
        val current = LocationInput(
            latitude = 24.71,
            longitude = -81.10,
            timestampMs = 2_000L,
            speedMps = 10f,
        )
        val metrics = engine.nextAccepted(current = current, previous = previous)
        assertEquals(40.0, metrics.kinCap, 1e-6)
    }

    @Test
    fun capCandidate_isMaxOfFloorAndAllCapsCombined() {
        val engine = LocationMetricsEngine()
        val previous = LocationInput(
            latitude = 24.7097,
            longitude = -81.1011,
            timestampMs = 0L,
            accuracyMeters = 8f,
            speedMps = 0f,
        )
        engine.nextAccepted(current = previous, previous = null)
        val current = LocationInput(
            latitude = 24.7099,
            longitude = -81.1011,
            timestampMs = 1_000L,
            accuracyMeters = 10f,
            speedMps = 0f,
        )
        val metrics = engine.nextAccepted(current = current, previous = previous)
        assertTrue(metrics.capCandidate >= 5.0)
        assertTrue(metrics.capCandidate >= metrics.accCap)
        assertTrue(metrics.capCandidate >= metrics.kinCap)
        assertTrue(metrics.capCandidate >= metrics.rollingCap)
    }

    @Test
    fun standstillScenario_isStationaryDetected_notOscillating() {
        val engine = LocationMetricsEngine()
        var ts = 0L
        var previous: LocationInput? = null
        var lastMetrics: LocationMetrics? = null
        repeat(8) {
            val sample = LocationInput(
                latitude = 24.7097,
                longitude = -81.1011,
                timestampMs = ts,
                accuracyMeters = 6f,
                speedMps = 0f,
                bearingDegrees = 90f,
            )
            lastMetrics = engine.nextAccepted(current = sample, previous = previous)
            previous = sample
            ts += 1_000L
        }
        val metrics = lastMetrics!!
        assertTrue("steady standstill must score as stationary", metrics.isStationary)
        assertFalse("steady standstill must not be flagged as oscillating", metrics.isOscillating)
    }

    @Test
    fun rubberBandPattern_isOscillatingDetected() {
        val engine = LocationMetricsEngine()
        var ts = 0L
        var previous: LocationInput? = null
        val baseLat = 24.7097
        val baseLon = -81.1011
        val offsets = doubleArrayOf(0.0, 5e-5, -5e-5, 6e-5, -6e-5, 4e-5, -4e-5, 7e-5)
        var lastMetrics: LocationMetrics? = null
        offsets.forEachIndexed { idx, off ->
            val bearing = (idx * 75) % 360
            val sample = LocationInput(
                latitude = baseLat + off,
                longitude = baseLon - off,
                timestampMs = ts,
                accuracyMeters = 55f,
                speedMps = 0.2f,
                bearingDegrees = bearing.toFloat(),
            )
            lastMetrics = engine.nextAccepted(current = sample, previous = previous)
            previous = sample
            ts += 1_000L
        }
        val metrics = lastMetrics!!
        assertTrue("oscillating cluster should be classified stationary", metrics.isStationary)
        assertTrue("oscillating cluster should be flagged as oscillating", metrics.isOscillating)
    }

    @Test
    fun anchorTrust_dropsOnRubberBandSignature() {
        val engine = LocationMetricsEngine()
        var ts = 0L
        var previous: LocationInput? = null
        var anchorTrust = 1.0
        repeat(6) {
            val sample = LocationInput(
                latitude = 24.7097 + ((it % 2) * 1e-4),
                longitude = -81.1011 - ((it % 2) * 1e-4),
                timestampMs = ts,
                accuracyMeters = 60f,
                speedMps = 0.1f,
                bearingDegrees = (it * 90).toFloat(),
            )
            anchorTrust = engine.nextAccepted(current = sample, previous = previous).anchorTrust
            previous = sample
            ts += 1_000L
        }
        assertTrue("anchor trust should fall under rubber-banding", anchorTrust < 0.7)
    }
}
