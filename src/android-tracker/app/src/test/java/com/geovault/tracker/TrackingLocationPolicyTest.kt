package com.geovault.tracker

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for TrackingLocationPolicy: accuracy filter, stationary detection,
 * significant-motion-only, and interval/distance parameters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackingLocationPolicyTest {

    private fun location(
        lat: Double = 0.0,
        lon: Double = 0.0,
        time: Long = 0L,
        accuracy: Float? = null,
        speed: Float? = null
    ): Location {
        val loc = Location("gps")
        loc.latitude = lat
        loc.longitude = lon
        loc.time = time
        if (accuracy != null) loc.accuracy = accuracy
        if (speed != null) loc.speed = speed
        return loc
    }

    // --- Interval ---

    @Test
    fun locationRequestInterval_15sec_returns15000And7500() {
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(15L)
        assertEquals(15_000L, intervalMs)
        assertEquals(7_500L, minUpdateMs)
    }

    @Test
    fun locationRequestInterval_30sec_returns30000And15000() {
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(30L)
        assertEquals(30_000L, intervalMs)
        assertEquals(15_000L, minUpdateMs)
    }

    @Test
    fun locationRequestInterval_60sec_returns60000And30000() {
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(60L)
        assertEquals(60_000L, intervalMs)
        assertEquals(30_000L, minUpdateMs)
    }

    // --- Accuracy ---

    @Test
    fun acceptByAccuracy_withinThreshold_accepts() {
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 30f), 50f))
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 50f), 50f))
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 80f), 100f))
    }

    @Test
    fun acceptByAccuracy_overThreshold_discards() {
        assertFalse(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 80f), 50f))
        assertFalse(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 120f), 100f))
    }

    @Test
    fun acceptByAccuracy_noAccuracy_accepts() {
        val loc = location()
        assertFalse(loc.hasAccuracy())
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(loc, 50f))
    }

    @Test
    fun acceptByAccuracy_filter50_variousAccuracies() {
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 30f), 50f))
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 50f), 50f))
        assertFalse(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 80f), 50f))
        assertFalse(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 120f), 50f))
    }

    @Test
    fun acceptByAccuracy_filter100_variousAccuracies() {
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 30f), 100f))
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 80f), 100f))
        assertFalse(TrackingLocationPolicy.acceptByAccuracy(location(accuracy = 120f), 100f))
    }

    // --- Distance / Stationary ---
    // At equator ~0.00009 deg ≈ 10m. So (0,0) to (0, 0.00005) ≈ 5.5m (within 10m), (0,0) to (0, 0.0002) ≈ 22m (beyond).

    private fun locationNear(base: Location, offsetDeg: Double): Location {
        val loc = Location("gps")
        loc.latitude = base.latitude
        loc.longitude = base.longitude + offsetDeg
        loc.time = base.time + 15_000
        return loc
    }

    @Test
    fun stationaryUpdate_twoWithin10m_count2_noPause() {
        val a = location(0.0, 0.0, 0L)
        val b = locationNear(a, 0.00005) // ~5.5m
        val c = locationNear(b, 0.00005) // ~5.5m from b
        val (count1, pause1) = TrackingLocationPolicy.stationaryUpdate(a, b, 10f, 0, true)
        assertEquals(1, count1)
        assertFalse(pause1)
        val (count2, pause2) = TrackingLocationPolicy.stationaryUpdate(b, c, 10f, count1, true)
        assertEquals(2, count2)
        assertFalse(pause2)
    }

    @Test
    fun stationaryUpdate_thirdWithin10m_shouldPauseTrue() {
        val a = location(0.0, 0.0, 0L)
        val b = locationNear(a, 0.00005)
        val c = locationNear(b, 0.00005)
        val d = locationNear(c, 0.00005)
        var consecutive = 0
        var shouldPause = false
        listOf(b, c, d).forEach { loc ->
            val prev = when (loc) {
                b -> a
                c -> b
                else -> c
            }
            val (newCount, pause) = TrackingLocationPolicy.stationaryUpdate(prev, loc, 10f, consecutive, true)
            consecutive = newCount
            shouldPause = pause
        }
        assertEquals(3, consecutive)
        assertTrue(shouldPause)
    }

    @Test
    fun stationaryUpdate_oneBeyond10m_resetsCount() {
        val a = location(0.0, 0.0, 0L)
        val b = locationNear(a, 0.00005) // within 10m
        val c = locationNear(b, 0.0002)  // ~22m from b
        val (count1, _) = TrackingLocationPolicy.stationaryUpdate(a, b, 10f, 0, true)
        assertEquals(1, count1)
        val (count2, pause2) = TrackingLocationPolicy.stationaryUpdate(b, c, 10f, count1, true)
        assertEquals(0, count2)
        assertFalse(pause2)
    }

    @Test
    fun stationaryUpdate_distanceFilter50m_within50m_countsStationary() {
        // ~30m apart: 0.00027 deg at equator
        val a = location(0.0, 0.0, 0L)
        val b = locationNear(a, 0.00027)
        val (count, _) = TrackingLocationPolicy.stationaryUpdate(a, b, 50f, 0, true)
        assertEquals(1, count)
    }

    @Test
    fun stationaryUpdate_distanceFilter50m_beyond50m_resets() {
        val a = location(0.0, 0.0, 0L)
        val b = locationNear(a, 0.0006) // ~67m
        val (count, _) = TrackingLocationPolicy.stationaryUpdate(a, b, 50f, 2, true)
        assertEquals(0, count)
    }

    // --- Significant motion only ---

    @Test
    fun stationaryUpdate_significantMotionOnlyOff_neverPausesFromStationary() {
        val a = location(0.0, 0.0, 0L)
        val b = locationNear(a, 0.00005)
        val c = locationNear(b, 0.00005)
        val d = locationNear(c, 0.00005)
        var consecutive = 0
        listOf(b, c, d).forEach { loc ->
            val prev = when (loc) {
                b -> a
                c -> b
                else -> c
            }
            val (newCount, pause) = TrackingLocationPolicy.stationaryUpdate(prev, loc, 10f, consecutive, false)
            consecutive = newCount
            assertFalse("shouldPause must be false when significantMotionOnly is false", pause)
        }
        assertEquals(0, consecutive)
    }

    @Test
    fun stationaryUpdate_significantMotionOnlyOn_threeStationary_pauses() {
        val a = location(0.0, 0.0, 0L)
        val b = locationNear(a, 0.00005)
        val c = locationNear(b, 0.00005)
        val (_, pause) = TrackingLocationPolicy.stationaryUpdate(b, c, 10f, 2, true)
        assertTrue(pause)
    }

    // --- Speeds (stationary count depends on distance, not speed) ---

    @Test
    fun stationaryUpdate_highSpeedButWithinDistance_stillCountsStationary() {
        val a = location(0.0, 0.0, 0L, speed = 0f)
        val b = locationNear(a, 0.00005)
        b.speed = 28f // ~100 km/h
        val (count, _) = TrackingLocationPolicy.stationaryUpdate(a, b, 10f, 0, true)
        assertEquals(1, count)
    }

    @Test
    fun stationaryUpdate_zeroSpeedButMovedBeyondThreshold_resets() {
        val a = location(0.0, 0.0, 0L, speed = 0f)
        val b = locationNear(a, 0.0002) // moved >10m
        b.speed = 0f
        val (count, _) = TrackingLocationPolicy.stationaryUpdate(a, b, 10f, 1, true)
        assertEquals(0, count)
    }

    // --- Combined (interval + distance + accuracy) ---

    @Test
    fun combined_interval30_distance20_accuracy80() {
        val (intervalMs, minMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(30L)
        assertEquals(30_000L, intervalMs)
        assertEquals(15_000L, minMs)
        val loc = location(accuracy = 80f)
        assertTrue(TrackingLocationPolicy.acceptByAccuracy(loc, 80f))
        val a = location(0.0, 0.0, 0L)
        val b = locationNear(a, 0.00017) // ~19m, within 20m filter
        val (count, _) = TrackingLocationPolicy.stationaryUpdate(a, b, 20f, 0, true)
        assertEquals(1, count)
    }
}
