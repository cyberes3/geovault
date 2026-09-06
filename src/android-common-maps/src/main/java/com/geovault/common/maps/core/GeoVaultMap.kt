package com.geovault.common.maps.core

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.geovault.common.maps.ui.scale.GeoVaultMapScaleBar
import com.geovault.common.maps.ui.scale.GeoVaultMapScaleBarDefaults
import com.geovault.common.ui.components.GeoVaultFormDialog
import org.maplibre.android.maps.MapView

data class GeoVaultMapPaddingDp(
    val left: Dp = Dp.Unspecified,
    val top: Dp = Dp.Unspecified,
    val right: Dp = Dp.Unspecified,
    val bottom: Dp = Dp.Unspecified,
)

data class GeoVaultMapPopupAvoidanceInsetsPx(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
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
    popupAvoidanceInsetsPx: GeoVaultMapPopupAvoidanceInsetsPx = GeoVaultMapPopupAvoidanceInsetsPx(),
    showScaleBar: Boolean = false,
    suppressMapLoadErrorDialog: Boolean = false,
) {
    GeoVaultMapHost(
        modifier = modifier,
        map = map,
        mode = GeoVaultMapHostMode.Standard,
        showDefaultSourceToggle = showDefaultSourceToggle,
        includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
        mapPaddingDp = mapPaddingDp,
        popupAvoidanceInsetsPx = popupAvoidanceInsetsPx,
        showScaleBar = showScaleBar,
        suppressMapLoadErrorDialog = suppressMapLoadErrorDialog,
    )
}

