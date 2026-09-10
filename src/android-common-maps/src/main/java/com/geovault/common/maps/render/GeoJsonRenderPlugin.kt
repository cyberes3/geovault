package com.geovault.common.maps.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import com.geovault.common.maps.core.GeoVaultMapPlugin
import com.geovault.common.maps.core.MapMarkerUtils
import com.geovault.common.maps.core.OutlinedGeoJsonLineLayers
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.common.maps.ui.OverlappingPointsPopup
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.MultiPoint
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Prepared GeoJSON payloads for the three sources owned by [GeoJsonRenderPlugin].
 *
 * The strings are full GeoJSON `FeatureCollection` documents built directly with a
 * [StringBuilder] on the plugin's background executor (see [GeoJsonRenderPlugin.prepareState])
 * so the dominant Java-side allocation cost — one [org.maplibre.geojson.Feature] +
 * geometry + properties wrapper per point — is avoided entirely. The main-thread apply
 * step then hands the string to `GeoJsonSource.setGeoJson(String)`, which forwards
 * directly to `nativeSetGeoJsonString` (no defensive `ArrayList(features)` copy and no
 * `FeatureCollection.toJson()` Gson serialization on the main thread, both of which are
 * required by the `setGeoJson(FeatureCollection)` overload). At ~100k points this turns
 * the initial map paint from a multi-second main-thread freeze into a fast JNI string
 * handoff with the parse happening on MapLibre's native worker.
 */
private data class PreparedGeoJsonRenderState(
    val pointsJson: String,
    val overlayPointsJson: String,
    val linesJson: String,
    val polygonsJson: String,
    val markerImageIds: Set<String>,
)

private const val PROPERTY_ID: String = "id"
private const val PROPERTY_TITLE: String = "title"
private const val PROPERTY_OVERLAP_LIST_LABEL: String = "overlapListLabel"
private const val PROPERTY_DRAW_OUTLINE: String = "drawOutline"
private const val PROPERTY_LINE_WIDTH: String = "lineWidth"
private const val POINT_HIT_HALF_DP: Float = 24f
private const val OVERLAY_HIT_HALF_DP: Float = 36f

/**
 * Renders [MapRenderState] as MapLibre GeoJSON sources and layers.
 *
 * Labeled point features use a built-in **icon then label** symbol stack (see [PointSymbolLayers])
 * whenever text labels are enabled—names paint above markers; callers should not duplicate collision
 * logic in app code.
 */
