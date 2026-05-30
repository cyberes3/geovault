package com.geovault.tracker.location

import android.location.Location
import com.geovault.tracker.TrackingLocationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PausedFreshnessPolicyTest {

    @Test
    fun evaluate_goodAccuracyWithinRadiusAndIntervalElapsed_emits() {
        val anchor = location(lat = 47.0, lon = -122.0, accuracy = 6f, timeMs = 0L)
        val candidate = location(lat = 47.0, lon = -122.00001, accuracy = 8f, timeMs = FIVE_MINUTES)

        val decision = PausedFreshnessPolicy.evaluate(
            anchorLocation = anchor,
            candidateLocation = candidate,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
            freshnessIntervalMs = FIVE_MINUTES,
            nowMs = FIVE_MINUTES,
            lastFreshnessPointAtMs = 0L,
        )

        assertTrue(decision.shouldEmit)
        assertEquals(PausedFreshnessDecisionReason.EMIT, decision.reason)
        assertEquals(8f, decision.accuracyMeters)
        assertNull(decision.elapsedSinceLastFreshnessMs)
        assertTrue((decision.distanceMeters ?: Float.MAX_VALUE) < TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS)
    }

    @Test
    fun evaluate_noAnchor_skips() {
        val candidate = location(lat = 47.0, lon = -122.0, accuracy = 8f, timeMs = FIVE_MINUTES)

        val decision = PausedFreshnessPolicy.evaluate(
            anchorLocation = null,
            candidateLocation = candidate,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
            freshnessIntervalMs = FIVE_MINUTES,
            nowMs = FIVE_MINUTES,
            lastFreshnessPointAtMs = 0L,
        )

        assertFalse(decision.shouldEmit)
        assertEquals(PausedFreshnessDecisionReason.NO_ANCHOR, decision.reason)
    }

    @Test
    fun evaluate_poorAccuracy_skipsWithDiagnostics() {
        val anchor = location(lat = 47.0, lon = -122.0, accuracy = 6f, timeMs = 0L)
        val candidate = location(lat = 47.0, lon = -122.00001, accuracy = 60f, timeMs = FIVE_MINUTES)

        val decision = PausedFreshnessPolicy.evaluate(
            anchorLocation = anchor,
            candidateLocation = candidate,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
            freshnessIntervalMs = FIVE_MINUTES,
            nowMs = FIVE_MINUTES,
            lastFreshnessPointAtMs = 0L,
        )

        assertFalse(decision.shouldEmit)
        assertEquals(PausedFreshnessDecisionReason.POOR_ACCURACY, decision.reason)
        assertEquals(60f, decision.accuracyMeters)
        assertTrue((decision.distanceMeters ?: Float.MAX_VALUE) < TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS)
    }

    @Test
    fun evaluate_movedBeyondRadius_skips() {
        val anchor = location(lat = 47.0, lon = -122.0, accuracy = 6f, timeMs = 0L)
        val candidate = location(lat = 47.0, lon = -121.998, accuracy = 8f, timeMs = FIVE_MINUTES)

        val decision = PausedFreshnessPolicy.evaluate(
            anchorLocation = anchor,
            candidateLocation = candidate,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
            freshnessIntervalMs = FIVE_MINUTES,
            nowMs = FIVE_MINUTES,
            lastFreshnessPointAtMs = 0L,
        )

        assertFalse(decision.shouldEmit)
        assertEquals(PausedFreshnessDecisionReason.MOVED, decision.reason)
        assertTrue((decision.distanceMeters ?: 0f) > TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS)
    }

    @Test
    fun evaluate_sparseProbeInterval_requiresFullTenMinutesSinceLastFreshness() {
        val sparseIntervalMs = FIVE_MINUTES * 2
        val anchor = location(lat = 47.0, lon = -122.0, accuracy = 6f, timeMs = 0L)
        val candidate = location(lat = 47.0, lon = -122.0, accuracy = 8f, timeMs = sparseIntervalMs)

        val tooSoon = PausedFreshnessPolicy.evaluate(
            anchorLocation = anchor,
            candidateLocation = candidate,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
            freshnessIntervalMs = sparseIntervalMs,
            nowMs = sparseIntervalMs,
            lastFreshnessPointAtMs = FIVE_MINUTES,
        )
        assertFalse(tooSoon.shouldEmit)
        assertEquals(PausedFreshnessDecisionReason.TOO_SOON, tooSoon.reason)

        val emit = PausedFreshnessPolicy.evaluate(
            anchorLocation = anchor,
            candidateLocation = candidate,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
            freshnessIntervalMs = sparseIntervalMs,
            nowMs = sparseIntervalMs + 1_000L,
            lastFreshnessPointAtMs = 0L,
        )
        assertTrue(emit.shouldEmit)
        assertEquals(PausedFreshnessDecisionReason.EMIT, emit.reason)
    }

    @Test
    fun evaluate_lessThanIntervalSinceLastFreshness_skipsTooSoon() {
        val anchor = location(lat = 47.0, lon = -122.0, accuracy = 6f, timeMs = 0L)
        val candidate = location(lat = 47.0, lon = -122.0, accuracy = 8f, timeMs = FIVE_MINUTES)

        val decision = PausedFreshnessPolicy.evaluate(
            anchorLocation = anchor,
            candidateLocation = candidate,
            stationaryRadiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = TrackingLocationPolicy.STATIONARY_ACCURACY_CEILING_METERS,
            freshnessIntervalMs = FIVE_MINUTES,
            nowMs = FIVE_MINUTES + 1_000L,
            lastFreshnessPointAtMs = 2_000L,
        )

        assertFalse(decision.shouldEmit)
        assertEquals(PausedFreshnessDecisionReason.TOO_SOON, decision.reason)
        assertEquals(FIVE_MINUTES - 1_000L, decision.elapsedSinceLastFreshnessMs)
    }

    @Test
    fun buildAnchoredFreshnessLocation_usesAnchorGeometryAndProbeAccuracy() {
        val anchor = location(lat = 47.0, lon = -122.0, accuracy = 6f, timeMs = 100L).apply {
            altitude = 12.5
            bearing = 90f
        }
        val probe = location(lat = 47.0, lon = -122.00001, accuracy = 8f, timeMs = FIVE_MINUTES).apply {
            provider = "gps"
        }

        val freshness = PausedFreshnessPointFactory.buildAnchoredFreshnessLocation(
            anchorLocation = anchor,
            probeLocation = probe,
            nowMs = FIVE_MINUTES,
            nowElapsedRealtimeNanos = 123_456L,
        )

        assertEquals(anchor.latitude, freshness.latitude, 0.0)
        assertEquals(anchor.longitude, freshness.longitude, 0.0)
        assertEquals(FIVE_MINUTES, freshness.time)
        assertEquals(123_456L, freshness.elapsedRealtimeNanos)
        assertEquals(8f, freshness.accuracy)
        assertEquals("paused_freshness:gps", freshness.provider)
        assertTrue(freshness.extras?.getBoolean(PausedFreshnessPointFactory.EXTRAS_KEY_PAUSED_FRESHNESS) == true)
        assertEquals("gps", freshness.extras?.getString(PausedFreshnessPointFactory.EXTRAS_KEY_SOURCE_PROVIDER))
    }

    private fun location(
        lat: Double,
        lon: Double,
        accuracy: Float,
        timeMs: Long,
    ): Location {
        return Location("gps").apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
            time = timeMs
        }
    }

    companion object {
        private const val FIVE_MINUTES = 5L * 60_000L
    }
}
