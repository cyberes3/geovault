package com.geovault.common.maps.core

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

enum class GeoVaultMapMode {
    Standard,
    Main,
}

@Composable
fun rememberGeoVaultMapController(): GeoVaultMapController {
    val context = LocalContext.current
    return remember(context) {
        GeoVaultMapController(context)
    }
}

@Composable
fun GeoVaultMap(
    modifier: Modifier = Modifier,
    controller: GeoVaultMapController = rememberGeoVaultMapController(),
    showDefaultSourceToggle: Boolean = false,
    mapMode: GeoVaultMapMode = GeoVaultMapMode.Standard,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView: MapView? by remember { mutableStateOf(null) }
    val currentController by rememberUpdatedState(controller)
    val mapStateBundle = remember { Bundle() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val deferredPauseStop = remember {
        Runnable {
            val activeMapView = mapView
            activeMapView?.onPause()
            activeMapView?.onStop()
            Log.d(TAG, "GeoVaultMap.mainHotHold: applied deferred pause/stop view=${activeMapView?.hashCode()}")
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val acquiredMapView = when (mapMode) {
                    GeoVaultMapMode.Standard -> currentController.createTransientMapView(mapStateBundle)
                    GeoVaultMapMode.Main -> currentController.acquireRetainedMapView(mapStateBundle)
                }
                Log.d(
                    TAG,
                    "GeoVaultMap.factory: mapMode=$mapMode view=${acquiredMapView.hashCode()}",
                )
                mapView = acquiredMapView
                currentController.attachMapView(acquiredMapView)
                acquiredMapView
            },
            update = {
                if (mapView !== it) {
                    Log.d(TAG, "GeoVaultMap.update: rebinding view=${it.hashCode()}")
                    mapView = it
                    currentController.attachMapView(it)
                }
            },
        )

        if (showDefaultSourceToggle) {
            Button(
                onClick = { currentController.cycleSource() },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Text("Layers")
            }
        }
    }

    DisposableEffect(lifecycleOwner, currentController, mapMode) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (mapMode == GeoVaultMapMode.Main) {
                    mainHandler.removeCallbacks(deferredPauseStop)
                }
                mapView?.onStart()
            }

            override fun onResume(owner: LifecycleOwner) {
                if (mapMode == GeoVaultMapMode.Main) {
                    mainHandler.removeCallbacks(deferredPauseStop)
                }
                mapView?.onResume()
            }

            override fun onPause(owner: LifecycleOwner) {
                if (mapMode == GeoVaultMapMode.Main) {
                    mainHandler.removeCallbacks(deferredPauseStop)
                    mainHandler.postDelayed(deferredPauseStop, MAIN_MAP_HOT_HOLD_MS)
                    Log.d(TAG, "GeoVaultMap.mainHotHold: scheduled deferred pause/stop")
                } else {
                    mapView?.onPause()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (mapMode == GeoVaultMapMode.Main) {
                    mainHandler.removeCallbacks(deferredPauseStop)
                    mainHandler.postDelayed(deferredPauseStop, MAIN_MAP_HOT_HOLD_MS)
                    Log.d(TAG, "GeoVaultMap.mainHotHold: rescheduled deferred pause/stop onStop")
                } else {
                    mapView?.onStop()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mapView?.onSaveInstanceState(mapStateBundle)
                if (mapMode == GeoVaultMapMode.Standard) {
                    mapView?.onDestroy()
                    currentController.onDestroy()
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            Log.d(TAG, "GeoVaultMap.onDispose: mapMode=$mapMode view=${mapView?.hashCode()}")
            lifecycleOwner.lifecycle.removeObserver(observer)
            val activeMapView = mapView
            if (mapMode == GeoVaultMapMode.Main) {
                mainHandler.removeCallbacks(deferredPauseStop)
                mainHandler.postDelayed(deferredPauseStop, MAIN_MAP_HOT_HOLD_MS)
                activeMapView?.onSaveInstanceState(mapStateBundle)
                Log.d(TAG, "GeoVaultMap.mainHotHold: kept hot on dispose")
            } else {
                activeMapView?.onPause()
                activeMapView?.onStop()
                activeMapView?.onSaveInstanceState(mapStateBundle)
            }
            if (mapMode == GeoVaultMapMode.Standard) {
                activeMapView?.onDestroy()
                currentController.onDestroy()
            }
        }
    }

}

private const val TAG = "GeoVaultMap"
private const val MAIN_MAP_HOT_HOLD_MS = 30_000L