class GeoJsonRenderPlugin(
    private val sourceIdPrefix: String = "gv-common-render",
    private val config: GeoJsonRenderConfig = GeoJsonRenderConfig(),
    private val context: Context? = null,
) : GeoVaultMapPlugin, GeoVaultRenderCapability {

    private val usePointOverlay: Boolean = config.overlayPointIconImageIds.isNotEmpty()
    private val selectionOverlayConfig: GeoJsonSelectionOverlayConfig? = config.selectionOverlay
    private val useSelectionOverlay: Boolean = selectionOverlayConfig != null

    @Volatile
    private var renderState: MapRenderState = MapRenderState()
    var selectedPointId: String? = null
        private set

    /**
     * Optional replacement for [GeoJsonSelectionOverlay.defaultStyle]. NGS uses this for
     * purple station / nav-target icons; Survey uses it so the nav marker stays on top.
     */
    var styleSelectionOverlayPoint: ((MapRenderPoint) -> MapRenderPoint)? = null
    val selectionMarkerState: GeoJsonSelectionMarkerState? =
        if (useSelectionOverlay) GeoJsonSelectionMarkerState() else null
    private val selectionBitmapCache: MutableMap<String, Bitmap> = mutableMapOf()
    private var map: MapLibreMap? = null
    private var mapView: MapView? = null
    private var tapDetector: GestureDetector? = null
    private var activePopup: OverlappingPointsPopup? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var marshaledApply: Runnable? = null
    private val renderGeneration = AtomicLong(0L)
    private val destroyed = AtomicBoolean(false)
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GeoJsonRenderPlugin-$sourceIdPrefix").apply {
            isDaemon = true
        }
    }

    var onRenderedMapHitSelected: ((GeoVaultRenderedMapHit) -> Boolean)? = null

    var onRenderedMapBackgroundTapped: (() -> Boolean)? = null

    /**
     * Optional wrapper around cluster-expansion camera moves so hosts can pause follow
     * (e.g. [com.geovault.common.maps.ui.camerafollow.GeoVaultMapHeadingFollowFabBundle.runProgrammaticCamera]).
     */
    var wrapProgrammaticCamera: ((block: () -> Unit) -> Unit)? = null

    var renderedMapTapHitKinds: Set<GeoVaultRenderedMapHitKind> =
        setOf(GeoVaultRenderedMapHitKind.Point, GeoVaultRenderedMapHitKind.Overlay)

    /**
     * Selects the point with [id] from the current [MapRenderState] and paints only the
     * selection marker. No-op when [GeoJsonRenderConfig.selectionOverlay] is null.
     * Pass null to clear.
     */
    fun setSelectedPointId(id: String?) {
        if (!useSelectionOverlay) return
        selectedPointId = id
        applySelectionOverlay()
    }

    /** Icon layers the location puck should fade against (main and nav overlay). */
    fun puckOverlapIconLayerIds(): List<String> = buildList {
        add(pointsIconLayerId)
        if (usePointOverlay) add(pointsOverlayIconLayerId)
    }

    override fun setRenderState(newState: MapRenderState) {
        renderState = newState
        val generation = renderGeneration.incrementAndGet()
        if (config.synchronousGeoJsonApplication) {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "GeoJsonRenderPlugin: synchronousGeoJsonApplication requires main thread"
            }
            applyPreparedState(prepareState(newState))
            return
        }
        schedulePreparedApply(newState, generation)
    }

    override fun onMapAttached(map: MapLibreMap) {
        this.map = map
    }

    override fun onMapViewAttached(map: MapLibreMap, mapView: MapView) {
        this.map = map
        this.mapView = mapView
        installTapDetector(mapView)
        if (useSelectionOverlay) applySelectionOverlay()
    }

    override fun onMapDetached() {
        uninstallTapDetector()
        activePopup?.dismiss()
        activePopup = null
        map = null
        mapView = null
    }

    /**
     * Owns map-tap handling for this plugin.
     *
     * MapLibre only dispatches [MapLibreMap.OnMapClickListener] from `onSingleTapConfirmed`, which
     * first waits out the platform double-tap timeout (~250ms), making every tap feel laggy. This
     * detector resolves the tap on `onSingleTapUp` (fired at finger-up) instead, so selection is
     * immediate. It fires strictly before `onSingleTapConfirmed` would, so the plugin does not
     * register a MapLibre click listener at all -- there is a single tap path with no reconciliation.
     *
     * The touch listener is non-consuming, so pan/zoom/double-tap still reach MapLibre.
     */
    @Suppress("ClickableViewAccessibility")
    private fun installTapDetector(mapView: MapView) {
        val detector = GestureDetector(
            mapView.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    handleMapTap(PointF(e.x, e.y))
                    return false
                }
            },
        )
        tapDetector = detector
        mapView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    private fun uninstallTapDetector() {
        mapView?.setOnTouchListener(null)
        tapDetector = null
    }

    private fun handleMapTap(screenPoint: PointF) {
        if (!handlesTaps()) return
        val attachedMap = map ?: return
        processTapAtScreen(attachedMap, screenPoint)
    }

    override fun onPluginDestroyed() {
        marshaledApply?.let { mainHandler.removeCallbacks(it) }
        marshaledApply = null
        destroyed.set(true)
        renderGeneration.incrementAndGet()
        renderExecutor.shutdownNow()
        selectionMarkerState?.clear()
        selectionBitmapCache.clear()
        onRenderedMapHitSelected = null
        onRenderedMapBackgroundTapped = null
        wrapProgrammaticCamera = null
        styleSelectionOverlayPoint = null
    }

    override fun onStyleLoaded(map: MapLibreMap, style: Style) {
        this.map = map
        if (config.disablePointSymbolFade) {
            // Keep symbol appearance deterministic during frequent source updates.
            style.setTransition(TransitionOptions(0L, 0L, false))
        }
        ensureCommonPlacemarkImages(style)
        ensureLayers(style)
        setRenderState(renderState)
    }

    override fun onStyleWillChange(map: MapLibreMap, currentStyle: Style?) {
        activePopup?.dismiss()
        activePopup = null
    }

    /** Whether this plugin has anything to do with a tap (selection, background, or clustering). */
    private fun handlesTaps(): Boolean {
        return onRenderedMapHitSelected != null ||
            onRenderedMapBackgroundTapped != null ||
            useSelectionOverlay ||
            config.pointClustering != null
    }

    private fun processTapAtScreen(attachedMap: MapLibreMap, screenPoint: PointF): Boolean {
        val hitSelected = onRenderedMapHitSelected
        val anchor = mapView ?: return false
        val density = anchor.resources.displayMetrics.density

        if (GeoVaultRenderedMapHitKind.Point in renderedMapTapHitKinds) {
            val pointResolution = GeoVaultRenderedMapHitResolver.resolve(
                queryRenderedHits(
                    map = attachedMap,
                    bounds = rectAround(screenPoint, POINT_HIT_HALF_DP * density),
                    layerIds = pointHitLayerIds(),
                    kind = GeoVaultRenderedMapHitKind.Point,
                ),
            )
            if (pointResolution !is GeoVaultRenderedMapHitResolution.None) {
                return dispatchRenderedHitResolution(
                    resolution = pointResolution,
                    screenPoint = screenPoint,
                    hitSelected = hitSelected,
                    applyPointSelection = true,
                )
            }
        }

        if (GeoVaultRenderedMapHitKind.Overlay in renderedMapTapHitKinds) {
            val overlayResolution = GeoVaultRenderedMapHitResolver.resolve(
                queryRenderedHits(
                    map = attachedMap,
                    bounds = rectAround(screenPoint, OVERLAY_HIT_HALF_DP * density),
                    layerIds = overlayHitLayerIds(),
                    kind = GeoVaultRenderedMapHitKind.Overlay,
                ),
            )
            if (overlayResolution !is GeoVaultRenderedMapHitResolution.None) {
                if (useSelectionOverlay) setSelectedPointId(null)
                return dispatchRenderedHitResolution(
                    resolution = overlayResolution,
                    screenPoint = screenPoint,
                    hitSelected = hitSelected,
                    applyPointSelection = false,
                )
            }
        }

        if (tryExpandCluster(attachedMap, screenPoint, density)) {
            return true
        }

        activePopup?.dismiss()
        activePopup = null
        if (useSelectionOverlay) setSelectedPointId(null)
        return onRenderedMapBackgroundTapped?.invoke() ?: false
    }

    private fun tryExpandCluster(
        mapLibreMap: MapLibreMap,
        screenPoint: PointF,
        density: Float,
    ): Boolean {
        val clustering = config.pointClustering ?: return false
        val clusterLayerIds = pointClusterLayerIds(sourceIdPrefix, clustering)
        val halfPx = (CLUSTER_TOLERANCE_DP * density) / 2f
        val clusterFeatures = runCatching {
            mapLibreMap.queryRenderedFeatures(
                rectAround(screenPoint, halfPx),
                *clusterLayerIds.toTypedArray(),
            )
        }.getOrElse { emptyList() }
        val cluster = clusterFeatures.firstOrNull() ?: return false
        val source = mapLibreMap.style
            ?.getSourceAs<GeoJsonSource>(pointsSourceId)
            ?: return true
        val zoomDelta = runCatching {
            source.getClusterExpansionZoom(cluster).toDouble() -
                mapLibreMap.cameraPosition.zoom +
                CLUSTER_EXPANSION_ZOOM_PADDING
        }.getOrNull() ?: return true
        val clickPoint = android.graphics.Point(screenPoint.x.toInt(), screenPoint.y.toInt())
        val expand = {
            mapLibreMap.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.zoomBy(
                    maxOf(zoomDelta, MIN_CLUSTER_ZOOM_DELTA),
                    clickPoint,
                ),
            )
        }
        val wrap = wrapProgrammaticCamera
        if (wrap != null) {
            wrap(expand)
        } else {
            expand()
        }
        return true
    }

    private fun dispatchRenderedHitResolution(
        resolution: GeoVaultRenderedMapHitResolution,
        screenPoint: PointF,
        hitSelected: ((GeoVaultRenderedMapHit) -> Boolean)?,
        applyPointSelection: Boolean,
    ): Boolean {
        return when (resolution) {
            GeoVaultRenderedMapHitResolution.None -> false
            is GeoVaultRenderedMapHitResolution.Single -> {
                activePopup?.dismiss()
                activePopup = null
                if (applyPointSelection && useSelectionOverlay) {
                    setSelectedPointId(resolution.hit.id)
                }
                hitSelected?.invoke(resolution.hit) ?: applyPointSelection
            }
            is GeoVaultRenderedMapHitResolution.Multiple -> {
                val anchor = mapView ?: return false
                activePopup?.dismiss()
                activePopup = OverlappingPointsPopup(
                    context = anchor.context,
                    anchor = anchor,
                    pointNames = resolution.hits.map { it.overlapListLabel },
                    tapX = screenPoint.x.toInt(),
                    tapY = screenPoint.y.toInt(),
                    onSelect = { index ->
                        val hit = resolution.hits.getOrNull(index) ?: return@OverlappingPointsPopup
                        if (applyPointSelection && useSelectionOverlay) {
                            setSelectedPointId(hit.id)
                        }
                        hitSelected?.invoke(hit)
                    },
                ).also { it.show() }
                true
            }
        }
    }

    private fun queryRenderedHits(
        map: MapLibreMap,
        bounds: RectF,
        layerIds: List<String>,
        kind: GeoVaultRenderedMapHitKind,
    ): List<GeoVaultRenderedMapHitCandidate> {
        val style = map.style ?: return emptyList()
        val existingLayerIds = layerIds.filter { style.getLayer(it) != null }
        if (existingLayerIds.isEmpty()) return emptyList()
        val raw = runCatching {
            map.queryRenderedFeatures(bounds, *existingLayerIds.toTypedArray())
        }.getOrElse { emptyList() }
        return raw.mapNotNull { feature -> feature.toRenderedMapHitCandidate(kind) }
    }

    private fun Feature.toRenderedMapHitCandidate(
        kind: GeoVaultRenderedMapHitKind,
    ): GeoVaultRenderedMapHitCandidate? {
        val id = getStringProperty(PROPERTY_ID) ?: return null
        val mapTitle = getStringProperty(PROPERTY_TITLE)?.takeIf { it.isNotBlank() } ?: ""
        val overlapListLabel = getStringProperty(PROPERTY_OVERLAP_LIST_LABEL)?.takeIf { it.isNotBlank() }
            ?: mapTitle
        val coordinate = representativeCoordinate(geometry())
        return GeoVaultRenderedMapHitCandidate(
            hit = GeoVaultRenderedMapHit(
                id = id,
                title = mapTitle,
                overlapListLabel = overlapListLabel,
                kind = kind,
                latitude = coordinate?.first,
                longitude = coordinate?.second,
            ),
            dedupeKey = id,
        )
    }

    private fun representativeCoordinate(geometry: Geometry?): Pair<Double, Double>? = when (geometry) {
        is Point -> geometry.latitude() to geometry.longitude()
        is MultiPoint -> geometry.coordinates().firstOrNull()?.let { it.latitude() to it.longitude() }
        is LineString -> geometry.coordinates().firstOrNull()?.let { it.latitude() to it.longitude() }
        is MultiLineString -> geometry.coordinates().firstOrNull()?.firstOrNull()?.let { it.latitude() to it.longitude() }
        is Polygon -> geometry.coordinates().firstOrNull()?.firstOrNull()?.let { it.latitude() to it.longitude() }
        is MultiPolygon -> geometry.coordinates().firstOrNull()?.firstOrNull()?.firstOrNull()?.let { it.latitude() to it.longitude() }
        else -> null
    }

    private fun pointHitLayerIds(): List<String> = listOf(
        pointsOverlayIconLayerId,
        pointsIconLayerId,
        pointsOverlayLabelLayerId,
        pointsLabelLayerId,
        pointsOverlayCircleLayerId,
        pointsCircleLayerId,
    )

    private fun overlayHitLayerIds(): List<String> = listOf(
        lineOuterLayerId,
        lineBorderLayerId,
        lineFillLayerId,
        lineThinLayerId,
        polygonsFillLayerId,
        polygonsOutlineLayerId,
        polygonsOutlineOuterLayerId,
        polygonsOutlineBorderLayerId,
        polygonsOutlineFillLayerId,
    )

    private fun rectAround(point: PointF, halfPx: Float): RectF =
        RectF(point.x - halfPx, point.y - halfPx, point.x + halfPx, point.y + halfPx)

    /**
     * Sets point-name label halo for the current style. Use [haloWidthPx] `0f` to disable the halo.
     * No-op if [GeoJsonRenderConfig.showPointTextLabels] is false or the label layer is missing.
     */
    fun applyPointLabelHalo(style: Style, haloWidthPx: Float, haloColorArgb: Int) {
        if (!config.showPointTextLabels) return
        val layer = style.getLayer(pointsLabelLayerId) as? SymbolLayer ?: return
        layer.setProperties(
            PropertyFactory.textHaloWidth(haloWidthPx),
            PropertyFactory.textHaloColor(haloColorArgb),
        )
        if (usePointOverlay) {
            (style.getLayer(pointsOverlayLabelLayerId) as? SymbolLayer)?.setProperties(
                PropertyFactory.textHaloWidth(haloWidthPx),
                PropertyFactory.textHaloColor(haloColorArgb),
            )
        }
    }

    private fun schedulePreparedApply(state: MapRenderState, generation: Long) {
        renderExecutor.execute {
            if (destroyed.get() || generation != renderGeneration.get()) return@execute
            val prepared = prepareState(state)
            if (destroyed.get() || generation != renderGeneration.get()) return@execute
            val runnable = Runnable {
                marshaledApply = null
                if (!destroyed.get() && generation == renderGeneration.get()) {
                    applyPreparedState(prepared)
                }
            }
            mainHandler.post {
                marshaledApply?.let { mainHandler.removeCallbacks(it) }
                marshaledApply = runnable
                mainHandler.post(runnable)
            }
        }
    }

    private fun ensureLayers(style: Style) {
        ensureSource(style, pointsSourceId, buildGeoJsonOptions(config.pointClustering))
        if (usePointOverlay) {
            ensureSource(style, pointsOverlaySourceId, null)
        }
        ensureSource(style, linesSourceId)
        ensureSource(style, polygonsSourceId)

        val pendingPointPresentationLayers = mutableListOf<Layer>()
        fun addPointPresentationLayer(layer: Layer) {
            if (config.renderPointSymbolsAboveLines) {
                pendingPointPresentationLayers += layer
            } else {
                addLayerWithPlacement(style, layer)
            }
        }
        fun addPointPresentationLayers(layers: List<Layer>) {
            if (config.renderPointSymbolsAboveLines) {
                pendingPointPresentationLayers += layers
            } else {
                addLayerStackWithPlacement(style, layers)
            }
        }

        addPointPresentationLayers(createPointClusterLayers(style))
        if (config.showPointCircles && style.getLayer(pointsCircleLayerId) == null) {
            addPointPresentationLayer(
                CircleLayer(pointsCircleLayerId, pointsSourceId).withProperties(
                    PropertyFactory.circleColor(
                        Expression.coalesce(
                            Expression.get("pointFillColorHex"),
                            Expression.literal(config.defaultPointFillColorHex),
                        ),
                    ),
                    PropertyFactory.circleRadius(
                        Expression.coalesce(
                            Expression.get("pointRadius"),
                            Expression.literal(config.defaultPointRadius),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor(
                        Expression.coalesce(
                            Expression.get("pointStrokeColorHex"),
                            Expression.literal(config.defaultPointStrokeColorHex),
                        ),
                    ),
                    PropertyFactory.circleStrokeWidth(
                        Expression.coalesce(
                            Expression.get("pointStrokeWidth"),
                            Expression.literal(config.defaultPointStrokeWidth),
                        ),
                    ),
                ).withUnclusteredPointFilter(),
            )
        }
        if (config.showPointLabelsAndIcons && style.getLayer(pointsIconLayerId) == null) {
            addPointPresentationLayers(
                PointSymbolLayerFactory.create(
                    sourceId = pointsSourceId,
                    iconLayerId = pointsIconLayerId,
                    labelLayerId = pointsLabelLayerId,
                    config = config,
                    textAllowOverlap = false,
                    filterUnclustered = config.pointClustering != null,
                ).inPaintOrder(),
            )
        }
        if (usePointOverlay) {
            if (config.showPointCircles && style.getLayer(pointsOverlayCircleLayerId) == null) {
                addPointPresentationLayer(
                    CircleLayer(pointsOverlayCircleLayerId, pointsOverlaySourceId).withProperties(
                        PropertyFactory.circleColor(
                            Expression.coalesce(
                                Expression.get("pointFillColorHex"),
                                Expression.literal(config.defaultPointFillColorHex),
                            ),
                        ),
                        PropertyFactory.circleRadius(
                            Expression.coalesce(
                                Expression.get("pointRadius"),
                                Expression.literal(config.defaultPointRadius),
                            ),
                        ),
                        PropertyFactory.circleStrokeColor(
                            Expression.coalesce(
                                Expression.get("pointStrokeColorHex"),
                                Expression.literal(config.defaultPointStrokeColorHex),
                            ),
                        ),
                        PropertyFactory.circleStrokeWidth(
                            Expression.coalesce(
                                Expression.get("pointStrokeWidth"),
                                Expression.literal(config.defaultPointStrokeWidth),
                            ),
                        ),
                    ),
                )
            }
            if (config.showPointLabelsAndIcons && style.getLayer(pointsOverlayIconLayerId) == null) {
                addPointPresentationLayers(
                    PointSymbolLayerFactory.create(
                        sourceId = pointsOverlaySourceId,
                        iconLayerId = pointsOverlayIconLayerId,
                        labelLayerId = pointsOverlayLabelLayerId,
                        config = config,
                        textAllowOverlap = config.overlayPointLabelsAllowOverlap,
                        filterUnclustered = false,
                    ).inPaintOrder(),
                )
            }
        }
        if (style.getLayer(lineOuterLayerId) == null) {
            addLayerWithPlacement(
                style,
                OutlinedGeoJsonLineLayers.createOuterLayer(
                    layerId = lineOuterLayerId,
                    sourceId = linesSourceId,
                ).withFilter(outlinedLineFilter()),
            )
        }
        if (style.getLayer(lineBorderLayerId) == null) {
            addLayerWithPlacement(
                style,
                OutlinedGeoJsonLineLayers.createBorderLayer(
                    layerId = lineBorderLayerId,
                    sourceId = linesSourceId,
                ).withFilter(outlinedLineFilter()),
            )
        }
        if (style.getLayer(lineFillLayerId) == null) {
            addLayerWithPlacement(
                style,
                OutlinedGeoJsonLineLayers.createFillLayer(lineFillLayerId, linesSourceId)
                    .withFilter(outlinedLineFilter()),
            )
        }
        if (style.getLayer(lineThinLayerId) == null) {
            addLayerWithPlacement(
                style,
                LineLayer(lineThinLayerId, linesSourceId).withProperties(
                    PropertyFactory.lineColor(Expression.get(OutlinedGeoJsonLineLayers.PROPERTY_LINE_COLOR)),
                    PropertyFactory.lineWidth(
                        Expression.coalesce(
                            Expression.get(PROPERTY_LINE_WIDTH),
                            Expression.literal(1.5f),
                        ),
                    ),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(thinLineFilter()),
            )
        }
        if (config.showPolygonFill && style.getLayer(polygonsFillLayerId) == null) {
            addLayerWithPlacement(
                style,
                FillLayer(polygonsFillLayerId, polygonsSourceId).withProperties(
                    PropertyFactory.fillColor(Expression.get("fillColor")),
                    PropertyFactory.fillOpacity(config.defaultPolygonFillOpacity),
                ),
            )
        }
        if (config.showPolygonOutline) {
            when (config.polygonOutlineStyle) {
                PolygonOutlineStyle.SIMPLE -> {
                    if (style.getLayer(polygonsOutlineLayerId) == null) {
                        addLayerWithPlacement(
                            style,
                            LineLayer(polygonsOutlineLayerId, polygonsSourceId).withProperties(
                                PropertyFactory.lineColor(Expression.get("outlineColor")),
                                PropertyFactory.lineWidth(config.defaultPolygonOutlineWidth),
                            ),
                        )
                    }
                }
                PolygonOutlineStyle.OUTLINED -> {
                    if (style.getLayer(polygonsOutlineOuterLayerId) == null) {
                        addLayerWithPlacement(
                            style,
                            OutlinedGeoJsonLineLayers.createOuterLayer(
                                layerId = polygonsOutlineOuterLayerId,
                                sourceId = polygonsSourceId,
                            ),
                        )
                    }
                    if (style.getLayer(polygonsOutlineBorderLayerId) == null) {
                        addLayerWithPlacement(
                            style,
                            OutlinedGeoJsonLineLayers.createBorderLayer(
                                layerId = polygonsOutlineBorderLayerId,
                                sourceId = polygonsSourceId,
                            ),
                        )
                    }
                    if (style.getLayer(polygonsOutlineFillLayerId) == null) {
                        addLayerWithPlacement(
                            style,
                            OutlinedGeoJsonLineLayers.createFillLayer(
                                layerId = polygonsOutlineFillLayerId,
                                sourceId = polygonsSourceId,
                            ),
                        )
                    }
                }
            }
        }
        if (pendingPointPresentationLayers.isNotEmpty()) {
            addLayerStackWithPlacement(style, pendingPointPresentationLayers)
        }
    }

    private fun createPointClusterLayers(style: Style): List<Layer> {
        val clustering = config.pointClustering ?: return emptyList()
        val circleLayers = clustering.orderedCircleStyles.mapIndexedNotNull { index, circleStyle ->
            val layerId = pointClusterCircleLayerId(sourceIdPrefix, index)
            if (style.getLayer(layerId) != null) {
                null
            } else {
                CircleLayer(layerId, pointsSourceId)
                    .withProperties(
                        PropertyFactory.circleColor(Expression.literal(circleStyle.circleColorHex)),
                        PropertyFactory.circleRadius(circleStyle.circleRadius),
                        PropertyFactory.circleStrokeColor(config.defaultPointStrokeColorHex),
                        PropertyFactory.circleStrokeWidth(config.defaultPointStrokeWidth),
                    )
                    .withFilter(clusterCircleFilter(index, clustering.orderedCircleStyles))
            }
        }
        val countLayerId = pointClusterCountLayerId(sourceIdPrefix)
        val countLayer = if (style.getLayer(countLayerId) == null) {
            SymbolLayer(countLayerId, pointsSourceId)
                .withProperties(
                    PropertyFactory.textField(Expression.get(PROPERTY_POINT_COUNT)),
                    PropertyFactory.textSize(clustering.countTextSize),
                    PropertyFactory.textColor(Expression.literal(clustering.countTextColorHex)),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                )
                .withFilter(Expression.has(PROPERTY_POINT_COUNT))
        } else {
            null
        }
        return circleLayers + listOfNotNull(countLayer)
    }

    private fun clusterCircleFilter(
        index: Int,
        styles: List<GeoJsonPointClusterCircleStyle>,
    ): Expression {
        val pointCount = Expression.toNumber(Expression.get(PROPERTY_POINT_COUNT))
        val minCountFilter = Expression.gte(pointCount, Expression.literal(styles[index].minPointCount))
        return if (index == 0) {
            Expression.all(
                Expression.has(PROPERTY_POINT_COUNT),
                minCountFilter,
            )
        } else {
            Expression.all(
                Expression.has(PROPERTY_POINT_COUNT),
                minCountFilter,
                Expression.lt(pointCount, Expression.literal(styles[index - 1].minPointCount)),
            )
        }
    }

    private fun prepareState(state: MapRenderState): PreparedGeoJsonRenderState {
        val overlayIconIds = config.overlayPointIconImageIds
        val (mainPoints, overlayPoints) = if (usePointOverlay) {
            val over = state.points.filter { p -> p.iconImageId in overlayIconIds }
            val main = state.points.filter { p -> p.iconImageId !in overlayIconIds }
            main to over
        } else {
            state.points to emptyList()
        }
        return PreparedGeoJsonRenderState(
            pointsJson = buildPointsFeatureCollectionJson(mainPoints),
            overlayPointsJson = if (usePointOverlay) {
                buildPointsFeatureCollectionJson(overlayPoints)
            } else {
                GeoJsonFeatureCollectionEncoder.EMPTY_FEATURE_COLLECTION_JSON
            },
            linesJson = buildLinesFeatureCollectionJson(state.lines),
            polygonsJson = buildPolygonsFeatureCollectionJson(
                polygons = state.polygons,
                emitLineColorProperty = config.polygonOutlineStyle == PolygonOutlineStyle.OUTLINED,
            ),
            markerImageIds = state.points.mapNotNull { it.iconImageId }.toSet(),
        )
    }

    private fun applyPreparedState(prepared: PreparedGeoJsonRenderState) {
        val style = map?.style ?: return
        ensureRenderStateMarkerImages(style, prepared.markerImageIds)
        updateSource(style, pointsSourceId, prepared.pointsJson)
        if (usePointOverlay) {
            updateSource(style, pointsOverlaySourceId, prepared.overlayPointsJson)
        }
        updateSource(style, linesSourceId, prepared.linesJson)
        updateSource(style, polygonsSourceId, prepared.polygonsJson)
        applySelectionOverlay()
    }

    private fun applySelectionOverlay() {
        val overlayConfig = selectionOverlayConfig ?: return
        val markerState = selectionMarkerState ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applySelectionOverlay() }
            return
        }
        val styled = GeoJsonSelectionOverlay.styledPoint(
            selectedId = selectedPointId,
            sourcePoints = renderState.points,
            config = overlayConfig,
            style = styleSelectionOverlayPoint,
        )
        selectedPointId = styled?.id
        if (styled == null || !isValidMapLibreGeographicLatLng(styled.latitude, styled.longitude)) {
            markerState.clear()
            return
        }
        val bitmap = bitmapForSelection(styled) ?: run {
            markerState.clear()
            return
        }
        markerState.show(
            GeoJsonSelectionMarkerVisual(
                pointId = styled.id,
                latitude = styled.latitude,
                longitude = styled.longitude,
                bitmap = bitmap,
                iconSize = styled.iconSize ?: overlayConfig.selectedIconSize,
                iconRotationDegrees = styled.iconRotationDegrees ?: 0f,
                iconAnchor = config.defaultIconAnchor,
            ),
        )
    }

    private fun bitmapForSelection(styled: MapRenderPoint): Bitmap? {
        val imageId = styled.iconImageId ?: return null
        selectionBitmapCache[imageId]?.let { return it }
        val appContext = context?.applicationContext ?: return null
        val markerStyle = CommonMapPointIcons.styleOrNull(imageId)
            ?: buildResolvedMarkerStyles(appContext)[imageId]
        val bitmap = if (markerStyle != null) {
            MapMarkerUtils.buildMarkerBitmap(appContext, markerStyle)
        } else {
            val symbolStyle = buildResolvedSymbolIconStyles()[imageId] ?: return null
            MapMarkerUtils.buildSymbolIconBitmap(appContext, symbolStyle)
        }
        selectionBitmapCache[imageId] = bitmap
        return bitmap
    }

    private fun updateSource(style: Style, id: String, json: String) {
        val source = style.getSourceAs<GeoJsonSource>(id) ?: return
        source.setGeoJson(json)
    }

    private fun ensureSource(
        style: Style,
        id: String,
        options: GeoJsonOptions? = buildGeoJsonOptions(pointClustering = null),
    ) {
        if (style.getSource(id) == null) {
            val emptyJson = GeoJsonFeatureCollectionEncoder.EMPTY_FEATURE_COLLECTION_JSON
            if (options == null) {
                style.addSource(GeoJsonSource(id, emptyJson))
            } else {
                style.addSource(GeoJsonSource(id, emptyJson, options))
            }
        }
    }

    private fun buildGeoJsonOptions(pointClustering: GeoJsonPointClusteringConfig?): GeoJsonOptions? {
        if (!config.useSynchronousSourceUpdates && pointClustering == null) return null
        return GeoJsonOptions().apply {
            if (config.useSynchronousSourceUpdates) {
                withSynchronousUpdate(true)
            }
            if (pointClustering != null) {
                withCluster(true)
                withClusterMaxZoom(pointClustering.maxZoom)
                withClusterRadius(pointClustering.radius)
                withClusterMinPoints(pointClustering.minPoints)
            }
        }
    }

    private fun ensureRenderStateMarkerImages(style: Style, imageIds: Set<String>) {
        val appContext = context?.applicationContext ?: return
        for (imageId in imageIds) {
            if (style.getImage(imageId) != null) continue
            val markerStyle = CommonMapPointIcons.styleOrNull(imageId) ?: continue
            val bitmap = MapMarkerUtils.buildMarkerBitmap(appContext, markerStyle)
            style.addImage(imageId, bitmap, false)
        }
    }

    private fun ensureCommonPlacemarkImages(style: Style) {
        val appContext = context?.applicationContext ?: return
        val resolvedStyles = buildResolvedMarkerStyles(appContext)
        resolvedStyles.forEach { (imageId, markerStyle) ->
            if (style.getImage(imageId) == null) {
                val bitmap = MapMarkerUtils.buildMarkerBitmap(appContext, markerStyle)
                style.addImage(imageId, bitmap, false)
            }
        }
        val resolvedSymbolIcons = buildResolvedSymbolIconStyles()
        resolvedSymbolIcons.forEach { (imageId, iconStyle) ->
            if (style.getImage(imageId) == null) {
                val bitmap = MapMarkerUtils.buildSymbolIconBitmap(appContext, iconStyle)
                style.addImage(imageId, bitmap, false)
            }
        }
    }

    private fun buildResolvedMarkerStyles(context: Context): Map<String, MapMarkerStyle> {
        return mapOf(
            CommonMapIconIds.MARKER_DEFAULT to CommonMapMarkerStyles.default(),
            CommonMapIconIds.MARKER_SELECTED to CommonMapMarkerStyles.selected(),
            CommonMapIconIds.MARKER_NAV_TARGET to CommonMapMarkerStyles.navTarget(),
        ) + config.markerStyles
    }

    private fun buildResolvedSymbolIconStyles(): Map<String, MapSymbolIconStyle> {
        return mapOf(
            CommonMapIconIds.STATION_POINT to CommonMapSymbolIconStyles.station(),
            CommonMapIconIds.STATION_POINT_SELECTED to CommonMapSymbolIconStyles.selectedStation(),
            CommonMapIconIds.STATION_POINT_NAV_TARGET to CommonMapSymbolIconStyles.stationNavTarget(),
        ) + config.symbolIconStyles
    }

    private fun addLayerWithPlacement(style: Style, layer: org.maplibre.android.style.layers.Layer) {
        val belowLayerId = config.belowLayerId
        if (!belowLayerId.isNullOrBlank() && style.getLayer(belowLayerId) != null) {
            style.addLayerBelow(layer, belowLayerId)
        } else {
            style.addLayer(layer)
        }
    }

    private fun addLayerStackWithPlacement(style: Style, layers: List<Layer>) {
        var previousLayerId: String? = null
        layers.forEach { layer ->
            if (style.getLayer(layer.id) != null) {
                previousLayerId = layer.id
                return@forEach
            }
            val anchorLayerId = previousLayerId
            if (anchorLayerId != null && style.getLayer(anchorLayerId) != null) {
                style.addLayerAbove(layer, anchorLayerId)
            } else {
                addLayerWithPlacement(style, layer)
            }
            previousLayerId = layer.id
        }
    }

    private fun CircleLayer.withUnclusteredPointFilter(): CircleLayer =
        if (config.pointClustering == null) {
            this
        } else {
            withFilter(Expression.neq(Expression.get(PointSymbolLayerFactory.CLUSTER_PROPERTY), true))
        }

    private fun SymbolLayer.withUnclusteredPointFilter(): SymbolLayer =
        if (config.pointClustering == null) {
            this
        } else {
            withFilter(Expression.neq(Expression.get(PointSymbolLayerFactory.CLUSTER_PROPERTY), true))
        }

    private fun outlinedLineFilter(): Expression = Expression.eq(
        Expression.coalesce(
            Expression.toNumber(Expression.get(PROPERTY_DRAW_OUTLINE)),
            Expression.literal(1),
        ),
        Expression.literal(1),
    )

    private fun thinLineFilter(): Expression = Expression.eq(
        Expression.coalesce(
            Expression.toNumber(Expression.get(PROPERTY_DRAW_OUTLINE)),
            Expression.literal(1),
        ),
        Expression.literal(0),
    )

    private val pointsSourceId = pointsSourceId(sourceIdPrefix)
    private val linesSourceId = "$sourceIdPrefix-lines-source"
    private val polygonsSourceId = "$sourceIdPrefix-polygons-source"
    private val pointsCircleLayerId = "$sourceIdPrefix-points-circle-layer"
    /** Points promoted above the main point layers (e.g. navigation targets), non-clustered source. */
    private val pointsOverlaySourceId = pointsOverlaySourceId(sourceIdPrefix)
    private val pointsOverlayCircleLayerId = "$sourceIdPrefix-points-overlay-circle-layer"
    /** Visible markers; painted below [pointsLabelLayerId] and above linework when [GeoJsonRenderConfig.renderPointSymbolsAboveLines]. */
    private val pointsIconLayerId = pointsIconLayerId(sourceIdPrefix)
    /** Text above [pointsIconLayerId]; collision hides overlapping labels, not icons. */
    private val pointsLabelLayerId = pointsLabelLayerId(sourceIdPrefix)
    private val pointsOverlayIconLayerId = pointsOverlayIconLayerId(sourceIdPrefix)
    private val pointsOverlayLabelLayerId = pointsOverlayLabelLayerId(sourceIdPrefix)
    private val lineOuterLayerId = "$sourceIdPrefix-lines-outer-layer"
    private val lineBorderLayerId = "$sourceIdPrefix-lines-border-layer"
    private val lineFillLayerId = "$sourceIdPrefix-lines-fill-layer"
    private val lineThinLayerId = "$sourceIdPrefix-lines-thin-layer"
    private val polygonsFillLayerId = "$sourceIdPrefix-polygons-fill-layer"
    private val polygonsOutlineLayerId = "$sourceIdPrefix-polygons-outline-layer"
    private val polygonsOutlineOuterLayerId = polygonsOutlineOuterLayerId(sourceIdPrefix)
    private val polygonsOutlineBorderLayerId = polygonsOutlineBorderLayerId(sourceIdPrefix)
    private val polygonsOutlineFillLayerId = polygonsOutlineFillLayerId(sourceIdPrefix)

    companion object {
        private const val PROPERTY_POINT_COUNT: String = "point_count"
        private const val CLUSTER_TOLERANCE_DP: Float = 44f
        private const val CLUSTER_EXPANSION_ZOOM_PADDING: Double = 0.25
        private const val MIN_CLUSTER_ZOOM_DELTA: Double = 0.5

        fun pointsSourceId(sourceIdPrefix: String): String = "$sourceIdPrefix-points-source"

        /** Non-clustered point source for [GeoJsonRenderConfig.overlayPointIconImageIds]. */
        fun pointsOverlaySourceId(sourceIdPrefix: String): String = "$sourceIdPrefix-points-overlay-source"

        fun pointsIconLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-points-icon-layer"

        fun pointsLabelLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-points-label-layer"

        fun pointsOverlayIconLayerId(sourceIdPrefix: String): String =
            "$sourceIdPrefix-points-overlay-icon-layer"

        fun pointsOverlayLabelLayerId(sourceIdPrefix: String): String =
            "$sourceIdPrefix-points-overlay-label-layer"

        fun pointsCircleLayerId(sourceIdPrefix: String): String =
            "$sourceIdPrefix-points-circle-layer"

        fun pointsOverlayCircleLayerId(sourceIdPrefix: String): String =
            "$sourceIdPrefix-points-overlay-circle-layer"

        fun linesSourceId(sourceIdPrefix: String): String = "$sourceIdPrefix-lines-source"

        fun polygonsSourceId(sourceIdPrefix: String): String = "$sourceIdPrefix-polygons-source"

        fun lineOuterLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-lines-outer-layer"

        fun lineBorderLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-lines-border-layer"

        fun lineFillLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-lines-fill-layer"

        fun lineThinLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-lines-thin-layer"

        fun polygonsFillLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-polygons-fill-layer"

        fun polygonsOutlineLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-polygons-outline-layer"

        fun polygonsOutlineOuterLayerId(sourceIdPrefix: String): String =
            "$sourceIdPrefix-polygons-outline-outer-layer"

        fun polygonsOutlineBorderLayerId(sourceIdPrefix: String): String =
            "$sourceIdPrefix-polygons-outline-border-layer"

        fun polygonsOutlineFillLayerId(sourceIdPrefix: String): String =
            "$sourceIdPrefix-polygons-outline-fill-layer"

        fun pointHitLayerIds(sourceIdPrefix: String): List<String> = listOf(
            pointsOverlayIconLayerId(sourceIdPrefix),
            pointsIconLayerId(sourceIdPrefix),
            pointsOverlayLabelLayerId(sourceIdPrefix),
            pointsLabelLayerId(sourceIdPrefix),
            pointsOverlayCircleLayerId(sourceIdPrefix),
            pointsCircleLayerId(sourceIdPrefix),
        )

        fun overlayHitLayerIds(sourceIdPrefix: String): List<String> = listOf(
            lineOuterLayerId(sourceIdPrefix),
            lineBorderLayerId(sourceIdPrefix),
            lineFillLayerId(sourceIdPrefix),
            lineThinLayerId(sourceIdPrefix),
            polygonsFillLayerId(sourceIdPrefix),
            polygonsOutlineLayerId(sourceIdPrefix),
            polygonsOutlineOuterLayerId(sourceIdPrefix),
            polygonsOutlineBorderLayerId(sourceIdPrefix),
            polygonsOutlineFillLayerId(sourceIdPrefix),
        )

        fun pointClusterCircleLayerId(sourceIdPrefix: String, index: Int): String =
            "$sourceIdPrefix-points-cluster-$index-layer"

        fun pointClusterCountLayerId(sourceIdPrefix: String): String =
            "$sourceIdPrefix-points-cluster-count-layer"

        fun pointClusterLayerIds(
            sourceIdPrefix: String,
            clustering: GeoJsonPointClusteringConfig,
        ): List<String> =
            clustering.orderedCircleStyles.indices.map { index ->
                pointClusterCircleLayerId(sourceIdPrefix, index)
            } + pointClusterCountLayerId(sourceIdPrefix)
    }
}

