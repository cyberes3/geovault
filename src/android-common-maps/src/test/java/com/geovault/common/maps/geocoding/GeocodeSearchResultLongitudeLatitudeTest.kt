package com.geovault.common.maps.geocoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeocodeSearchResultLongitudeLatitudeTest {

    @Test
    fun longitudeLatitudeOrNull_returnsPair_whenTwoCoordinates() {
        val r = GeocodeSearchResult(
            coordinates = listOf(-122.4, 37.8),
            place_name = "X",
            text = "Y",
        )
        assertEquals(-122.4 to 37.8, r.longitudeLatitudeOrNull())
    }

    @Test
    fun longitudeLatitudeOrNull_returnsNull_whenCoordinatesNull() {
        val r = GeocodeSearchResult(
            coordinates = null,
            place_name = null,
            text = null,
        )
        assertNull(r.longitudeLatitudeOrNull())
    }

    @Test
    fun longitudeLatitudeOrNull_returnsNull_whenOnlyOneCoordinate() {
        val r = GeocodeSearchResult(
            coordinates = listOf(-122.0),
            place_name = null,
            text = null,
        )
        assertNull(r.longitudeLatitudeOrNull())
    }

    @Test
    fun longitudeLatitudeOrNull_returnsNull_whenEmptyList() {
        val r = GeocodeSearchResult(
            coordinates = emptyList(),
            place_name = null,
            text = null,
        )
        assertNull(r.longitudeLatitudeOrNull())
    }
}
