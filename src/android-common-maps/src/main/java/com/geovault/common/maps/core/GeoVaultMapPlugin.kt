package com.geovault.common.maps.core

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

interface GeoVaultMapPlugin {
    fun onMapAttached(map: MapLibreMap) = Unit
    fun onMapDetached() = Unit
    fun onStyleWillChange(map: MapLibreMap, currentStyle: Style?) = Unit
    fun onStyleLoaded(map: MapLibreMap, style: Style) = Unit
    fun onPluginDestroyed() = Unit
}
