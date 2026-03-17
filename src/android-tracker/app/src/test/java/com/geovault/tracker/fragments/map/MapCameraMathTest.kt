package com.geovault.tracker.fragments.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraMathTest {

    @Test
    fun sanitizeBoundsFitPaddingPx_clampsInsetsToPreserveViewport() {
        val sanitized = MapCameraMath.sanitizeBoundsFitPaddingPx(
            mapWidthPxRaw = 1000,
            mapHeightPxRaw = 800,
            rawPaddingPx = intArrayOf(700, 500, 700, 500),
            minViewportWidthFraction = 0.4,
            minViewportHeightFraction = 0.4
        )

        assertArrayEquals(intArrayOf(300, 240, 300, 240), sanitized)
    }

    @Test
    fun sanitizeBoundsFitPaddingPx_rejectsNegativePadding() {
        val sanitized = MapCameraMath.sanitizeBoundsFitPaddingPx(
            mapWidthPxRaw = 500,
            mapHeightPxRaw = 500,
            rawPaddingPx = intArrayOf(-10, -20, 40, 50),
            minViewportWidthFraction = 0.5,
            minViewportHeightFraction = 0.5
        )

        assertTrue(sanitized[0] >= 0)
        assertTrue(sanitized[1] >= 0)
        assertTrue(sanitized[2] >= 0)
        assertTrue(sanitized[3] >= 0)
    }

    @Test
    fun worldHelpers_wrapAndNormalizeConsistently() {
        val worldSize = MapCameraMath.worldSizeAtZoom(2.0)
        assertEquals(1024.0, worldSize, 0.000001)
        assertEquals(1004.0, MapCameraMath.normalizeWrapped(-20.0, worldSize), 0.000001)
        assertEquals(20.0, MapCameraMath.wrappedPixelDelta(1000.0, 1020.0, worldSize), 0.000001)
    }
}