/**
 * Builds GeoJSON `FeatureCollection` documents directly with a [StringBuilder] for the
 * plugin's background `prepareState` step.
 *
 * Skipping the [org.maplibre.geojson.FeatureCollection] object graph — and therefore the
 * Gson serialization MapLibre's `setGeoJson(FeatureCollection)` would otherwise trigger
 * on the main thread — is critical at NGS-scale point counts (~100k features), where
 * per-feature wrapper allocations alone produced multi-second freezes during the initial
 * map draw.
 *
 * The encoder owns all separator-character bookkeeping (commas between features, commas
 * between properties), so call sites describe data only and never thread "have I written
 * one yet" flags through helper functions.
 *
 * Property names emitted here are fixed identifiers controlled by this plugin and its
 * layer expressions; they are appended raw without escaping. Only externally-supplied
 * string values (ids, titles, color hexes) flow through the JSON string-escape path.
 */
private class GeoJsonFeatureCollectionEncoder(initialCapacity: Int = 0) {
    private val out: StringBuilder =
        if (initialCapacity > 0) StringBuilder(initialCapacity) else StringBuilder()
    private val properties: PropertyWriter = PropertyWriter(out)
    private var hasFeature: Boolean = false

    init {
        out.append(FEATURE_COLLECTION_PREFIX)
    }

