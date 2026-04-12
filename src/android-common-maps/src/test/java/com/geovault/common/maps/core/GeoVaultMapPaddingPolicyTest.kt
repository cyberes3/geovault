package com.geovault.common.maps.core

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GeoVaultMapPaddingPolicyTest {

    @Test
    fun computeViewportPaddingPx_withFabColumnPadding_appliesBaseAndOverlayInsets() {
        val density = Density(2f)
        val policy = GeoVaultMapPaddingPolicy(
            includeDefaultFabColumnPadding = true,
            mapPaddingDp = GeoVaultMapPaddingDp(
                left = 10.dp,
                top = 5.dp,
                right = 7.dp,
                bottom = 3.dp,
            ),
        )

        val px = policy.computeViewportPaddingPx(density)

        assertArrayEquals(
            doubleArrayOf(
                68.0,  // (16 + 8 + 10) * 2
                42.0,  // (16 + 5) * 2
                190.0, // (16 + (16 + 44) + 12 + 7) * 2
                38.0,  // (16 + 3) * 2
            ),
            px,
            0.001,
        )
    }

    @Test
    fun computeBoundsFitPaddingPx_matchesViewportPaddingRounded() {
        val density = Density(2f)
        val policy = GeoVaultMapPaddingPolicy(
            includeDefaultFabColumnPadding = true,
            mapPaddingDp = GeoVaultMapPaddingDp(left = 10.dp, top = 5.dp),
        )

        val boundsPx = policy.computeBoundsFitPaddingPx(density)

        assertArrayEquals(intArrayOf(68, 42, 176, 32), boundsPx)
    }

    @Test
    fun computeViewportPaddingPx_withoutFabColumnPadding_usesOnlyOverlayInsets() {
        val density = Density(2f)
        val policy = GeoVaultMapPaddingPolicy(
            includeDefaultFabColumnPadding = false,
            mapPaddingDp = GeoVaultMapPaddingDp(
                left = 10.dp,
                top = 5.dp,
                right = 7.dp,
                bottom = 3.dp,
            ),
        )

        val px = policy.computeViewportPaddingPx(density)

        assertArrayEquals(doubleArrayOf(20.0, 10.0, 14.0, 6.0), px, 0.001)
    }
}
