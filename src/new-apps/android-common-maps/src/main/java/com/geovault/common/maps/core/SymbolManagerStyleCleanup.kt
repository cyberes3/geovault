package com.geovault.common.maps.core

import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager

object SymbolManagerStyleCleanup {
    fun removeFromStyle(style: Style, manager: SymbolManager) {
        val layer = style.getLayer(manager.layerId)
        if (layer != null) {
            style.removeLayer(layer)
        }
    }
}