    fun pointFeature(
        longitude: Double,
        latitude: Double,
        properties: PropertyWriter.() -> Unit,
    ) {
        beginFeature()
        out.append(POINT_GEOMETRY_PREFIX)
        out.append(longitude).append(',').append(latitude)
        out.append(GEOMETRY_TO_PROPERTIES_BRIDGE)
        writeProperties(properties)
        out.append(FEATURE_SUFFIX)
    }

    fun lineFeature(
        coordinates: List<Pair<Double, Double>>,
        properties: PropertyWriter.() -> Unit,
    ) {
        beginFeature()
        out.append(LINE_STRING_GEOMETRY_PREFIX)
        coordinates.forEachIndexed { index, coord ->
            if (index > 0) out.append(',')
            appendCoordinatePair(coord)
        }
        out.append(GEOMETRY_TO_PROPERTIES_BRIDGE)
        writeProperties(properties)
        out.append(FEATURE_SUFFIX)
    }

    fun polygonFeature(
        rings: List<List<Pair<Double, Double>>>,
        properties: PropertyWriter.() -> Unit,
    ) {
        beginFeature()
        out.append(POLYGON_GEOMETRY_PREFIX)
        rings.forEachIndexed { ringIndex, ring ->
            if (ringIndex > 0) out.append(',')
            out.append('[')
            ring.forEachIndexed { coordIndex, coord ->
                if (coordIndex > 0) out.append(',')
                appendCoordinatePair(coord)
            }
            out.append(']')
        }
        out.append(GEOMETRY_TO_PROPERTIES_BRIDGE)
        writeProperties(properties)
        out.append(FEATURE_SUFFIX)
    }

