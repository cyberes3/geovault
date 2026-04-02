package com.geovault.common.maps.core

import android.util.Log
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer

object SymbolManagerStyleCleanup {
    fun removeFromStyle(style: Style, layerId: String) {
        try {
            val layer = style.getLayer(layerId) ?: return
            val sourceId = (layer as? SymbolLayer)?.sourceId
            style.removeLayer(layerId)
            if (sourceId != null && style.getSource(sourceId) != null) {
                style.removeSource(sourceId)
            }
        } catch (error: Exception) {
            Log.w(TAG, "removeFromStyle", error)
        }
    }

    private const val TAG = "SymbolManagerStyleCleanup"
}
