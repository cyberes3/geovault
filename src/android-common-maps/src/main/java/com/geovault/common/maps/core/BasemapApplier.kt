package com.geovault.common.maps.core

import android.content.Context
import android.util.Log
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.logging.GeoVaultCaptureLog
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
 *  - the typed [ResolvedBasemap] -> MapLibre call surface
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
    var onStyleDegraded: ((String) -> Unit)? = null

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

        val plan = MapSourceApplyPlanner.plan(
            requestedSourceKey = requestedKey,
            pendingSourceKey = pendingSourceKey,
            lastAppliedSourceKey = lastAppliedSourceKey,
            hasCurrentStyle = currentStyle != null,
        )
        GeoVaultCaptureLog.i(
            TAG,
            "basemap_apply_plan plan=$plan requestedKey=$requestedKey pendingKey=$pendingSourceKey " +
                "lastAppliedKey=$lastAppliedSourceKey hasStyle=${currentStyle != null}",
        )
        when (plan) {
            MapSourceApplyPlan.Noop -> return false
            MapSourceApplyPlan.ReplaceRasterInPlace -> {
                if (basemap is ResolvedBasemap.Raster && currentStyle != null) {
                    pendingSourceKey = requestedKey
                    if (replaceRasterInPlace(currentStyle, basemap.tileTemplate)) {
                        lastAppliedSourceKey = requestedKey
                        pendingSourceKey = null
                        GeoVaultCaptureLog.i(TAG, "basemap_raster_replaced_in_place sourceKey=$requestedKey")
                        return false
                    }
                }
            }
            MapSourceApplyPlan.LoadFullStyle -> Unit
        }

        pendingSourceKey = requestedKey
        val generation = nextStyleRequestGeneration()
        val restoreCamera = restoreCameraOf(map, savedCamera, defaultPadding)
        GeoVaultCaptureLog.i(
            TAG,
            "basemap_style_load_started type=${basemap.diagnosticType()} sourceKey=$requestedKey generation=$generation",
        )

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
            GeoVaultCaptureLog.e(
                TAG,
                "basemap_apply_failed effectiveId=${sourceManager.getEffectiveSourceId()} sourceKey=$requestedKey",
                e,
            )
            onStyleDegraded?.invoke(e.message ?: e.javaClass.simpleName)
            applyEmptyStyle(map, requestedKey, generation, restoreCamera)
            true
        }
    }

    private fun ResolvedBasemap?.diagnosticType(): String = when (this) {
        null -> "empty"
        is ResolvedBasemap.Raster -> "raster"
        is ResolvedBasemap.Vector -> "vector"
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
                GeoVaultCaptureLog.e(TAG, "basemap_raster_source_apply_failed sourceKey=$sourceKey", rasterError)
                onStyleDegraded?.invoke(rasterError.message ?: "raster_source_apply_failed")
            }
            lastAppliedSourceKey = sourceKey
            pendingSourceKey = null
            GeoVaultCaptureLog.i(TAG, "basemap_style_loaded type=raster sourceKey=$sourceKey generation=$generation")
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
                    GeoVaultCaptureLog.i(
                        TAG,
                        "basemap_style_loaded type=vector sourceKey=$sourceKey generation=$generation " +
                            "isOurServer=$isOurServer",
                    )
                    onStyleLoaded?.invoke(map, style)
                    restoreCamera()
                }
            } else {
                pendingSourceKey = null
                Log.w(
                    TAG,
                    "Vector style JSON unavailable; applying empty style. " +
                        "styleUrl=$styleUrlString isOurServer=$isOurServer",
                )
                GeoVaultCaptureLog.w(
                    TAG,
                    "basemap_vector_style_unavailable styleUrl=$styleUrlString isOurServer=$isOurServer " +
                        "generation=$generation",
                )
                onStyleDegraded?.invoke("Map style unavailable for $styleUrlString")
                applyEmptyStyle(map, sourceKey, generation, restoreCamera)
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
            GeoVaultCaptureLog.i(TAG, "basemap_style_loaded type=empty sourceKey=$sourceKey generation=$generation")
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
            GeoVaultCaptureLog.w(TAG, "basemap_raster_replace_in_place_failed", error)
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
        val server = GeoVaultAuthSession.get().getServerUrl().trimEnd('/')
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
