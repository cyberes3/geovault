package com.geovault.common.maps.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Remove
import com.geovault.common.maps.core.GeoVaultBaseMap
import org.maplibre.android.camera.CameraUpdateFactory

/**
 * Non-location map FAB presets (layers + zoom). For GPS, see
 * [com.geovault.common.maps.ui.recenter.rememberGeoVaultGpsRecenterFabAction] and
 * [com.geovault.common.maps.ui.camerafollow.rememberGeoVaultMapCameraFollowFabBundle].
 */
fun geoVaultLayerToggleFabAction(
    map: GeoVaultBaseMap,
    id: String = "layers",
    order: Int = 10,
    contentDescription: String = "Toggle map source",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Layers),
        contentDescription = contentDescription,
        onTap = { map.cycleSource() },
    )
}

fun geoVaultZoomInFabAction(
    map: GeoVaultBaseMap,
    id: String = "zoom_in",
    order: Int = 40,
    contentDescription: String = "Zoom in",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Add),
        contentDescription = contentDescription,
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
    contentDescription: String = "Zoom out",
): GeoVaultMapFabAction {
    return GeoVaultMapFabAction(
        id = id,
        order = order,
        icon = GeoVaultMapFabIcon.Vector(Icons.Default.Remove),
        contentDescription = contentDescription,
        onTap = {
            val mapLibreMap = map.maplibreMap
            if (mapLibreMap != null) {
                map.animateCameraWithPadding(CameraUpdateFactory.zoomBy(-1.0))
            }
        },
    )
}
