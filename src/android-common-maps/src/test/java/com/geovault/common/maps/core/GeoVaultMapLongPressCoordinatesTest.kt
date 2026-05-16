package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoVaultMapLongPressCoordinatesTest {
    @Test
    fun formatMapLongPressCoordinates_valid() {
        assertEquals("40.1235, -75.5679", formatMapLongPressCoordinates(40.123456789, -75.56789123))
    }

    @Test
    fun formatMapLongPressCoordinates_invalidLat() {
        assertNull(formatMapLongPressCoordinates(91.0, 0.0))
    }

    @Test
    fun formatMapLongPressCoordinates_invalidLon() {
        assertNull(formatMapLongPressCoordinates(0.0, Double.NaN))
    }
}
