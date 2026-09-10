package com.geovault.common.maps.render

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

data class GeoJsonSelectionMarkerVisual(
    val pointId: String,
    val latitude: Double,
    val longitude: Double,
    val bitmap: Bitmap,
    val iconSize: Float,
    val iconRotationDegrees: Float,
    val iconAnchor: String,
)

/**
 * Compose-observed selected-marker visual. [GeoJsonRenderPlugin.setSelectedPointId] writes this
 * so [com.geovault.common.maps.core.GeoVaultMainMapView] can paint above the map surface.
 */
class GeoJsonSelectionMarkerState {
    var visual: GeoJsonSelectionMarkerVisual? by mutableStateOf(null)
        internal set

    internal fun show(visual: GeoJsonSelectionMarkerVisual) {
        this.visual = visual
        // The write happens inside MapLibre's tap callback, outside Compose's normal input
        // dispatch, so flush the global snapshot now to schedule a draw frame this vsync instead
        // of waiting for an unrelated recomposition to force one.
        Snapshot.sendApplyNotifications()
    }

    internal fun clear() {
        visual = null
        Snapshot.sendApplyNotifications()
    }
}