    fun build(): String {
        out.append(FEATURE_COLLECTION_SUFFIX)
        return out.toString()
    }

    private fun beginFeature() {
        if (hasFeature) out.append(',') else hasFeature = true
        out.append(FEATURE_PREFIX)
    }

    private fun writeProperties(block: PropertyWriter.() -> Unit) {
        properties.beginBlock()
        properties.block()
    }

    /** Pairs are stored as `(latitude, longitude)`; GeoJSON requires `[lon, lat]`. */
    private fun appendCoordinatePair(coord: Pair<Double, Double>) {
        out.append('[').append(coord.second).append(',').append(coord.first).append(']')
    }

    /** Writes the `"properties":{ ... }` body of a single feature. */
    class PropertyWriter internal constructor(private val out: StringBuilder) {
        private var hasProperty: Boolean = false

        internal fun beginBlock() {
            hasProperty = false
        }

        fun string(name: String, value: String) {
            beginProperty(name)
            appendEscapedJsonString(out, value)
        }

        fun number(name: String, value: Float) {
            beginProperty(name)
            out.append(value)
        }

        fun number(name: String, value: Double) {
            beginProperty(name)
            out.append(value)
        }

        private fun beginProperty(name: String) {
            if (hasProperty) out.append(',') else hasProperty = true
            out.append('"').append(name).append("\":")
        }
    }

