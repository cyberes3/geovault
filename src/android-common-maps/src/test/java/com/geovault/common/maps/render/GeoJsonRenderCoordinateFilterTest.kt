package com.geovault.common.maps.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoJsonRenderCoordinateFilterTest {

    @Test
    fun filterPoints_dropsInvalidLat() {
        val good = MapRenderPoint(id = "a", latitude = 40.0, longitude = -75.0)
        val bad = MapRenderPoint(id = "b", latitude = 91.0, longitude = 0.0)
        val out = filterMapRenderPointsForGeoJson(listOf(good, bad))
        assertEquals(1, out.size)
        assertEquals("a", out[0].id)
    }

    @Test
    fun filterLine_nullWhenTooFewAfterFilter() {
        val line = MapRenderLine(
            id = "L",
            coordinates = listOf(40.0 to -75.0, 91.0 to 0.0),
            lineColorHex = "#000",
        )
        assertNull(mapRenderLineToValidCoordinatesOrNull(line))
    }

    @Test
    fun filterLine_keepsWhenTwoValid() {
        val line = MapRenderLine(
            id = "L",
            coordinates = listOf(40.0 to -75.0, 41.0 to -76.0),
            lineColorHex = "#000",
        )
        val c = mapRenderLineToValidCoordinatesOrNull(line)!!
        assertEquals(2, c.size)
    }

    @Test
    fun filterPolygon_nullWhenRingsEmptyAfterFilter() {
        val badRing = listOf(91.0 to 0.0, 92.0 to 0.0, 93.0 to 0.0)
        val p = MapRenderPolygon(id = "p", rings = listOf(badRing), fillColorHex = "#f00", outlineColorHex = "#00f")
        assertNull(filterMapRenderPolygonForGeoJson(p))
    }

    @Test
    fun filterPolygon_keepsValidRing() {
        val ring = listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)
        val p = MapRenderPolygon(id = "p", rings = listOf(ring), fillColorHex = "#f00", outlineColorHex = "#00f")
        val rings = filterMapRenderPolygonForGeoJson(p)!!
        assertEquals(1, rings.size)
        assertEquals(3, rings[0].size)
    }
}
