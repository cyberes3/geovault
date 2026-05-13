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

fun GeoVaultBaseMap.moveCameraToFitLatLngBounds(
    bounds: LatLngBounds,
    paddingPx: IntArray,
    maxZoom: Double = MapLibreManager.BOUNDS_FIT_MAX_ZOOM,
) {
    require(paddingPx.size == 4)
    moveCameraWithPadding(
        CameraUpdateFactory.newLatLngBounds(
            bounds,
            paddingPx[0],
            paddingPx[1],
            paddingPx[2],
            paddingPx[3],
        ),
        padding = paddingPx.toCameraPaddingDouble(),
        maxZoom = maxZoom,
    )
}

fun GeoVaultBaseMap.animateCameraToFitLatLngBounds(
    bounds: LatLngBounds,
    paddingPx: IntArray,
    durationMs: Int = 300,
    maxZoom: Double = MapLibreManager.BOUNDS_FIT_MAX_ZOOM,
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
        padding = paddingPx.toCameraPaddingDouble(),
        durationMs = durationMs,
        maxZoom = maxZoom,
    )
}

// `newLatLngBounds(bounds, l, t, r, b)` produces a CameraPosition whose target/zoom assume the
// camera will be drawn with l/t/r/b inset padding. `*WithPadding` would otherwise fall back to
// the host's [GeoVaultBaseMap.setDefaultCameraPadding] (typically `[0,0,0,0]` for hosts that
// deliberately don't push the bottom-drawer height into the persistent viewport padding), which
// strips the bake-in and re-centers the bounds on the unpadded viewport center — visually
// dragging the bottom of the fit underneath the drawer. Forwarding the same insets as the
// camera padding keeps the fit framed inside the inner rect the caller sized for.
private fun IntArray.toCameraPaddingDouble(): DoubleArray = doubleArrayOf(
    this[0].toDouble(),
    this[1].toDouble(),
    this[2].toDouble(),
    this[3].toDouble(),
)
