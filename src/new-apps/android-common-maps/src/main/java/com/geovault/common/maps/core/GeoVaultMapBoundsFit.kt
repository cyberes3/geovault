package com.geovault.common.maps.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds

/**
 * Per-edge insets (px) passed to [CameraUpdateFactory.newLatLngBounds] so fitted geometry clears
 * screen edges and the default FAB column. Distinct from persistent camera padding applied by
 * [computeMapPaddingPx] / [GeoVaultMapHost].
 */
val GeoVaultMapBoundsFitPaddingLeftDp = 24.dp
val GeoVaultMapBoundsFitPaddingTopDp = 16.dp
val GeoVaultMapBoundsFitPaddingRightDp = 88.dp
val GeoVaultMapBoundsFitPaddingBottomDp = 16.dp

fun computeGeoVaultMapBoundsFitPaddingPx(density: Density): IntArray {
    return intArrayOf(
        with(density) { GeoVaultMapBoundsFitPaddingLeftDp.toPx().toInt() },
        with(density) { GeoVaultMapBoundsFitPaddingTopDp.toPx().toInt() },
        with(density) { GeoVaultMapBoundsFitPaddingRightDp.toPx().toInt() },
        with(density) { GeoVaultMapBoundsFitPaddingBottomDp.toPx().toInt() },
    )
}

@Composable
fun rememberGeoVaultMapBoundsFitPaddingPx(): IntArray {
    val density = LocalDensity.current
    return remember(density) { computeGeoVaultMapBoundsFitPaddingPx(density) }
}

fun GeoVaultBaseMap.moveCameraToFitLatLngBounds(bounds: LatLngBounds, paddingPx: IntArray) {
    require(paddingPx.size == 4)
    moveCameraWithPadding(
        CameraUpdateFactory.newLatLngBounds(
            bounds,
            paddingPx[0],
            paddingPx[1],
            paddingPx[2],
            paddingPx[3],
        ),
        maxZoom = MapLibreManager.BOUNDS_FIT_MAX_ZOOM,
    )
}

fun GeoVaultBaseMap.animateCameraToFitLatLngBounds(
    bounds: LatLngBounds,
    paddingPx: IntArray,
    durationMs: Int = 300,
) {
    require(paddingPx.size == 4)
    animateCameraWithPadding(
        CameraUpdateFactory.newLatLngBounds(
            bounds,
            paddingPx[0],
            paddingPx[1],
            paddingPx[2],
            paddingPx[3],
        ),
        durationMs = durationMs,
        maxZoom = MapLibreManager.BOUNDS_FIT_MAX_ZOOM,
    )
}
