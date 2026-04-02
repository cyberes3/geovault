package com.geovault.common.maps.core

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicLong

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
    val hotHoldGeneration = remember { AtomicLong(0L) }

    fun cancelMainHotHold() {
        hotHoldGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(HOT_HOLD_TOKEN)
    }

    fun scheduleMainHotHold(view: MapView?) {
        if (view == null) return
        val generation = hotHoldGeneration.incrementAndGet()
        val runnable = Runnable {
            if (hotHoldGeneration.get() != generation) return@Runnable
            if (currentController.getMapViewOrNull() !== view) return@Runnable
            view.onPause()
            view.onStop()
        }
        mainHandler.removeCallbacksAndMessages(HOT_HOLD_TOKEN)
        mainHandler.postAtTime(
            runnable,
            HOT_HOLD_TOKEN,
            SystemClock.uptimeMillis() + DEFAULT_MAIN_MAP_HOT_HOLD_MS,
        )
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                val acquiredMapView = when (mapMode) {
                    GeoVaultMapMode.Standard -> currentController.createTransientMapView(mapStateBundle)
                    GeoVaultMapMode.Main -> currentController.acquireRetainedMapView(mapStateBundle)
                }
                mapView = acquiredMapView
                currentController.attachMapView(acquiredMapView)
                acquiredMapView
            },
            update = {
                if (mapView !== it) {
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
                    cancelMainHotHold()
                }
                mapView?.onStart()
                currentController.ensureInteractiveGestures()
            }

            override fun onResume(owner: LifecycleOwner) {
                if (mapMode == GeoVaultMapMode.Main) {
                    cancelMainHotHold()
                }
                mapView?.onResume()
                currentController.ensureInteractiveGestures()
            }

            override fun onPause(owner: LifecycleOwner) {
                if (mapMode == GeoVaultMapMode.Main) {
                    scheduleMainHotHold(mapView)
                } else {
                    mapView?.onPause()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (mapMode == GeoVaultMapMode.Main) {
                    scheduleMainHotHold(mapView)
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
            lifecycleOwner.lifecycle.removeObserver(observer)
            val activeMapView = mapView
            if (mapMode == GeoVaultMapMode.Main) {
                scheduleMainHotHold(activeMapView)
                activeMapView?.onSaveInstanceState(mapStateBundle)
            } else {
                cancelMainHotHold()
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
private const val DEFAULT_MAIN_MAP_HOT_HOLD_MS = 30_000L
private val HOT_HOLD_TOKEN = Any()
