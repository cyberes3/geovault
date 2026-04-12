package com.geovault.common.map

import android.util.Log
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.style.layers.SymbolLayer

/**
 * The MapLibre annotation plugin's [SymbolManager.onDestroy] removes map listeners but does not
 * remove the manager's symbol layer or GeoJSON source from the style. If a new [SymbolManager]
 * is created on the same style, orphaned layers keep drawing old markers alongside new ones.
 */
object SymbolManagerStyleCleanup {

    private const val TAG = "SymbolManagerStyleCleanup"

    fun removeFromStyle(style: Style, manager: SymbolManager) {
        try {
            val layerId = manager.layerId
            val layer = style.getLayer(layerId) ?: return
            val sourceId = (layer as? SymbolLayer)?.sourceId
            style.removeLayer(layerId)
            if (sourceId != null && style.getSource(sourceId) != null) {
                style.removeSource(sourceId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeFromStyle", e)
        }
    }
}