@Composable
fun GeoVaultMainMapView(
    modifier: Modifier = Modifier,
    map: GeoVaultMainMap,
    showDefaultSourceToggle: Boolean = false,
    includeDefaultFabColumnPadding: Boolean = false,
    mapPaddingDp: GeoVaultMapPaddingDp = GeoVaultMapPaddingDp(),
    popupAvoidanceInsetsPx: GeoVaultMapPopupAvoidanceInsetsPx = GeoVaultMapPopupAvoidanceInsetsPx(),
    showScaleBar: Boolean = false,
    suppressMapLoadErrorDialog: Boolean = false,
) {
    GeoVaultMapHost(
        modifier = modifier,
        map = map,
        mode = GeoVaultMapHostMode.Main,
        showDefaultSourceToggle = showDefaultSourceToggle,
        includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
        mapPaddingDp = mapPaddingDp,
        popupAvoidanceInsetsPx = popupAvoidanceInsetsPx,
        showScaleBar = showScaleBar,
        suppressMapLoadErrorDialog = suppressMapLoadErrorDialog,
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
    popupAvoidanceInsetsPx: GeoVaultMapPopupAvoidanceInsetsPx,
    showScaleBar: Boolean,
    suppressMapLoadErrorDialog: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val configurationNight =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val materialDark = !MaterialTheme.colors.isLight
    val streetNightForBasemap = configurationNight || materialDark
    var mapView: MapView? by remember { mutableStateOf(null) }
    val mapErrorNotice by map.errorNotice.collectAsState()
    val phase by map.phase.collectAsState()
    val currentMap by rememberUpdatedState(map)
    val mapStateBundle = remember { Bundle() }
    val baselineStreetNight = remember { mutableStateOf<Boolean?>(null) }
    var previousSuppressMapLoadErrorDialog by remember { mutableStateOf<Boolean?>(null) }

    val effectivePaddingPx = remember(density, includeDefaultFabColumnPadding, mapPaddingDp) {
        GeoVaultMapPaddingPolicy(
            includeDefaultFabColumnPadding = includeDefaultFabColumnPadding,
            mapPaddingDp = mapPaddingDp,
        ).computeViewportPaddingPx(density)
    }

    val mapUnderlayArgb = MaterialTheme.colors.background.toArgb()

    SideEffect {
        currentMap.setDefaultCameraPadding(effectivePaddingPx)
    }

    SideEffect {
        try {
            currentMap.manager.sourceManager.setStreetNightUiHintFromHost(streetNightForBasemap)
        } catch (_: IllegalStateException) {
            // Map manager not attached yet; hint will be set on the next frame.
        }
    }

    LaunchedEffect(streetNightForBasemap, phase) {
        if (phase != GeoVaultMapPhase.Ready) return@LaunchedEffect
        val previous = baselineStreetNight.value
        if (previous == null) {
            baselineStreetNight.value = streetNightForBasemap
            return@LaunchedEffect
        }
        if (previous != streetNightForBasemap) {
            baselineStreetNight.value = streetNightForBasemap
            currentMap.reapplyBasemapAfterUiModeChange()
        }
    }

    LaunchedEffect(suppressMapLoadErrorDialog, currentMap) {
        val wasSuppressed = previousSuppressMapLoadErrorDialog
        previousSuppressMapLoadErrorDialog = suppressMapLoadErrorDialog

        if (suppressMapLoadErrorDialog) {
            currentMap.dismissMapErrorNotice()
        } else if (wasSuppressed == true) {
            // Server just became reachable again; avoid showing a stale load-error dialog before
            // the map has a chance to fetch tiles over the restored transport.
            currentMap.dismissMapErrorNotice()
            currentMap.retryMapSourceLoad()
        }
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
                acquiredMapView.setBackgroundColor(mapUnderlayArgb)
                acquiredMapView.setMapPopupAvoidanceInsets(popupAvoidanceInsetsPx)
                mapView = acquiredMapView
                currentMap.attachMapView(acquiredMapView)
                acquiredMapView
            },
            update = {
                it.setBackgroundColor(mapUnderlayArgb)
                it.setMapPopupAvoidanceInsets(popupAvoidanceInsetsPx)
                // Keep night/dark hint in sync on every recomposition (Material dark without
                // UI_MODE_NIGHT_YES, theme toggles) — SideEffect alone can run after the first
                // tile-source fetch reads a stale null hint and sticks on light streets.
                try {
                    currentMap.manager.sourceManager.setStreetNightUiHintFromHost(streetNightForBasemap)
                } catch (_: IllegalStateException) {
                    // Manager not attached yet; [SideEffect] sets the hint on the next frame.
                }
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

        if (showScaleBar) {
            GeoVaultMapScaleBar(
                map = currentMap,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(GeoVaultMapScaleBarDefaults.EdgePadding),
            )
        }
    }

    val currentMapErrorNotice = mapErrorNotice
    if (!suppressMapLoadErrorDialog && currentMapErrorNotice != null) {
        GeoVaultMapErrorDialog(
            notice = currentMapErrorNotice,
            onDismiss = { currentMap.dismissMapErrorNotice() },
            onRetry = { currentMap.retryMapSourceLoad() },
        )
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
                currentMap.ensureBasemapMatchesEffectiveSelection()
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

@Composable
private fun GeoVaultMapErrorDialog(
    notice: GeoVaultMapErrorNotice,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    // Keep this as a dialog-backed surface. Host apps commonly compose map FABs,
    // loading scrims, and full-screen overlays above the map subtree; inline map
    // error UI can be covered by those app-owned layers.
    GeoVaultFormDialog(
        title = notice.title,
        onConfirm = if (notice.retryable) onRetry else onDismiss,
        onDismissRequest = onDismiss,
        confirmText = if (notice.retryable) "Retry" else "Close",
        cancelText = "Close",
        showDismissButton = notice.retryable,
    ) {
        Text(notice.message)
    }
}

private fun MapView.setMapPopupAvoidanceInsets(insets: GeoVaultMapPopupAvoidanceInsetsPx) {
    setTag(
        com.geovault.common.maps.R.id.gv_common_map_popup_avoidance_insets,
        Rect(insets.left, insets.top, insets.right, insets.bottom),
    )
}
