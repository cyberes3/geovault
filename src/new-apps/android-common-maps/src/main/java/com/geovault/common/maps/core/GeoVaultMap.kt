package com.geovault.common.maps.core

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

data class GeoVaultMapPaddingDp(
    val left: Dp = Dp.Unspecified,
    val top: Dp = Dp.Unspecified,
    val right: Dp = Dp.Unspecified,
    val bottom: Dp = Dp.Unspecified,
)

@Composable
fun rememberGeoVaultStandardMap(): GeoVaultStandardMap {
    val context = LocalContext.current
    return remember(context) {
        GeoVaultStandardMap(context)
    }
}

@Composable
fun GeoVaultStandardMapView(
    modifier: Modifier = Modifier,
    map: GeoVaultStandardMap = rememberGeoVaultStandardMap(),
    showDefaultSourceToggle: Boolean = false,
    includeDefaultFabColumnPadding: Boolean = false,
    mapPaddingDp: GeoVaultMapPaddingDp = GeoVaultMapPaddingDp(),
) {
    GeoVaultMapHost(
        modifier = modifier,
        map = map,
        mode = GeoVaultMapHostMode.Standard,
        showDefaultSourceToggle = showDefaultSourceToggle,
        includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
        mapPaddingDp = mapPaddingDp,
    )
}

@Composable
fun GeoVaultMainMapView(
    modifier: Modifier = Modifier,
    map: GeoVaultMainMap,
    showDefaultSourceToggle: Boolean = false,
    includeDefaultFabColumnPadding: Boolean = false,
    mapPaddingDp: GeoVaultMapPaddingDp = GeoVaultMapPaddingDp(),
) {
    GeoVaultMapHost(
        modifier = modifier,
        map = map,
        mode = GeoVaultMapHostMode.Main,
        showDefaultSourceToggle = showDefaultSourceToggle,
        includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
        mapPaddingDp = mapPaddingDp,
    )
}

private enum class GeoVaultMapHostMode {
    Standard,
    Main,
}

@Composable
private fun GeoVaultMapHost(
    modifier: Modifier,
    map: GeoVaultBaseMap,
    mode: GeoVaultMapHostMode,
    showDefaultSourceToggle: Boolean,
    includeDefaultFabColumnPadding: Boolean,
    mapPaddingDp: GeoVaultMapPaddingDp,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var mapView: MapView? by remember { mutableStateOf(null) }
    val currentMap by rememberUpdatedState(map)
    val mapStateBundle = remember { Bundle() }

    val effectivePaddingPx = remember(density, includeDefaultFabColumnPadding, mapPaddingDp) {
        computeMapPaddingPx(
            density = density,
            includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
            mapPaddingDp = mapPaddingDp,
        )
    }

    SideEffect {
        currentMap.setDefaultCameraPadding(effectivePaddingPx)
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val acquiredMapView = currentMap.acquireMapView(mapStateBundle)
                mapView = acquiredMapView
                currentMap.attachMapView(acquiredMapView)
                acquiredMapView
            },
            update = {
                if (mapView !== it) {
                    mapView = it
                    currentMap.attachMapView(it)
                }
            },
        )

        if (showDefaultSourceToggle) {
            Button(
                onClick = { currentMap.cycleSource() },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text("Layers")
            }
        }
    }

    DisposableEffect(lifecycleOwner, currentMap, mode) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                mapView?.onStart()
                currentMap.ensureInteractiveGestures()
            }

            override fun onResume(owner: LifecycleOwner) {
                mapView?.onResume()
                currentMap.ensureInteractiveGestures()
            }

            override fun onPause(owner: LifecycleOwner) {
                mapView?.onPause()
            }

            override fun onStop(owner: LifecycleOwner) {
                mapView?.onStop()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mapView?.onSaveInstanceState(mapStateBundle)
                if (mode == GeoVaultMapHostMode.Standard) {
                    mapView?.onDestroy()
                    currentMap.onDestroy()
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val activeMapView = mapView
            if (mode == GeoVaultMapHostMode.Standard) {
                currentMap.setDefaultCameraPadding(null)
                activeMapView?.onPause()
                activeMapView?.onStop()
                activeMapView?.onSaveInstanceState(mapStateBundle)
                currentMap.detachMapView()
                activeMapView?.onDestroy()
                currentMap.onDestroy()
            }
        }
    }

}

internal fun computeMapPaddingPx(
    density: androidx.compose.ui.unit.Density,
    includeDefaultFabColumnPadding: Boolean,
    mapPaddingDp: GeoVaultMapPaddingDp,
): DoubleArray {
    fun Dp.orZeroPx(): Double {
        return if (this == Dp.Unspecified) 0.0 else with(density) { toPx().toDouble() }
    }
    if (includeDefaultFabColumnPadding) {
        // Same edge inset on all sides, plus additional right-side reserve for the FAB stack
        // (see GeoVaultMapFabColumn default end margin + fabSize) to keep fitted content clear.
        val edge = with(density) { DEFAULT_MAP_EDGE_PADDING_DP.toPx().toDouble() }
        val fabColumnReserve = with(density) {
            (DEFAULT_MAP_FAB_COLUMN_END_MARGIN_DP + DEFAULT_MAP_FAB_COLUMN_FAB_SIZE_DP).toPx().toDouble()
        }
        val leftExtra = with(density) { DEFAULT_MAP_LEFT_SAFE_EXTRA_DP.toPx().toDouble() }
        val rightExtra = with(density) { DEFAULT_MAP_RIGHT_SAFE_EXTRA_DP.toPx().toDouble() }
        return doubleArrayOf(
            edge + leftExtra + mapPaddingDp.left.orZeroPx(),
            edge + mapPaddingDp.top.orZeroPx(),
            edge + fabColumnReserve + rightExtra + mapPaddingDp.right.orZeroPx(),
            edge + mapPaddingDp.bottom.orZeroPx(),
        )
    }
    return doubleArrayOf(
        mapPaddingDp.left.orZeroPx(),
        mapPaddingDp.top.orZeroPx(),
        mapPaddingDp.right.orZeroPx(),
        mapPaddingDp.bottom.orZeroPx(),
    )
}

private val DEFAULT_MAP_EDGE_PADDING_DP = 16.dp
private val DEFAULT_MAP_LEFT_SAFE_EXTRA_DP = 8.dp
private val DEFAULT_MAP_RIGHT_SAFE_EXTRA_DP = 12.dp

/** Matches [GeoVaultMapFabColumn] default `Modifier.padding` end inset and FAB width. */
private val DEFAULT_MAP_FAB_COLUMN_END_MARGIN_DP = 16.dp
private val DEFAULT_MAP_FAB_COLUMN_FAB_SIZE_DP = 44.dp
