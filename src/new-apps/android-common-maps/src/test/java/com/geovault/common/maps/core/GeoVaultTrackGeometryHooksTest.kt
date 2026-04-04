package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultTrackGeometryHooksTest {

    @Test
    fun splitTrackByDistance_splitsAcrossLargeJump() {
        val segments = geoVaultSplitTrackByDistance(
            points = listOf(
                37.0 to -122.0,
                37.0001 to -122.0001,
                38.0 to -123.0,
                38.0001 to -123.0001
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
                37.0 to -122.0,
                95.0 to -122.0,
                37.0001 to -122.0001
            ),
            maxJumpMeters = 1_000f
        )
        assertEquals(1, segments.size)
        assertEquals(2, segments.first().size)
        assertTrue(segments.first().all { it.first in -90.0..90.0 })
    }
}
