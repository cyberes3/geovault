package com.geovault.common.maps.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccuracyGeometryBuilderTest {
    @Test
    fun `returns empty for invalid radius`() {
        assertTrue(AccuracyGeometryBuilder.buildAccuracyRing(LatLon(0.0, 0.0), -1.0).isEmpty())
        assertTrue(AccuracyGeometryBuilder.buildAccuracyRing(LatLon(0.0, 0.0), Double.NaN).isEmpty())
    }

    @Test
    fun `returns closed ring for valid radius`() {
        val ring = AccuracyGeometryBuilder.buildAccuracyRing(LatLon(10.0, 20.0), 50.0, steps = 24)
        assertFalse(ring.isEmpty())
        assertTrue(ring.first().lat.isFinite())
        assertTrue(ring.last().lon.isFinite())
    }
}
