package com.geovault.common.maps.location

import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.geovault.common.maps.core.GeoVaultBaseMap
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

private const val TAG = "PuckOverlap"
private const val OVERLAP_HIT_HALF_DP = 14f

/**
 * Makes the location puck disc translucent when it sits on a rendered icon so the marker
 * stays readable. Re-evaluates on location updates, camera idle, and map re-attachment.
 *
 * [iconLayerIds] must be icon (symbol) layers only — not cluster or label layers.
 */
@Composable
fun GeoVaultMapPuckOverlapEffect(
    map: GeoVaultBaseMap,
    plugin: MapLocationRendererPlugin,
    iconLayerIds: List<String>,
) {
    val mapView = map.getMapViewOrNull() ?: return
    val attachmentVersion by map.mapAttachmentVersion.collectAsState()
    val density = mapView.context.resources.displayMetrics.density
    val halfPx = remember(density) { OVERLAP_HIT_HALF_DP * density }
    DisposableEffect(map, plugin, iconLayerIds, halfPx, attachmentVersion) {
        val mapLibreMap = map.maplibreMap
        if (mapLibreMap == null || iconLayerIds.isEmpty()) {
            return@DisposableEffect onDispose { }
        }

        val reevaluate = {
            val overlapping = evaluateOverlap(
                mapLibreMap = mapLibreMap,
                plugin = plugin,
                halfPx = halfPx,
                iconLayerIds = iconLayerIds,
            )
            if (overlapping != null) {
                plugin.setPuckBackgroundTranslucent(overlapping)
            }
        }
        val locationListener: (android.location.Location) -> Unit = { reevaluate() }
        val cameraIdleListener = MapLibreMap.OnCameraIdleListener(reevaluate)

        plugin.addLocationListener(locationListener)
        mapLibreMap.addOnCameraIdleListener(cameraIdleListener)
        reevaluate()

        onDispose {
            plugin.removeLocationListener(locationListener)
            mapLibreMap.removeOnCameraIdleListener(cameraIdleListener)
            plugin.setPuckBackgroundTranslucent(false)
        }
    }
}

private fun evaluateOverlap(
    mapLibreMap: MapLibreMap,
    plugin: MapLocationRendererPlugin,
    halfPx: Float,
    iconLayerIds: List<String>,
): Boolean? {
    val lastLocation = plugin.getLastLocation() ?: return null
    val latLng = LatLng(lastLocation.latitude, lastLocation.longitude)
    val screenPoint = mapLibreMap.projection.toScreenLocation(latLng)
    val rect = RectF(
        screenPoint.x - halfPx,
        screenPoint.y - halfPx,
        screenPoint.x + halfPx,
        screenPoint.y + halfPx,
    )
    val features = runCatching {
        mapLibreMap.queryRenderedFeatures(rect, *iconLayerIds.toTypedArray())
    }.onFailure { throwable ->
        Log.w(TAG, "queryRenderedFeatures failed for puck overlap", throwable)
    }.getOrDefault(emptyList())
    return features.isNotEmpty()
}
