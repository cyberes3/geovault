package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

class GeoVaultLatLngBoundsTest {

    @Test
    fun geoVaultLatLngBoundsForPoints_usesShortArcForUsaAndEurope() {
        val bounds = geoVaultLatLngBoundsForPoints(
            listOf(
                LatLng(39.0, -95.0),
                LatLng(55.0, 37.0),
            ),
        )
        assertNotNull(bounds)
        assertEquals(-95.0, bounds!!.longitudeWest, 1e-6)
        assertEquals(37.0, bounds.longitudeEast, 1e-6)
        val span = bounds.longitudeEast - bounds.longitudeWest
        assertTrue("expected short Pacific arc (<200°), was $span", span < 200.0)
    }

    @Test
    fun geoVaultLatLngBoundsUnion_includesGpsWithoutNaiveBuilder() {
        val base = geoVaultLatLngBoundsForPoints(
            listOf(LatLng(39.0, -95.0), LatLng(55.0, 37.0)),
        )!!
        val merged = geoVaultLatLngBoundsUnion(base, listOf(LatLng(40.0, -96.0)))
        assertTrue(merged.latitudeNorth >= 55.0)
        assertTrue(merged.latitudeSouth <= 39.0)
    }

    @Test
    fun geoVaultLatLngBoundsForPoints_twoNearbyPoints_matchesBuilder() {
        val a = LatLng(40.0, -120.0)
        val b = LatLng(41.0, -121.0)
        val ours = geoVaultLatLngBoundsForPoints(listOf(a, b))
        val builder = LatLngBounds.Builder().include(a).include(b).build()
        assertNotNull(ours)
        assertEquals(builder.latitudeNorth, ours!!.latitudeNorth, 1e-6)
        assertEquals(builder.latitudeSouth, ours.latitudeSouth, 1e-6)
        assertEquals(builder.longitudeWest, ours.longitudeWest, 1e-6)
        assertEquals(builder.longitudeEast, ours.longitudeEast, 1e-6)
    }
}
