package com.geovault.common.maps.core

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

interface GeoVaultMapPlugin {
    fun onMapReady(map: MapLibreMap) = Unit
    fun onStyleWillChange(map: MapLibreMap, currentStyle: Style?) = Unit
    fun onStyleLoaded(map: MapLibreMap, style: Style) = Unit
    fun onDestroy() = Unit
}