    companion object {
        const val EMPTY_FEATURE_COLLECTION_JSON: String =
            """{"type":"FeatureCollection","features":[]}"""

        private const val FEATURE_COLLECTION_PREFIX: String =
            """{"type":"FeatureCollection","features":["""
        private const val FEATURE_COLLECTION_SUFFIX: String = "]}"
        private const val FEATURE_PREFIX: String = """{"type":"Feature","geometry":"""
        private const val FEATURE_SUFFIX: String = "}}"
        private const val POINT_GEOMETRY_PREFIX: String = """{"type":"Point","coordinates":["""
        private const val LINE_STRING_GEOMETRY_PREFIX: String =
            """{"type":"LineString","coordinates":["""
        private const val POLYGON_GEOMETRY_PREFIX: String =
            """{"type":"Polygon","coordinates":["""
        private const val GEOMETRY_TO_PROPERTIES_BRIDGE: String = """]},"properties":{"""

        private val HEX_DIGITS: CharArray = "0123456789abcdef".toCharArray()

        private fun appendEscapedJsonString(out: StringBuilder, value: String) {
            out.append('"')
            for (i in value.indices) {
                val c = value[i]
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c == '\b' -> out.append("\\b")
                    c == '\u000C' -> out.append("\\f")
                    c.code < 0x20 -> {
                        val code = c.code
                        out.append("\\u")
                        out.append(HEX_DIGITS[(code shr 12) and 0xF])
                        out.append(HEX_DIGITS[(code shr 8) and 0xF])
                        out.append(HEX_DIGITS[(code shr 4) and 0xF])
                        out.append(HEX_DIGITS[code and 0xF])
                    }
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }
}

private fun buildPointsFeatureCollectionJson(points: List<MapRenderPoint>): String {
    val validPoints = filterMapRenderPointsForGeoJson(points)
    if (validPoints.isEmpty()) return GeoJsonFeatureCollectionEncoder.EMPTY_FEATURE_COLLECTION_JSON
    // Empirical sizing: most NGS station features serialize to ~140-200 chars; a small
    // pad keeps a single allocation for the common case without over-committing.
    val encoder = GeoJsonFeatureCollectionEncoder(initialCapacity = validPoints.size * 160 + 64)
    validPoints.forEach { point ->
        encoder.pointFeature(longitude = point.longitude, latitude = point.latitude) {
            string(PROPERTY_ID, point.id)
            val overlapForJson = point.overlapListLabel?.takeIf { it.isNotBlank() }
                ?: point.title?.takeIf { it.isNotBlank() }
                ?: ""
            string(PROPERTY_OVERLAP_LIST_LABEL, overlapForJson)
            point.title?.let { string(PROPERTY_TITLE, it) }
            point.iconImageId?.let { string("iconImageId", it) }
            point.pointRadius?.let { number("pointRadius", it) }
            point.pointFillColorHex?.let { string("pointFillColorHex", it) }
            point.pointStrokeColorHex?.let { string("pointStrokeColorHex", it) }
            point.pointStrokeWidth?.let { number("pointStrokeWidth", it) }
            point.labelTextColorHex?.let { string("labelTextColorHex", it) }
            point.labelTextSize?.let { number("labelTextSize", it) }
            point.iconSize?.let { number("iconSize", it) }
            point.iconRotationDegrees?.let { number("iconRotationDegrees", it.toDouble()) }
        }
    }
    return encoder.build()
}

private fun buildLinesFeatureCollectionJson(lines: List<MapRenderLine>): String {
    if (lines.isEmpty()) return GeoJsonFeatureCollectionEncoder.EMPTY_FEATURE_COLLECTION_JSON
    val encoder = GeoJsonFeatureCollectionEncoder()
    var hasAny = false
    lines.forEach { line ->
        val filtered = mapRenderLineToValidCoordinatesOrNull(line) ?: return@forEach
        hasAny = true
        encoder.lineFeature(coordinates = filtered) {
            string(PROPERTY_ID, line.id)
            line.title?.let { string(PROPERTY_TITLE, it) }
            string(OutlinedGeoJsonLineLayers.PROPERTY_LINE_COLOR, line.lineColorHex)
            number(PROPERTY_DRAW_OUTLINE, if (line.drawOutline) 1f else 0f)
            number(PROPERTY_LINE_WIDTH, line.lineWidthPx)
        }
    }
    if (!hasAny) return GeoJsonFeatureCollectionEncoder.EMPTY_FEATURE_COLLECTION_JSON
    return encoder.build()
}

private fun buildPolygonsFeatureCollectionJson(
    polygons: List<MapRenderPolygon>,
    emitLineColorProperty: Boolean,
): String {
    if (polygons.isEmpty()) return GeoJsonFeatureCollectionEncoder.EMPTY_FEATURE_COLLECTION_JSON
    val encoder = GeoJsonFeatureCollectionEncoder()
    var hasAny = false
    polygons.forEach { polygon ->
        val rings = filterMapRenderPolygonForGeoJson(polygon) ?: return@forEach
        hasAny = true
        encoder.polygonFeature(rings = rings) {
            string(PROPERTY_ID, polygon.id)
            polygon.title?.let { string(PROPERTY_TITLE, it) }
            string("fillColor", polygon.fillColorHex)
            string("outlineColor", polygon.outlineColorHex)
            if (emitLineColorProperty) {
                string(OutlinedGeoJsonLineLayers.PROPERTY_LINE_COLOR, polygon.outlineColorHex)
            }
        }
    }
    if (!hasAny) return GeoJsonFeatureCollectionEncoder.EMPTY_FEATURE_COLLECTION_JSON
    return encoder.build()
}
