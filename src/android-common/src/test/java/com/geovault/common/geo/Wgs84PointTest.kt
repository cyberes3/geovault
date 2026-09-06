package com.geovault.common.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Wgs84PointTest {
    @Test
    fun conversionPreservesNamedAxes() {
        val point = Wgs84Point(latitude = 10.0, longitude = 20.0)
        val lonLat = point.asLonLat()
        assertEquals(20.0, lonLat.longitude, 0.0)
        assertEquals(10.0, lonLat.latitude, 0.0)
        assertEquals(point, lonLat.asWgs84())
    }

    @Test
    fun validityUsesGeographicBounds() {
        assertTrue(Wgs84Point(45.0, -93.0).isValidGeographic())
        assertFalse(Wgs84Point(95.0, 0.0).isValidGeographic())
        assertFalse(LonLat(longitude = 200.0, latitude = 0.0).isValidGeographic())
    }
}
