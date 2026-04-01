package com.geovault.common.maps.core

import android.content.Context
import android.content.res.Configuration
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

class MapLibreManager(private val context: Context) {
    val sourceManager = MapSourceManager(context)

    fun applySelectedSource(map: MapLibreMap) {
        // Keep this implementation intentionally lean for now.
        // Apps set full style URLs if they need richer custom sources.
        val selected = sourceManager.getSelectedSourceId()
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val style = when (selected) {
            "satellite" -> "https://demotiles.maplibre.org/style.json"
            "topo" -> "https://demotiles.maplibre.org/style.json"
            else -> if (isNight) "https://demotiles.maplibre.org/style.json" else "https://demotiles.maplibre.org/style.json"
        }
        val savedCamera = map.cameraPosition
        map.setStyle(style) {
            map.cameraPosition = CameraPosition.Builder(savedCamera).build()
        }
    }

    fun moveCameraWithPadding(map: MapLibreMap, cameraUpdate: CameraUpdate) {
        map.moveCamera(cameraUpdate)
    }

    fun animateCameraWithPadding(map: MapLibreMap, cameraUpdate: CameraUpdate) {
        map.animateCamera(cameraUpdate)
    }

    companion object {
        const val DEFAULT_POINT_ZOOM = 15.0
        val DEFAULT_WORLD_CENTER = LatLng(0.0, 0.0)
    }
}
