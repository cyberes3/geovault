package com.geovault.common.maps.core

import com.geovault.common.geo.Wgs84Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultTrackGeometryHooksTest {

    @Test
    fun splitTrackByDistance_splitsAcrossLargeJump() {
        val segments = geoVaultSplitTrackByDistance(
            points = listOf(
                Wgs84Point(37.0, -122.0),
                Wgs84Point(37.0001, -122.0001),
                Wgs84Point(38.0, -123.0),
                Wgs84Point(38.0001, -123.0001)
            ),
            maxJumpMeters = 1_000f
        )
        assertEquals(2, segments.size)
        assertEquals(2, segments[0].size)
        assertEquals(2, segments[1].size)
    }

    @Test
    fun splitTrackByDistance_filtersInvalidPoints() {
        val segments = geoVaultSplitTrackByDistance(
            points = listOf(
                Wgs84Point(37.0, -122.0),
                Wgs84Point(95.0, -122.0),
                Wgs84Point(37.0001, -122.0001)
            ),
            maxJumpMeters = 1_000f
        )
        assertEquals(1, segments.size)
        assertEquals(2, segments.first().size)
        assertTrue(segments.first().all { it.latitude in -90.0..90.0 })
    }
}
