package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import android.widget.Toast
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
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
    /** Invoked when a remote/vector style cannot be applied (network/cache miss, parse errors, etc.). */
    var onStyleLoadFailed: ((String) -> Unit)? = null
    var defaultPadding: DoubleArray? = null
        set(value) {
            field = value
            applyViewportPadding(value)
        }

    fun setupBaseMapSettings(map: MapLibreMap) {
        maplibreMap = map
        map.uiSettings.setLogoEnabled(false)
        map.uiSettings.setAttributionEnabled(false)
        map.uiSettings.setCompassEnabled(false)
        map.uiSettings.isScrollGesturesEnabled = true
        map.uiSettings.isZoomGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true
        map.uiSettings.isDoubleTapGesturesEnabled = true
        map.uiSettings.isRotateGesturesEnabled = false
        // Unbounded camera target: MapLibre Native is initialised with `ConstrainMode::HeightOnly`
        // and an empty `LatLngBounds`, which clamps latitude to the Mercator band (≈ ±85°) while
        // letting longitude wrap infinitely via world copies. Passing any bounded `LatLngBounds`
        // here (including `LatLngBounds.world()`) disables horizontal wrap, so we explicitly
        // restore the native default with `null`.
        map.setLatLngBoundsForCameraTarget(null)
        applyZoomPreferences(map, MAX_ZOOM_LEVEL.toDouble())
        applyViewportPadding(defaultPadding)
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

    /**
     * Applies the selected basemap.
     *
     * @return `true` when a full style load was started and callers should wait for
     * [onStyleLoaded]; `false` when the selection was applied in-place (or was already active).
     */
    fun applySelectedSource(map: MapLibreMap = requireNotNull(maplibreMap)): Boolean {
        val savedCamera = map.cameraPosition
        val effectiveId = sourceManager.getEffectiveSourceId()
        val requestedSourceKey = buildSourceKey(effectiveId)
        val currentStyle = map.style
        when (
            MapSourceApplyPlanner.plan(
                requestedSourceKey = requestedSourceKey,
                pendingSourceKey = pendingSourceKey,
                lastAppliedSourceKey = lastAppliedSourceKey,
                hasCurrentStyle = currentStyle != null,
            )
        ) {
            MapSourceApplyPlan.Noop -> return false
            MapSourceApplyPlan.ReplaceRasterInPlace -> {
                val rasterUrl = sourceManager.getRasterUrl(effectiveId)
                if (!rasterUrl.isNullOrBlank() && currentStyle != null) {
                    pendingSourceKey = requestedSourceKey
                    // Raster-to-raster changes are pure basemap swaps. Keeping the current
                    // style avoids GeoJSON plugin reattachment and the 100k-point redraw that
                    // would otherwise happen through setStyle().
                    if (replaceRasterSourceInPlace(currentStyle, rasterUrl)) {
                        lastAppliedSourceKey = requestedSourceKey
                        pendingSourceKey = null
                        return false
                    }
                }
            }
            MapSourceApplyPlan.LoadFullStyle -> Unit
        }
        pendingSourceKey = requestedSourceKey

        fun restoreCamera() {
            map.setCameraPosition(savedCamera)
            // Style reload can yield a snapshot without our viewport padding, so re-apply
            // whatever padding was active on the saved camera. Using `defaultPadding`
            // unconditionally would clobber non-zero padding a prior bounds-fit baked into
            // the camera (e.g., the Survey map's initial `snapFitAll` fits with a
            // drawer+FAB inset while the host's `defaultPadding` is `[0, 0, 0, 0]`), which
            // is what made the map appear to shift the first time a layer was changed.
            val paddingToRestore = savedCamera.padding?.takeIf { it.size == 4 } ?: defaultPadding
            applyViewportPadding(paddingToRestore)
        }

        try {
            val mapMaxZoom = if (sourceManager.isVectorSource(effectiveId)) {
                MAX_ZOOM_LEVEL_VECTOR.toDouble()
            } else {
                MAX_ZOOM_LEVEL.toDouble()
            }
            applyZoomPreferences(map, mapMaxZoom)

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
                        applyZoomPreferences(map, mapMaxZoom)
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
                        applyZoomPreferences(map, mapMaxZoom)
                        try {
                            style.addSource(buildRasterSource(rasterUrl))
                            addRasterLayer(style, RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID))
                        } catch (rasterError: Exception) {
                            Log.e(TAG, "Failed applying raster source for selected map source", rasterError)
                            onStyleLoadFailed?.invoke(
                                rasterError.message ?: "raster_source_apply_failed"
                            )
                        }
                        lastAppliedSourceKey = requestedSourceKey
                        pendingSourceKey = null
                        onStyleLoaded?.invoke(map, style)
                        restoreCamera()
                    }
                    return true
                } else {
                    map.setStyle(Style.Builder()) { style ->
                        applyZoomPreferences(map, mapMaxZoom)
                        lastAppliedSourceKey = requestedSourceKey
                        pendingSourceKey = null
                        onStyleLoaded?.invoke(map, style)
                        restoreCamera()
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            pendingSourceKey = null
            Log.e(
                TAG,
                "Map source apply failed before style load; applying empty style. " +
                    "effectiveId=${sourceManager.getEffectiveSourceId()}",
                e,
            )
            onStyleLoadFailed?.invoke(e.message ?: e.javaClass.simpleName)
            map.setStyle(Style.Builder()) { style ->
                applyZoomPreferences(map, MAX_ZOOM_LEVEL.toDouble())
                lastAppliedSourceKey = requestedSourceKey
                onStyleLoaded?.invoke(map, style)
                restoreCamera()
            }
            return true
        }
        return true
    }

    private fun replaceRasterSourceInPlace(style: Style, rasterUrl: String): Boolean {
        return try {
            if (style.getLayer(RASTER_LAYER_ID) != null) {
                style.removeLayer(RASTER_LAYER_ID)
            }
            if (style.getSource(RASTER_SOURCE_ID) != null) {
                style.removeSource(RASTER_SOURCE_ID)
            }
            style.addSource(buildRasterSource(rasterUrl))
            addRasterLayer(style, RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID))
            true
        } catch (error: Exception) {
            pendingSourceKey = null
            Log.w(TAG, "Failed replacing raster source in-place; falling back to full style load", error)
            false
        }
    }

    private fun buildRasterSource(rasterUrl: String): RasterSource {
        return RasterSource(
            RASTER_SOURCE_ID,
            TileSet("2.1.0", rasterUrl).apply { maxZoom = MAX_ZOOM_LEVEL.toFloat() },
            256,
        )
    }

    private fun addRasterLayer(style: Style, rasterLayer: RasterLayer) {
        try {
            style.addLayerBelow(rasterLayer, firstLayerAboveBaseMap(style) ?: ANNOTATIONS_LAYER_ID)
        } catch (layerError: Exception) {
            Log.w(TAG, "Raster layer insertion fallback to top-layer add", layerError)
            style.addLayer(rasterLayer)
        }
    }

    private fun firstLayerAboveBaseMap(style: Style): String? {
        return style.layers.firstOrNull { it.id != RASTER_LAYER_ID }?.id
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
                    applyZoomPreferences(map, MAX_ZOOM_LEVEL_VECTOR.toDouble())
                    lastAppliedSourceKey = sourceKey
                    pendingSourceKey = null
                    onStyleLoaded?.invoke(map, style)
                    restoreCamera()
                }
            } else {
                pendingSourceKey = null
                if (isOurServer) {
                    Log.e(
                        TAG,
                        "GeoVault server map style not loaded (empty fetch or bad JSON path); " +
                            "using OSM raster fallback. styleUrl=$styleUrl " +
                            "configuredServer=${com.geovault.common.GeovaultAuthManager.getServerUrl(context).trimEnd('/')} " +
                            "(see MapStyleCache for HTTP/exception details)",
                    )
                } else {
                    Log.w(
                        TAG,
                        "Vector map style JSON missing; loading OSM raster fallback. styleUrl=$styleUrl " +
                            "(see MapStyleCache for HTTP details)",
                    )
                }
                onStyleLoadFailed?.invoke("Map style unavailable for $styleUrl")
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
                applyZoomPreferences(map, MAX_ZOOM_LEVEL.toDouble())
                onStyleLoaded?.invoke(map, style)
                restoreCamera()
            }
            return
        }

        applyZoomPreferences(map, MAX_ZOOM_LEVEL.toDouble())
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
                applyZoomPreferences(map, MAX_ZOOM_LEVEL.toDouble())
                onStyleLoaded?.invoke(map, style)
                restoreCamera()
                mapView.post {
                    val styleInMap = map.style ?: return@post
                    try {
                        try {
                            styleInMap.addLayerBelow(rasterLayer, "track-outline-layer")
                        } catch (outlineError: Exception) {
                            Log.w(TAG, "Raster layer insert below track outline failed, retrying annotations layer", outlineError)
                            styleInMap.addLayerBelow(rasterLayer, ANNOTATIONS_LAYER_ID)
                        }
                    } catch (annotationsError: Exception) {
                        Log.w(TAG, "Raster layer insert below annotations failed, retrying symbol fallback", annotationsError)
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

    private fun applyZoomPreferences(map: MapLibreMap, maxZoom: Double) {
        map.setMinZoomPreference(MIN_ZOOM_LEVEL.toDouble())
        map.setMaxZoomPreference(maxZoom)
    }

    fun reapplyZoomPreferences(map: MapLibreMap) {
        val effectiveId = sourceManager.getEffectiveSourceId()
        val maxZoom = if (sourceManager.isVectorSource(effectiveId)) {
            MAX_ZOOM_LEVEL_VECTOR.toDouble()
        } else {
            MAX_ZOOM_LEVEL.toDouble()
        }
        applyZoomPreferences(map, maxZoom)
    }

    fun animateCameraWithPadding(
        map: MapLibreMap,
        update: CameraUpdate,
        padding: DoubleArray? = null,
        durationMs: Int = 300,
        callback: MapLibreMap.CancelableCallback? = null,
        maxZoom: Double = resolveEffectiveMaxZoom(),
    ) {
        val lastPadding = padding ?: defaultPadding
        val position = update.getCameraPosition(map) ?: return
        val clampedZoom = position.zoom
            .coerceAtLeast(MIN_ZOOM_LEVEL.toDouble())
            .coerceAtMost(maxZoom)
        val builder = CameraPosition.Builder(position).zoom(clampedZoom)
        if (lastPadding != null) builder.padding(lastPadding)
        map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), durationMs, callback)
    }

    fun moveCameraWithPadding(
        map: MapLibreMap,
        update: CameraUpdate,
        padding: DoubleArray? = null,
        maxZoom: Double = resolveEffectiveMaxZoom(),
    ) {
        val lastPadding = padding ?: defaultPadding
        val position = update.getCameraPosition(map) ?: return
        val clampedZoom = position.zoom
            .coerceAtLeast(MIN_ZOOM_LEVEL.toDouble())
            .coerceAtMost(maxZoom)
        val builder = CameraPosition.Builder(position).zoom(clampedZoom)
        if (lastPadding != null) builder.padding(lastPadding)
        map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
    }

    fun resolveEffectiveMaxZoom(): Double {
        val effectiveId = sourceManager.getEffectiveSourceId()
        return if (sourceManager.isVectorSource(effectiveId)) {
            MAX_ZOOM_LEVEL_VECTOR.toDouble()
        } else {
            MAX_ZOOM_LEVEL.toDouble()
        }
    }

    private fun applyViewportPadding(padding: DoubleArray?) {
        val map = maplibreMap ?: return
        val resolved = if (padding == null || padding.size != 4) {
            doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        } else {
            doubleArrayOf(
                padding[0].coerceAtLeast(0.0),
                padding[1].coerceAtLeast(0.0),
                padding[2].coerceAtLeast(0.0),
                padding[3].coerceAtLeast(0.0),
            )
        }
        // MapLibre setPadding is lazy; use paddingTo for immediate viewport update.
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "applyViewportPadding px L=${resolved[0]} T=${resolved[1]} R=${resolved[2]} B=${resolved[3]}",
            )
        }
        map.moveCamera(
            CameraUpdateFactory.paddingTo(
                resolved[0],
                resolved[1],
                resolved[2],
                resolved[3],
            )
        )
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
        const val MIN_ZOOM_LEVEL = 1
        // Raster tiles top out around 18-19; MapLibre keeps rendering the finest available
        // tiles past that but allows pinch-zooming in further, which lets survey/tracker
        // users read dense point clusters without the map "hitting a wall".
        const val MAX_ZOOM_LEVEL = 25
        const val MAX_ZOOM_LEVEL_VECTOR = 25
        const val BOUNDS_FIT_MAX_ZOOM = 15.0
        const val DEFAULT_POINT_ZOOM = 12.0
        val DEFAULT_WORLD_CENTER = LatLng(0.0, 0.0)
    }
}
