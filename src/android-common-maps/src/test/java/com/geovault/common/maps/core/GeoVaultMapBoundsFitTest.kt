package com.geovault.common.maps.core

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultMapBoundsFitTest {

    @Test
    fun computeGeoVaultMapBoundsFitPaddingPx_returnsFourInsets() {
        val density = Density(2f)
        val px = computeGeoVaultMapBoundsFitPaddingPx(density)
        assertEquals(4, px.size)
        assertTrueAllNonNegative(px)
        assertArrayEquals(
            intArrayOf(48, 32, 176, 32),
            px,
        )
    }

    private fun assertTrueAllNonNegative(px: IntArray) {
        px.forEach { assertTrue(it >= 0) }
    }
}
