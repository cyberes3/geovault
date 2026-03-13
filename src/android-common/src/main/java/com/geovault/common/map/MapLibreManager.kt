package com.geovault.common.map

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.geovault.common.GeovaultAuthManager
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory

/**
 * Handles shared MapLibre setup, style loading, and OSM fallback.
 * Add this to activities that host a MapView.
 */
class MapLibreManager(private val activity: Activity, private val mapView: MapView) {

    val sourceManager = MapSourceManager(activity)
    
    /** True when fetchMapSources callback ran (server configured). */
    var sourcesFetched = false
        private set
        
    private var maplibreMap: MapLibreMap? = null
    private var lastAppliedSourceKey: String? = null
    private var pendingSourceKey: String? = null
    
    var onStyleLoaded: ((MapLibreMap, Style) -> Unit)? = null

    /**
     * Default padding to apply to camera updates if none is provided.
     * Format: [left, top, right, bottom] in pixels.
     */
    var defaultPadding: DoubleArray? = null

    /**
     * Helper to add a marker icon to the map style.
     */
    fun addMarkerIcon(style: Style, id: String, drawableId: Int) {
        val bitmap = MapMarkerUtils.getMarkerBitmap(activity, drawableId)
        if (bitmap != null) {
            style.addImage(id, bitmap)
        }
    }

    companion object {
        private const val TAG = "MapLibreManager"
        const val RASTER_SOURCE_ID = "geovault-raster"
        const val RASTER_LAYER_ID = "geovault-raster-layer"
        const val ANNOTATIONS_LAYER_ID = "org.maplibre.annotations.points"
        const val MAX_ZOOM_LEVEL = 25
        const val MAX_ZOOM_LEVEL_VECTOR = 25
        const val DEFAULT_POINT_ZOOM = 12.0
    }

    /** 
     * Configure base map settings like hiding logo, attribution, and disabling rotation. 
     */
    fun setupBaseMapSettings(map: MapLibreMap) {
        maplibreMap = map
        map.uiSettings.setLogoEnabled(false)
        map.uiSettings.setAttributionEnabled(false)
        map.uiSettings.isRotateGesturesEnabled = false
        map.setMaxZoomPreference(MAX_ZOOM_LEVEL.toDouble())
    }

