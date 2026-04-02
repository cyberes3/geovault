package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

class MapLibreManager(
    private val context: Context,
    private val mapView: MapView,
) {
    val sourceManager = MapSourceManager(context)
    var sourcesFetched = false
        private set
    private var maplibreMap: MapLibreMap? = null
    private var lastAppliedSourceKey: String? = null
    private var pendingSourceKey: String? = null
    var onStyleLoaded: ((MapLibreMap, Style) -> Unit)? = null
    var defaultPadding: DoubleArray? = null

    fun setupBaseMapSettings(map: MapLibreMap) {
        maplibreMap = map
        map.uiSettings.setLogoEnabled(false)
        map.uiSettings.setAttributionEnabled(false)
        map.uiSettings.isScrollGesturesEnabled = true
        map.uiSettings.isZoomGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isDoubleTapGesturesEnabled = true
        map.uiSettings.isRotateGesturesEnabled = false
        map.setLatLngBoundsForCameraTarget(LatLngBounds.world())
        map.setMaxZoomPreference(MAX_ZOOM_LEVEL.toDouble())
    }

    fun fetchMapSources(onFetched: () -> Unit = {}) {
        TileSourceCache.getTileSources(context) { sources ->
            if (sources != null) {
                sourceManager.setSources(sources)
            }
            sourcesFetched = true
            onFetched()
        }
    }

    fun applySelectedSource(map: MapLibreMap = requireNotNull(maplibreMap)) {
        val savedCamera = map.cameraPosition
        val effectiveId = sourceManager.getEffectiveSourceId()
        val requestedSourceKey = buildSourceKey(effectiveId)
        if (requestedSourceKey == pendingSourceKey || (requestedSourceKey == lastAppliedSourceKey && map.style != null)) {
            return
        }
        pendingSourceKey = requestedSourceKey

        fun restoreCamera() {
            map.setCameraPosition(savedCamera)
        }

        try {
            val mapMaxZoom = if (sourceManager.isVectorSource(effectiveId)) {
                MAX_ZOOM_LEVEL_VECTOR.toDouble()
            } else {
                MAX_ZOOM_LEVEL.toDouble()
            }
            map.setMaxZoomPreference(mapMaxZoom)

            if (sourceManager.isVectorSource(effectiveId)) {
                val styleUrl = sourceManager.getResolvedStyleUrl(effectiveId)
                if (!styleUrl.isNullOrBlank()) {
                    loadVectorStyle(
                        map = map,
                        styleUrl = styleUrl,
                        sourceKey = requestedSourceKey,
                        restoreCamera = ::restoreCamera,
                    )
                } else {
                    map.setStyle(Style.Builder()) { style ->
                        lastAppliedSourceKey = requestedSourceKey
                        pendingSourceKey = null
                        onStyleLoaded?.invoke(map, style)
                        restoreCamera()
                    }
                }
            } else {
                val rasterUrl = sourceManager.getRasterUrl(effectiveId)
                if (!rasterUrl.isNullOrBlank()) {
                    map.setStyle(Style.Builder()) { style ->
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
                        } catch (_: Exception) {
                        }
                        lastAppliedSourceKey = requestedSourceKey
                        pendingSourceKey = null
                        onStyleLoaded?.invoke(map, style)
                        restoreCamera()
                    }
                } else {
                    map.setStyle(Style.Builder()) { style ->
                        lastAppliedSourceKey = requestedSourceKey
                        pendingSourceKey = null
                        onStyleLoaded?.invoke(map, style)
                        restoreCamera()
                    }
                }
            }
        } catch (_: Exception) {
            pendingSourceKey = null
            map.setStyle(Style.Builder()) { style ->
                lastAppliedSourceKey = requestedSourceKey
                onStyleLoaded?.invoke(map, style)
                restoreCamera()
            }
        }
    }

    private fun loadVectorStyle(
        map: MapLibreMap,
        styleUrl: String,
        sourceKey: String,
        restoreCamera: () -> Unit = {},
    ) {
        val serverUrl = com.geovault.common.GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        val isOurServer = serverUrl.isNotEmpty() && (styleUrl == serverUrl || styleUrl.startsWith("$serverUrl/"))
        val serverBase = if (isOurServer) {
            java.net.URI.create(styleUrl).let { "${it.scheme}://${it.host}" }
        } else {
            null
        }

        MapStyleCache.getStyleJson(context, styleUrl, isOurServer, serverBase) { json ->
            if (!json.isNullOrBlank()) {
                map.setStyle(Style.Builder().fromJson(json)) { style ->
                    lastAppliedSourceKey = sourceKey
                    pendingSourceKey = null
                    onStyleLoaded?.invoke(map, style)
                    restoreCamera()
                }
            } else {
                pendingSourceKey = null
                Toast.makeText(context, "Map style unavailable, falling back to basic map.", Toast.LENGTH_SHORT).show()
                loadOsmFallback(map, restoreCamera)
            }
        }
    }

    fun isCurrentSourceApplied(map: MapLibreMap = requireNotNull(maplibreMap)): Boolean {
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

    fun loadOsmFallback(map: MapLibreMap, restoreCamera: () -> Unit = {}) {
        val rasterUrl = sourceManager.getStreetFallbackRasterUrl()
        if (rasterUrl.isNullOrBlank()) {
            map.setStyle(Style.Builder()) { style ->
                onStyleLoaded?.invoke(map, style)
                restoreCamera()
            }
            return
        }

        map.setMaxZoomPreference(MAX_ZOOM_LEVEL.toDouble())
        map.setStyle(Style.Builder()) { style ->
            try {
                style.addSource(
                    RasterSource(
                        RASTER_SOURCE_ID,
                        TileSet("2.1.0", rasterUrl).apply { maxZoom = MAX_ZOOM_LEVEL.toFloat() },
                        256,
                    ),
                )
                val rasterLayer = RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID)
                onStyleLoaded?.invoke(map, style)
                restoreCamera()
                mapView.post {
                    val styleInMap = map.style ?: return@post
                    try {
                        try {
                            styleInMap.addLayerBelow(rasterLayer, "track-outline-layer")
                        } catch (_: Exception) {
                            styleInMap.addLayerBelow(rasterLayer, ANNOTATIONS_LAYER_ID)
                        }
                    } catch (_: Exception) {
                        try {
                            val firstSymbolLayer = styleInMap.layers.firstOrNull { it is SymbolLayer }
                            if (firstSymbolLayer != null) {
                                styleInMap.addLayerBelow(rasterLayer, firstSymbolLayer.id)
                            } else {
                                styleInMap.addLayer(rasterLayer)
                            }
                        } catch (secondaryError: Exception) {
                            Log.e(TAG, "addLayer failed", secondaryError)
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "loadOsmFallback exception", error)
            }
        }
    }

    fun animateCameraWithPadding(
        map: MapLibreMap,
        update: CameraUpdate,
        padding: DoubleArray? = null,
        durationMs: Int = 300,
        callback: MapLibreMap.CancelableCallback? = null,
    ) {
        val lastPadding = padding ?: defaultPadding
        val position = update.getCameraPosition(map)
        if (position != null) {
            val builder = CameraPosition.Builder(position)
            if (lastPadding != null) builder.padding(lastPadding)
            map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), durationMs, callback)
        }
    }

    fun moveCameraWithPadding(
        map: MapLibreMap,
        update: CameraUpdate,
        padding: DoubleArray? = null,
    ) {
        val lastPadding = padding ?: defaultPadding
        val position = update.getCameraPosition(map)
        if (position != null) {
            val builder = CameraPosition.Builder(position)
            if (lastPadding != null) builder.padding(lastPadding)
            map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
        }
    }

    fun addMarkerIcon(style: Style, id: String, drawableId: Int) {
        val bitmap = MapMarkerUtils.getMarkerBitmap(context, drawableId)
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
        val DEFAULT_WORLD_CENTER = LatLng(0.0, 0.0)
    }
}
