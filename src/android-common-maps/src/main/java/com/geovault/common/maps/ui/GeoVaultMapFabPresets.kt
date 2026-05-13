package com.geovault.common.maps.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import com.geovault.common.maps.core.GeoVaultBaseMap
import org.maplibre.android.camera.CameraUpdateFactory

/**
 * Non-location map FAB presets (layers + zoom). For continuous position follow, see
 * [com.geovault.common.maps.ui.camerafollow.rememberGeoVaultMapHeadingFollowFabBundle]. For a
 * one-shot map jump to the device location, see
 * [com.geovault.common.maps.ui.oneshot.rememberGeoVaultGpsOneShotMyLocationFabAction].
 */
fun geoVaultLayerToggleFabAction(
    map: GeoVaultBaseMap,
    id: String = "layers",
    order: Int = 10,
    contentDescription: String = "Change the map style or layers you use for this view.",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Layers),
        contentDescription = contentDescription,
        tooltip = contentDescription,
        onTap = { map.cycleSource() },
    )
}

fun geoVaultZoomInFabAction(
    map: GeoVaultBaseMap,
    id: String = "zoom_in",
    order: Int = 40,
    contentDescription: String = "Zoom the map in.",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Add),
        contentDescription = contentDescription,
        tooltip = contentDescription,
        onTap = {
            val mapLibreMap = map.maplibreMap
            if (mapLibreMap != null) {
                map.animateCameraWithPadding(CameraUpdateFactory.zoomBy(1.0))
            }
        },
    )
}

fun geoVaultZoomOutFabAction(
    map: GeoVaultBaseMap,
    id: String = "zoom_out",
    order: Int = 50,
    contentDescription: String = "Zoom the map out.",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Remove),
        contentDescription = contentDescription,
        tooltip = contentDescription,
        onTap = {
            val mapLibreMap = map.maplibreMap
            if (mapLibreMap != null) {
                map.animateCameraWithPadding(CameraUpdateFactory.zoomBy(-1.0))
            }
        },
    )
}
