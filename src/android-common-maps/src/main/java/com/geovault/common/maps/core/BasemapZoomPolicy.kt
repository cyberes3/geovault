package com.geovault.common.maps.core

import org.maplibre.android.maps.MapLibreMap

/**
 * Resolves zoom limits for the active basemap and applies them to a
 * [MapLibreMap]. The constants are the same in [BasemapZoomPolicy.MIN_ZOOM]
 * and [BasemapZoomPolicy.MAX_ZOOM] for raster + vector today, but the type
 * hierarchy keeps the dispatch explicit so a future change (e.g. tighter
 * raster cap) does not need to touch every call site.
 */
internal class BasemapZoomPolicy {

    fun applyForRaster(map: MapLibreMap) = apply(map, MAX_ZOOM_RASTER)
    fun applyForVector(map: MapLibreMap) = apply(map, MAX_ZOOM_VECTOR)

    fun applyFor(map: MapLibreMap, basemap: ResolvedBasemap) {
        when (basemap) {
            is ResolvedBasemap.Raster -> applyForRaster(map)
            is ResolvedBasemap.Vector -> applyForVector(map)
        }
    }

    fun maxZoomFor(basemap: ResolvedBasemap): Double = when (basemap) {
        is ResolvedBasemap.Raster -> MAX_ZOOM_RASTER
        is ResolvedBasemap.Vector -> MAX_ZOOM_VECTOR
    }

    private fun apply(map: MapLibreMap, maxZoom: Double) {
        map.setMinZoomPreference(MIN_ZOOM)
        map.setMaxZoomPreference(maxZoom)
    }

    companion object {
        const val MIN_ZOOM_LEVEL = 1
        // Raster tiles top out around 18-19; MapLibre keeps rendering the finest
        // available tiles past that but allows pinch-zooming in further, which lets
        // survey/tracker users read dense point clusters without the map "hitting
        // a wall".
        const val MAX_ZOOM_LEVEL = 25
        const val MAX_ZOOM_LEVEL_VECTOR = 25

        val MIN_ZOOM: Double = MIN_ZOOM_LEVEL.toDouble()
        val MAX_ZOOM_RASTER: Double = MAX_ZOOM_LEVEL.toDouble()
        val MAX_ZOOM_VECTOR: Double = MAX_ZOOM_LEVEL_VECTOR.toDouble()
    }
}
