package com.geovault.tracker.ui

import androidx.compose.ui.unit.Density
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
    private val delegate = GeoVaultMapPaddingPolicy(
        includeDefaultFabColumnPadding = true,
        mapPaddingDp = GeoVaultMapPaddingDp(
            left = TopLeftChipViewportReserveLeftDp,
            top = TopLeftChipViewportReserveTopDp,
        ),
    )

    val includeDefaultFabColumnPadding: Boolean
        get() = delegate.includeDefaultFabColumnPadding

    val mapPaddingDp: GeoVaultMapPaddingDp
        get() = delegate.mapPaddingDp

    fun computeBoundsFitPaddingPx(density: Density): IntArray {
        return delegate.computeBoundsFitPaddingPx(density)
    }

    private companion object {
        val TopLeftChipViewportReserveLeftDp = 88.dp
        val TopLeftChipViewportReserveTopDp = 40.dp
    }
}
