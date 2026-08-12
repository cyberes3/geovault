package com.geovault.common.maps.kml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KmlCoordinateTuplesTest {

    @Test
    fun parsePositions_lonLat() {
        val pts = KmlCoordinateTuples.parsePositions("10,20  30,40")
        assertEquals(2, pts.size)
        assertEquals(10.0, pts[0].longitude, 0.0)
        assertEquals(20.0, pts[0].latitude, 0.0)
        assertNull(pts[0].altitudeMeters)
        assertEquals(30.0, pts[1].longitude, 0.0)
        assertEquals(40.0, pts[1].latitude, 0.0)
    }

    @Test
    fun parsePositions_withAltitude() {
        val pts = KmlCoordinateTuples.parsePositions("-71.06,42.36,100")
        assertEquals(1, pts.size)
        assertEquals(-71.06, pts[0].longitude, 0.0)
        assertEquals(42.36, pts[0].latitude, 0.0)
        assertEquals(100.0, pts[0].altitudeMeters!!, 0.0)
    }

    @Test
    fun parsePositions_skipsBadTokens() {
        val pts = KmlCoordinateTuples.parsePositions("1,2 bad 3,4")
        assertEquals(2, pts.size)
        assertEquals(3.0, pts[1].longitude, 0.0)
    }

    @Test
    fun parsePositions_blank_returnsEmpty() {
        assertEquals(0, KmlCoordinateTuples.parsePositions(null).size)
        assertEquals(0, KmlCoordinateTuples.parsePositions("   ").size)
    }

    @Test
    fun parsePosition_stripsInternalWhitespace() {
        val pos = KmlCoordinateTuples.parsePosition(" -71.06, 42.36, 0 ")
        assertEquals(-71.06, pos!!.longitude, 0.0)
        assertEquals(42.36, pos.latitude, 0.0)
        assertEquals(0.0, pos.altitudeMeters!!, 0.0)
    }

    @Test
    fun closeRing_appendsFirstWhenOpen() {
        val a = KmlPosition(0.0, 0.0)
        val b = KmlPosition(1.0, 0.0)
        val closed = KmlCoordinateTuples.closeRing(listOf(a, b, KmlPosition(0.0, 1.0)))
        assertEquals(4, closed.size)
        assertEquals(a, closed.last())
    }
}
