package com.geovault.common.maps.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds

/**
 * Default per-edge insets (px) passed to [CameraUpdateFactory.newLatLngBounds] when no overlay
 * specific policy is provided by the screen.
 */
fun computeGeoVaultMapBoundsFitPaddingPx(density: Density): IntArray {
    return DEFAULT_GEO_VAULT_MAP_PADDING_POLICY.computeBoundsFitPaddingPx(density)
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
