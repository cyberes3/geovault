package com.geovault.common.maps.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MapLibreGeographyTest {
    @Test
    fun validTypical() {
        assertTrue(isValidMapLibreGeographicLatLng(40.0, -75.0))
    }

    @Test
    fun invalidLatitudeOver90() {
        assertFalse(isValidMapLibreGeographicLatLng(91.0, 0.0))
    }

    @Test
    fun invalidNonFinite() {
        assertFalse(isValidMapLibreGeographicLatLng(Double.NaN, 0.0))
        assertFalse(isValidMapLibreGeographicLatLng(0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun latLngOrNullValid() {
        val p = latLngOrNull(10.0, 20.0)!!
        assertEquals(10.0, p.latitude, 0.0)
        assertEquals(20.0, p.longitude, 0.0)
    }

    @Test
    fun latLngOrNullInvalidNull() {
        assertNull(latLngOrNull(91.0, 0.0))
        assertNull(latLngOrNull(Double.NaN, 0.0))
    }
}
