package com.geovault.common.maps.core

import android.os.Bundle
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
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView: MapView? by remember { mutableStateOf(null) }
    val currentController by rememberUpdatedState(controller)
    val mapStateBundle = remember { Bundle() }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).also { createdMapView ->
                    createdMapView.onCreate(mapStateBundle)
                    mapView = createdMapView
                    currentController.attachMapView(createdMapView)
                }
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

    DisposableEffect(lifecycleOwner, mapView, currentController) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                mapView?.onStart()
            }

            override fun onResume(owner: LifecycleOwner) {
                mapView?.onResume()
            }

            override fun onPause(owner: LifecycleOwner) {
                mapView?.onPause()
            }

            override fun onStop(owner: LifecycleOwner) {
                mapView?.onStop()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mapView?.onSaveInstanceState(mapStateBundle)
                mapView?.onDestroy()
                currentController.onDestroy()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onSaveInstanceState(mapStateBundle)
        }
    }

}
