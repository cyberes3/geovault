package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Per-map facade owned by [GeoVaultBaseMap]. Composes:
 *
 *  - [BasemapApplier] for all basemap source / style logic.
 *  - [BasemapZoomPolicy] for zoom limits.
 *  - Local camera + viewport padding helpers used by camera fits / animations.
 *  - A small marker-icon helper used by plugins.
 *
 * Public API is intentionally thin and stable: [setupBaseMapSettings],
 * [fetchMapSources], [applySelectedSource], [isCurrentSourceApplied], camera
 * helpers, [defaultPadding], [addMarkerIcon].
 */
class MapLibreManager(
    private val context: Context,
    @Suppress("unused") private val mapView: MapView,
) {

    val sourceManager = MapSourceManager(context)

    private val zoomPolicy = BasemapZoomPolicy()
    private val applier = BasemapApplier(context, sourceManager, zoomPolicy)

    var sourcesFetched = false
        private set
    private var maplibreMap: MapLibreMap? = null

    var onStyleLoaded: ((MapLibreMap, Style) -> Unit)?
        get() = applier.onStyleLoaded
        set(value) { applier.onStyleLoaded = value }

    /** Invoked when a remote/vector style cannot be applied (network/cache miss, parse errors, etc.). */
    var onStyleLoadFailed: ((String) -> Unit)?
        get() = applier.onStyleLoadFailed
        set(value) { applier.onStyleLoadFailed = value }

    /** Invoked for deterministic server-side map setup problems, not transient network failures. */
    var onMapConfigurationFailed: ((String) -> Unit)? = null

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
        zoomPolicy.applyForRaster(map)
        applyViewportPadding(defaultPadding)
    }

    fun fetchMapSources(onFetched: (Boolean) -> Unit = {}) {
        TileSourceCache.getTileSources(context) { result ->
            val canRenderMap = when (result) {
                is TileSourceFetchResult.Success -> {
                    sourceManager.setSources(result.sources)
                    Log.i(
                        TAG,
                        "Fetched ${result.sources.size} map sources. " +
                            "effectiveSource=${sourceManager.getEffectiveSourceId()}",
                    )
                    true
                }
                is TileSourceFetchResult.ConfigurationError -> {
                    Log.e(TAG, "Map source configuration failure: ${result.message}")
                    sourceManager.setSources(emptyList())
                    onMapConfigurationFailed?.invoke(result.message)
                    false
                }
                is TileSourceFetchResult.TransientFailure -> {
                    Log.e(TAG, "Map source transient failure: ${result.message}")
                    sourceManager.setSources(emptyList())
                    onStyleLoadFailed?.invoke(result.message)
                    false
                }
            }
            sourcesFetched = true
            onFetched(canRenderMap)
        }
    }

    /**
     * Applies the selected basemap.
     *
     * @return `true` when a full style load was started and callers should wait for
     * [onStyleLoaded]; `false` when the selection was applied in-place (or was already active).
     */
    fun applySelectedSource(map: MapLibreMap = requireNotNull(maplibreMap)): Boolean =
        applier.applySelected(map, defaultPadding)

    fun isCurrentSourceApplied(map: MapLibreMap = requireNotNull(maplibreMap)): Boolean =
        applier.isCurrentBasemapApplied(map)

    fun reapplyZoomPreferences(map: MapLibreMap) = applier.reapplyZoomPreferences(map)

    fun resolveEffectiveMaxZoom(): Double = applier.resolveEffectiveMaxZoom()

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
            .coerceAtLeast(BasemapZoomPolicy.MIN_ZOOM)
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
            .coerceAtLeast(BasemapZoomPolicy.MIN_ZOOM)
            .coerceAtMost(maxZoom)
        val builder = CameraPosition.Builder(position).zoom(clampedZoom)
        if (lastPadding != null) builder.padding(lastPadding)
        map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
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
            CameraUpdateFactory.paddingTo(resolved[0], resolved[1], resolved[2], resolved[3]),
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

        const val RASTER_SOURCE_ID = BasemapApplier.RASTER_SOURCE_ID
        const val RASTER_LAYER_ID = BasemapApplier.RASTER_LAYER_ID
        const val ANNOTATIONS_LAYER_ID = BasemapApplier.ANNOTATIONS_LAYER_ID
        const val MIN_ZOOM_LEVEL = BasemapZoomPolicy.MIN_ZOOM_LEVEL
        const val MAX_ZOOM_LEVEL = BasemapZoomPolicy.MAX_ZOOM_LEVEL
        const val MAX_ZOOM_LEVEL_VECTOR = BasemapZoomPolicy.MAX_ZOOM_LEVEL_VECTOR
        const val BOUNDS_FIT_MAX_ZOOM = 15.0
        const val DEFAULT_POINT_ZOOM = 12.0
        val DEFAULT_WORLD_CENTER = LatLng(0.0, 0.0)
    }
}
