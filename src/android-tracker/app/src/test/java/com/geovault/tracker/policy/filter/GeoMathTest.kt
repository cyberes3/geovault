package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun haversine_zeroForIdenticalCoordinates() {
        assertEquals(0.0, GeoMath.haversineMeters(10.0, 20.0, 10.0, 20.0), 1e-9)
    }

    @Test
    fun haversine_handlesSmallNorthOffset_to_oneCm() {
        val a = GeoMath.haversineMeters(0.0, 0.0, 0.0001, 0.0)
        assertEquals(11.13, a, 0.05)
    }

    @Test
    fun haversine_handlesPolarLine_lengthMatchesSphereGeometry() {
        // Two points 1 deg of latitude apart at the equator -> ~111 km
        val d = GeoMath.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 100.0)
    }

    @Test
    fun haversine_isSymmetric() {
        val ab = GeoMath.haversineMeters(40.0, -74.0, 41.0, -73.0)
        val ba = GeoMath.haversineMeters(41.0, -73.0, 40.0, -74.0)
        assertEquals(ab, ba, 1e-9)
    }

    @Test
    fun haversine_doesNotProduceNaNForBarelyOverflowingFloatingPointError() {
        // Same coordinate triggers a == 0.0 exactly; the implementation must clamp.
        val d = GeoMath.haversineMeters(0.5, 0.5, 0.5, 0.5)
        assertEquals(0.0, d, 1e-12)
    }

    @Test
    fun shortestBearingDelta_isAlwaysNonNegativeAndAtMost180() {
        val pairs = listOf(
            0.0 to 0.0,
            350.0 to 10.0,
            10.0 to 350.0,
            45.0 to 225.0,
            -10.0 to 10.0,
            720.0 to 360.0,
        )
        pairs.forEach { (from, to) ->
            val delta = GeoMath.shortestBearingDeltaDegrees(from, to)
            assert(delta in 0.0..180.0) { "delta out of range for ($from,$to): $delta" }
        }
    }

    @Test
    fun shortestBearingDelta_takesShortArcAcrossNorth() {
        assertEquals(20.0, GeoMath.shortestBearingDeltaDegrees(350.0, 10.0), 1e-9)
        assertEquals(20.0, GeoMath.shortestBearingDeltaDegrees(10.0, 350.0), 1e-9)
    }

    @Test
    fun shortestBearingDelta_oppositeBearings_equal180() {
        assertEquals(180.0, GeoMath.shortestBearingDeltaDegrees(0.0, 180.0), 1e-9)
        assertEquals(180.0, GeoMath.shortestBearingDeltaDegrees(45.0, 225.0), 1e-9)
    }
}