    /**
     * Fetch the available tile sources from the Geovault Server (or use cache).
     * When completed, if the map is ready, it applies the selected source.
     */
    fun fetchMapSources(onFetched: () -> Unit = {}) {
        TileSourceCache.getTileSources(activity) { sources ->
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    if (sources != null) sourceManager.setSources(sources)
                    sourcesFetched = true
                    onFetched()
                }
            }
        }
    }

    /**
     * Applies the current [MapSourceManager] selected style/raster to the MapLibreMap.
     * Preserves camera position and zoom so the view is unchanged when switching layers.
     */
    fun applySelectedSource(map: MapLibreMap = maplibreMap!!) {
        if (activity.isDestroyed) return

        val savedCamera = map.cameraPosition
        val effectiveId = sourceManager.getEffectiveSourceId()
        val requestedSourceKey = buildSourceKey(effectiveId)

        // Avoid reapplying the same source/style while already loaded or in flight.
        if (requestedSourceKey == pendingSourceKey || (requestedSourceKey == lastAppliedSourceKey && map.style != null)) {
            return
        }
        pendingSourceKey = requestedSourceKey

        fun restoreCamera() {
            if (!activity.isDestroyed) map.setCameraPosition(savedCamera)
        }
        
        try {
            val mapMaxZoom = if (sourceManager.isVectorSource(effectiveId)) MAX_ZOOM_LEVEL_VECTOR.toDouble() else MAX_ZOOM_LEVEL.toDouble()
            map.setMaxZoomPreference(mapMaxZoom)
            
            if (sourceManager.isVectorSource(effectiveId)) {
                val styleUrl = sourceManager.getResolvedStyleUrl(effectiveId)
                if (!styleUrl.isNullOrBlank()) {
                    loadVectorStyle(map, styleUrl, requestedSourceKey) { restoreCamera() }
                } else {
                    map.setStyle(Style.Builder()) { style ->
                        if (!activity.isDestroyed) {
                            lastAppliedSourceKey = requestedSourceKey
                            pendingSourceKey = null
                            onStyleLoaded?.invoke(map, style)
                            restoreCamera()
                        }
                    }
                }
            } else {
                val rasterUrl = sourceManager.getRasterUrl(effectiveId)
                if (!rasterUrl.isNullOrBlank()) {
                    map.setStyle(Style.Builder()) { style ->
                        if (activity.isDestroyed) return@setStyle
                        try {
                            val tileSet = TileSet("2.1.0", rasterUrl).apply {
                                maxZoom = MAX_ZOOM_LEVEL.toFloat()
                            }
                            style.addSource(RasterSource(RASTER_SOURCE_ID, tileSet, 256))
                            val rasterLayer = RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID)
                            try {
                                style.addLayerBelow(rasterLayer, ANNOTATIONS_LAYER_ID)
                            } catch (_: Exception) {
                                style.addLayer(rasterLayer)
                            }
                        } catch (_: Exception) { /* ignore */ }
                        if (!activity.isDestroyed) {
                            lastAppliedSourceKey = requestedSourceKey
                            pendingSourceKey = null
                            onStyleLoaded?.invoke(map, style)
                            restoreCamera()
                        }
                    }
                } else {
                    map.setStyle(Style.Builder()) { style ->
                        if (!activity.isDestroyed) {
                            lastAppliedSourceKey = requestedSourceKey
                            pendingSourceKey = null
                            onStyleLoaded?.invoke(map, style)
                            restoreCamera()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            pendingSourceKey = null
            map.setStyle(Style.Builder()) { style ->
                if (!activity.isDestroyed) {
                    lastAppliedSourceKey = requestedSourceKey
                    onStyleLoaded?.invoke(map, style)
                    restoreCamera()
                }
            }
        }
    }

    private fun loadVectorStyle(
        map: MapLibreMap,
        styleUrl: String,
        sourceKey: String,
        restoreCamera: () -> Unit = {}
    ) {
        val serverUrl = GeovaultAuthManager.getServerUrl(activity).trimEnd('/')
        val isOurServer = serverUrl.isNotEmpty() && (styleUrl == serverUrl || styleUrl.startsWith("$serverUrl/"))
        val serverBase = if (isOurServer) java.net.URI.create(styleUrl).let { "${it.scheme}://${it.host}" } else null

        MapStyleCache.getStyleJson(activity, styleUrl, isOurServer, serverBase) { json ->
            if (activity.isDestroyed) return@getStyleJson
            if (!json.isNullOrBlank()) {
                map.setStyle(Style.Builder().fromJson(json)) { style ->
                    if (!activity.isDestroyed) {
                        lastAppliedSourceKey = sourceKey
                        pendingSourceKey = null
                        onStyleLoaded?.invoke(map, style)
                        restoreCamera()
                    }
                }
            } else {
                pendingSourceKey = null
                Toast.makeText(activity, "Map style unavailable, falling back to basic map.", Toast.LENGTH_SHORT).show()
                loadOsmFallback(map, restoreCamera)
            }
        }
    }

    fun isCurrentSourceApplied(map: MapLibreMap = maplibreMap!!): Boolean {
        val requestedSourceKey = buildSourceKey(sourceManager.getEffectiveSourceId())
        return requestedSourceKey == pendingSourceKey || (requestedSourceKey == lastAppliedSourceKey && map.style != null)
    }

    private fun buildSourceKey(effectiveId: String): String {
        return if (sourceManager.isVectorSource(effectiveId)) {
            "vector:$effectiveId:${sourceManager.getResolvedStyleUrl(effectiveId).orEmpty()}"
        } else {
            "raster:$effectiveId:${sourceManager.getRasterUrl(effectiveId).orEmpty()}"
        }
    }

    /** 
     * Load OSM raster as fallback when vector (MapTiler) street style fails. 
     * Handles adding the raster layer below point annotations if present.
     */
    fun loadOsmFallback(map: MapLibreMap, restoreCamera: () -> Unit = {}) {
        val rasterUrl = sourceManager.getStreetFallbackRasterUrl()
        if (rasterUrl.isNullOrBlank()) {
            map.setStyle(Style.Builder()) { style ->
                if (!activity.isDestroyed) {
                    onStyleLoaded?.invoke(map, style)
                    restoreCamera()
                }
            }
            return
        }
        
        map.setMaxZoomPreference(MAX_ZOOM_LEVEL.toDouble())
        map.setStyle(Style.Builder()) { style ->
            if (activity.isDestroyed) return@setStyle
            try {
                style.addSource(RasterSource(RASTER_SOURCE_ID, TileSet("2.1.0", rasterUrl).apply { maxZoom = MAX_ZOOM_LEVEL.toFloat() }, 256))
                val rasterLayer = RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID)
                if (!activity.isDestroyed) {
                    onStyleLoaded?.invoke(map, style)
                    restoreCamera()
                }
                
                mapView.post {
                    if (activity.isDestroyed) return@post
                    val s = map.style ?: return@post
                    try {
                        // Attempt to place below our direct track layer or annotations
                        try {
                            s.addLayerBelow(rasterLayer, "track-outline-layer")
                        } catch (e: Exception) {
                            s.addLayerBelow(rasterLayer, ANNOTATIONS_LAYER_ID)
                        }
                    } catch (e: Exception) {
                        try {
                            // First, try adding below first symbol layer
                            val firstSymbolLayer = s.layers.firstOrNull { it is org.maplibre.android.style.layers.SymbolLayer }
                            if (firstSymbolLayer != null) {
                                s.addLayerBelow(rasterLayer, firstSymbolLayer.id)
                            } else {
                                s.addLayer(rasterLayer)
                            }
                        } catch (e2: Exception) {
                            Log.e(TAG, "addLayer failed", e2)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadOsmFallback exception", e)
            }
        }
    }

    /**
     * Animate camera to a new position while ensuring specific viewport padding is applied.
     * This prevents CameraUpdate objects (like newLatLngBounds) from resetting padding to zero.
     * If [padding] is null, [defaultPadding] is used.
     */
    fun animateCameraWithPadding(
        map: MapLibreMap,
        update: CameraUpdate,
        padding: DoubleArray? = null,
        durationMs: Int = 300,
        callback: MapLibreMap.CancelableCallback? = null
    ) {
        val lastPadding = padding ?: defaultPadding
        val position = update.getCameraPosition(map)
        if (position != null) {
            val builder = CameraPosition.Builder(position)
            if (lastPadding != null) builder.padding(lastPadding)
            map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), durationMs, callback)
        }
    }

    /**
     * Move camera to a new position while ensuring specific viewport padding is applied.
     * If [padding] is null, [defaultPadding] is used.
     */
    fun moveCameraWithPadding(
        map: MapLibreMap,
        update: CameraUpdate,
        padding: DoubleArray? = null
    ) {
        val lastPadding = padding ?: defaultPadding
        val position = update.getCameraPosition(map)
        if (position != null) {
            val builder = CameraPosition.Builder(position)
            if (lastPadding != null) builder.padding(lastPadding)
            map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
        }
    }
}
