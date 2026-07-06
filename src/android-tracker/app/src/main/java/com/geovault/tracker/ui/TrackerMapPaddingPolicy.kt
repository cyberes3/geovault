package com.geovault.tracker.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.geovault.common.maps.core.GeoVaultMapPaddingDp
import com.geovault.common.maps.core.GeoVaultMapPaddingPolicy

/**
 * Map padding contract for tracker map overlays.
 *
 * Using one policy object for both persistent viewport and bounds-fit padding prevents
 * camera framing from drifting when overlays are adjusted.
 */
class TrackerMapPaddingPolicy {
    val includeDefaultFabColumnPadding: Boolean = true

    val mapPaddingDp: GeoVaultMapPaddingDp
        get() = buildMapPaddingDp(FallbackTopLeftChipViewportReserveTopDp)

    /**
     * [topLeftChipReserveDp] should be the actual measured on-screen height of the top-left
     * tracker chip (including its own top inset), not a guessed constant -- the chip's height
     * varies with its content (name-only vs. name+status vs. name+user-label+status), and a
     * static guess undershooting the tallest variant lets fitted bounds place a marker right
     * behind the chip. Callers that haven't measured it yet (e.g. before first layout, or when
     * no chip is shown) should fall back to [FallbackTopLeftChipViewportReserveTopDp].
     */
    fun computeBoundsFitPaddingPx(
        density: Density,
        topLeftChipReserveDp: Dp = FallbackTopLeftChipViewportReserveTopDp,
    ): IntArray {
        return GeoVaultMapPaddingPolicy(
            includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
            mapPaddingDp = buildMapPaddingDp(topLeftChipReserveDp),
        ).computeBoundsFitPaddingPx(density)
    }

    private fun buildMapPaddingDp(topReserveDp: Dp) = GeoVaultMapPaddingDp(
        left = TopLeftChipViewportReserveLeftDp,
        top = topReserveDp,
    )

    companion object {
        val FallbackTopLeftChipViewportReserveTopDp = 40.dp
        private val TopLeftChipViewportReserveLeftDp = 88.dp
    }
}
