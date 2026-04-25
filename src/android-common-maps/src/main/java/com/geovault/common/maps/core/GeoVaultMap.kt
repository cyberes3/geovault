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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geovault.common.ui.theme.GeoVaultColorTokens
import org.maplibre.android.maps.MapView

private val MAP_UNDERLAY_COLOR: Int = GeoVaultColorTokens.MapUnderlay.toArgb()

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
        GeoVaultMapPaddingPolicy(
            includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
            mapPaddingDp = mapPaddingDp,
        ).computeViewportPaddingPx(density)
    }

    SideEffect {
        currentMap.setDefaultCameraPadding(effectivePaddingPx)
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val acquiredMapView = currentMap.acquireMapView(mapStateBundle)
                //  - OVER_SCROLL_NEVER avoids glow/stretch artifacts some vendors draw on a
                //    GL surface during fling.
                //  - Painting an underlay background colour hides the empty black surface
                //    between attach and first-tile render, which is jarring on white UIs.
                acquiredMapView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
                acquiredMapView.setBackgroundColor(MAP_UNDERLAY_COLOR)
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
            } else {
                // Main-mode retained MapView: keep it alive across tab changes but pause/stop
                // while it's not mounted so rendering, GPS/heading sensor subscriptions, and
                // MapLibre tile requests stop instead of draining battery in the background.
                // The next `attachMapView` + re-added lifecycle observer synchronously
                // redispatches onStart/onResume based on the Activity's current state.
                activeMapView?.onPause()
                activeMapView?.onStop()
            }
        }
    }

}
