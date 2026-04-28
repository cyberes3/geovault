package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import com.geovault.common.GeovaultAuthManager
import okhttp3.HttpUrl
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Single owner of basemap-style application for one [MapLibreMap].
 *
 * Centralizes:
 *  - the apply-plan dispatch (no-op vs in-place raster swap vs full reload)
 *  - the typed [ResolvedBasemap] -> MapLibre call surface, with one
 *    canonical raster apply path used by both the normal and OSM-fallback
 *    flows
 *  - book-keeping of `lastAppliedSourceKey` / `pendingSourceKey` so
 *    [isCurrentBasemapApplied] can answer reliably
 *
 * No UI side effects (no toasts, no view ops outside MapLibre). Failure
 * notifications surface through [onStyleLoadFailed]; hosts decide how to
 * present them.
 */
internal class BasemapApplier(
    private val context: Context,
    val sourceManager: MapSourceManager,
    private val zoomPolicy: BasemapZoomPolicy,
) {

    var onStyleLoaded: ((MapLibreMap, Style) -> Unit)? = null
    var onStyleLoadFailed: ((String) -> Unit)? = null

    private var lastAppliedSourceKey: String? = null
    private var pendingSourceKey: String? = null
    private var styleRequestGeneration: Long = 0L

    /**
     * @return `true` when a full style load was started (callers should
     * await [onStyleLoaded]); `false` when the selection was applied
     * in-place or was already active.
     */
    fun applySelected(map: MapLibreMap, defaultPadding: DoubleArray?): Boolean {
        val savedCamera = map.cameraPosition
        val basemap = sourceManager.resolveBasemap(sourceManager.getEffectiveSourceId())
        val requestedKey = basemap?.cacheKey ?: EMPTY_STYLE_KEY
        val currentStyle = map.style

        when (
            MapSourceApplyPlanner.plan(
                requestedSourceKey = requestedKey,
                pendingSourceKey = pendingSourceKey,
                lastAppliedSourceKey = lastAppliedSourceKey,
                hasCurrentStyle = currentStyle != null,
            )
        ) {
            MapSourceApplyPlan.Noop -> return false
            MapSourceApplyPlan.ReplaceRasterInPlace -> {
                if (basemap is ResolvedBasemap.Raster && currentStyle != null) {
                    pendingSourceKey = requestedKey
                    if (replaceRasterInPlace(currentStyle, basemap.tileTemplate)) {
                        lastAppliedSourceKey = requestedKey
                        pendingSourceKey = null
                        return false
                    }
                }
            }
            MapSourceApplyPlan.LoadFullStyle -> Unit
        }

        pendingSourceKey = requestedKey
        val generation = nextStyleRequestGeneration()
        val restoreCamera = restoreCameraOf(map, savedCamera, defaultPadding)

        return try {
            when (basemap) {
                null -> applyEmptyStyle(map, requestedKey, generation, restoreCamera)
                is ResolvedBasemap.Raster -> applyRaster(map, basemap, requestedKey, generation, restoreCamera)
                is ResolvedBasemap.Vector -> applyVector(map, basemap, requestedKey, generation, restoreCamera)
            }
            true
        } catch (e: Exception) {
            pendingSourceKey = null
            Log.e(
                TAG,
                "Basemap apply failed before style load; applying empty style. effectiveId=${sourceManager.getEffectiveSourceId()}",
                e,
            )
            onStyleLoadFailed?.invoke(e.message ?: e.javaClass.simpleName)
            applyEmptyStyle(map, requestedKey, generation, restoreCamera)
            true
        }
    }

    /** Forces an OSM raster basemap regardless of selection. */
    fun applyOsmFallback(map: MapLibreMap, defaultPadding: DoubleArray?) {
        val savedCamera = map.cameraPosition
        val restoreCamera = restoreCameraOf(map, savedCamera, defaultPadding)
        val osm = sourceManager.resolveStreetFallbackBasemap()
        val generation = nextStyleRequestGeneration()
        if (osm == null) {
            applyEmptyStyle(map, EMPTY_STYLE_KEY, generation, restoreCamera)
            return
        }
        applyRaster(map, osm, osm.cacheKey, generation, restoreCamera)
    }

    fun isCurrentBasemapApplied(map: MapLibreMap): Boolean {
        val key = sourceManager.resolveBasemap(sourceManager.getEffectiveSourceId())?.cacheKey
            ?: EMPTY_STYLE_KEY
        return key == pendingSourceKey || (key == lastAppliedSourceKey && map.style != null)
    }

    fun reapplyZoomPreferences(map: MapLibreMap) {
        val basemap = sourceManager.resolveBasemap(sourceManager.getEffectiveSourceId())
        if (basemap != null) {
            zoomPolicy.applyFor(map, basemap)
        } else {
            zoomPolicy.applyForRaster(map)
        }
    }

    fun resolveEffectiveMaxZoom(): Double {
        val basemap = sourceManager.resolveBasemap(sourceManager.getEffectiveSourceId())
        return if (basemap != null) zoomPolicy.maxZoomFor(basemap) else BasemapZoomPolicy.MAX_ZOOM_RASTER
    }

    private fun applyRaster(
        map: MapLibreMap,
        raster: ResolvedBasemap.Raster,
        sourceKey: String,
        generation: Long,
        restoreCamera: () -> Unit,
    ) {
        zoomPolicy.applyForRaster(map)
        map.setStyle(Style.Builder()) { style ->
            if (generation != styleRequestGeneration) return@setStyle
            zoomPolicy.applyForRaster(map)
            try {
                style.addSource(buildRasterSource(raster.tileTemplate))
                addRasterLayer(style, RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID))
            } catch (rasterError: Exception) {
                Log.e(TAG, "Failed applying raster source: sourceKey=$sourceKey", rasterError)
                onStyleLoadFailed?.invoke(rasterError.message ?: "raster_source_apply_failed")
            }
            lastAppliedSourceKey = sourceKey
            pendingSourceKey = null
            onStyleLoaded?.invoke(map, style)
            restoreCamera()
        }
    }

    private fun applyVector(
        map: MapLibreMap,
        vector: ResolvedBasemap.Vector,
        sourceKey: String,
        generation: Long,
        restoreCamera: () -> Unit,
    ) {
        zoomPolicy.applyForVector(map)
        val styleUrlString = vector.styleUrl.toString()
        val isOurServer = isGeoVaultServerStyle(vector.styleUrl)
        MapStyleCache.getStyleJson(context, styleUrlString, isOurServer) { json ->
            if (generation != styleRequestGeneration) return@getStyleJson
            if (!json.isNullOrBlank()) {
                map.setStyle(Style.Builder().fromJson(json)) { style ->
                    if (generation != styleRequestGeneration) return@setStyle
                    zoomPolicy.applyForVector(map)
                    lastAppliedSourceKey = sourceKey
                    pendingSourceKey = null
                    onStyleLoaded?.invoke(map, style)
                    restoreCamera()
                }
            } else {
                pendingSourceKey = null
                Log.w(
                    TAG,
                    "Vector style JSON unavailable; loading OSM raster fallback. " +
                        "styleUrl=$styleUrlString isOurServer=$isOurServer",
                )
                onStyleLoadFailed?.invoke("Map style unavailable for $styleUrlString")
                applyOsmFallback(map, defaultPadding = null)
            }
        }
    }

    private fun applyEmptyStyle(
        map: MapLibreMap,
        sourceKey: String,
        generation: Long,
        restoreCamera: () -> Unit,
    ) {
        zoomPolicy.applyForRaster(map)
        map.setStyle(Style.Builder()) { style ->
            if (generation != styleRequestGeneration) return@setStyle
            zoomPolicy.applyForRaster(map)
            lastAppliedSourceKey = sourceKey
            pendingSourceKey = null
            onStyleLoaded?.invoke(map, style)
            restoreCamera()
        }
    }

    private fun replaceRasterInPlace(style: Style, tileTemplate: String): Boolean {
        return try {
            if (style.getLayer(RASTER_LAYER_ID) != null) style.removeLayer(RASTER_LAYER_ID)
            if (style.getSource(RASTER_SOURCE_ID) != null) style.removeSource(RASTER_SOURCE_ID)
            style.addSource(buildRasterSource(tileTemplate))
            addRasterLayer(style, RasterLayer(RASTER_LAYER_ID, RASTER_SOURCE_ID))
            true
        } catch (error: Exception) {
            pendingSourceKey = null
            Log.w(TAG, "Failed replacing raster source in-place; falling back to full style load", error)
            false
        }
    }

    private fun buildRasterSource(tileTemplate: String): RasterSource = RasterSource(
        RASTER_SOURCE_ID,
        TileSet("2.1.0", tileTemplate).apply { maxZoom = BasemapZoomPolicy.MAX_ZOOM_RASTER.toFloat() },
        RASTER_TILE_SIZE,
    )

    private fun addRasterLayer(style: Style, rasterLayer: RasterLayer) {
        try {
            style.addLayerBelow(rasterLayer, firstLayerAboveBaseMap(style) ?: ANNOTATIONS_LAYER_ID)
        } catch (layerError: Exception) {
            Log.w(TAG, "Raster layer insertion fallback to top-layer add", layerError)
            style.addLayer(rasterLayer)
        }
    }

    private fun nextStyleRequestGeneration(): Long {
        styleRequestGeneration += 1L
        return styleRequestGeneration
    }

    private fun firstLayerAboveBaseMap(style: Style): String? =
        style.layers.firstOrNull { it.id != RASTER_LAYER_ID }?.id

    private fun isGeoVaultServerStyle(styleUrl: HttpUrl): Boolean {
        val server = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        if (server.isEmpty()) return false
        val s = styleUrl.toString()
        return s == server || s.startsWith("$server/")
    }

    private fun restoreCameraOf(
        map: MapLibreMap,
        savedCamera: CameraPosition,
        defaultPadding: DoubleArray?,
    ): () -> Unit = {
        map.setCameraPosition(savedCamera)
        // Style reload can yield a snapshot without our viewport padding, so re-apply
        // whatever padding was active on the saved camera. Using `defaultPadding`
        // unconditionally would clobber non-zero padding a prior bounds-fit baked
        // into the camera (e.g., the Survey map's initial `snapFitAll` fits with a
        // drawer+FAB inset while the host's `defaultPadding` is `[0, 0, 0, 0]`).
        val paddingToRestore = savedCamera.padding?.takeIf { it.size == 4 } ?: defaultPadding
        applyViewportPadding(map, paddingToRestore)
    }

    private fun applyViewportPadding(map: MapLibreMap, padding: DoubleArray?) {
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
        map.moveCamera(
            CameraUpdateFactory.paddingTo(resolved[0], resolved[1], resolved[2], resolved[3]),
        )
    }

    companion object {
        private const val TAG = "BasemapApplier"
        private const val EMPTY_STYLE_KEY = "empty"
        private const val RASTER_TILE_SIZE = 256

        const val RASTER_SOURCE_ID = "geovault-raster"
        const val RASTER_LAYER_ID = "geovault-raster-layer"
        const val ANNOTATIONS_LAYER_ID = "org.maplibre.annotations.points"
    }
}
