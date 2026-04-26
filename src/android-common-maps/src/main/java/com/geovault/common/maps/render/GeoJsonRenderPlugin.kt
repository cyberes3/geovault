package com.geovault.common.maps.render

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.geovault.common.maps.core.MapMarkerUtils
import com.geovault.common.maps.core.GeoVaultMapPlugin
import com.geovault.common.maps.core.OutlinedGeoJsonLineLayers
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.maplibre.android.maps.MapLibreMap
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
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private data class PreparedGeoJsonRenderState(
    val points: FeatureCollection,
    val lines: FeatureCollection,
    val polygons: FeatureCollection,
)

/**
 * Renders [MapRenderState] as MapLibre GeoJSON sources and layers.
 *
 * Labeled point features use a built-in **icon + label** symbol stack (see [GeoJsonRenderConfig])
 * whenever text labels are enabled—callers should not duplicate collision logic in app code.
 */
class GeoJsonRenderPlugin(
    private val sourceIdPrefix: String = "gv-common-render",
    private val config: GeoJsonRenderConfig = GeoJsonRenderConfig(),
    private val context: Context? = null,
) : GeoVaultMapPlugin, GeoVaultRenderCapability {
    @Volatile
    private var renderState: MapRenderState = MapRenderState()
    private var map: MapLibreMap? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var marshaledApply: Runnable? = null
    private val renderGeneration = AtomicLong(0L)
    private val destroyed = AtomicBoolean(false)
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GeoJsonRenderPlugin-$sourceIdPrefix").apply {
            isDaemon = true
        }
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

    override fun onMapDetached() {
        map = null
    }

    override fun onPluginDestroyed() {
        marshaledApply?.let { mainHandler.removeCallbacks(it) }
        marshaledApply = null
        destroyed.set(true)
        renderGeneration.incrementAndGet()
        renderExecutor.shutdownNow()
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
            val iconSizeExpr = Expression.coalesce(
                Expression.get("iconSize"),
                Expression.literal(config.defaultIconSize),
            )
            val iconRotateExpr = Expression.coalesce(
                Expression.toNumber(Expression.get("iconRotationDegrees")),
                Expression.literal(0.0),
            )
            val iconLayer = SymbolLayer(pointsIconLayerId, pointsSourceId).withProperties(
                PropertyFactory.textField(Expression.literal("")),
                PropertyFactory.iconImage(Expression.get("iconImageId")),
                PropertyFactory.iconSize(iconSizeExpr),
                PropertyFactory.iconAnchor(config.defaultIconAnchor),
                PropertyFactory.iconRotate(iconRotateExpr),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ).withUnclusteredPointFilter()
            if (config.disablePointSymbolFade) {
                val instant = TransitionOptions(0L, 0L)
                iconLayer.setIconOpacityTransition(instant)
            }
            val labelLayer: SymbolLayer? = if (config.showPointTextLabels) {
                SymbolLayer(pointsLabelLayerId, pointsSourceId).withProperties(
                    PropertyFactory.iconImage(Expression.get("iconImageId")),
                    PropertyFactory.iconOpacity(Expression.literal(0.0)),
                    PropertyFactory.iconSize(iconSizeExpr),
                    PropertyFactory.iconAnchor(config.defaultIconAnchor),
                    PropertyFactory.iconRotate(iconRotateExpr),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.textField(Expression.get("title")),
                    PropertyFactory.textSize(
                        Expression.coalesce(
                            Expression.get("labelTextSize"),
                            Expression.literal(config.defaultLabelTextSize),
                        ),
                    ),
                    PropertyFactory.textColor(
                        Expression.coalesce(
                            Expression.get("labelTextColorHex"),
                            Expression.literal(config.defaultLabelTextColorHex),
                        ),
                    ),
                    // Top anchor + downward offset: long / multi-line labels extend below the
                    // marker instead of growing upward over the icon (center anchor default).
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 0.85f)),
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textIgnorePlacement(false),
                ).withUnclusteredPointFilter().also { layer ->
                    if (config.disablePointSymbolFade) {
                        val instant = TransitionOptions(0L, 0L)
                        layer.setIconOpacityTransition(instant)
                        layer.setTextOpacityTransition(instant)
                    }
                }
            } else {
                null
            }
            fun attachPointSymbolLayers() {
                addPointPresentationLayer(iconLayer)
                if (labelLayer != null) {
                    if (config.renderPointSymbolsAboveLines) {
                        pendingPointPresentationLayers += labelLayer
                    } else {
                        style.addLayerAbove(labelLayer, pointsIconLayerId)
                    }
                }
            }
            attachPointSymbolLayers()
        }
        if (style.getLayer(lineOuterLayerId) == null) {
            addLayerWithPlacement(
                style,
                OutlinedGeoJsonLineLayers.createOuterLayer(
                    layerId = lineOuterLayerId,
                    sourceId = linesSourceId,
                )
            )
        }
        if (style.getLayer(lineBorderLayerId) == null) {
            addLayerWithPlacement(
                style,
                OutlinedGeoJsonLineLayers.createBorderLayer(
                    layerId = lineBorderLayerId,
                    sourceId = linesSourceId,
                )
            )
        }
        if (style.getLayer(lineFillLayerId) == null) {
            addLayerWithPlacement(style, OutlinedGeoJsonLineLayers.createFillLayer(lineFillLayerId, linesSourceId))
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
        if (config.showPolygonOutline && style.getLayer(polygonsOutlineLayerId) == null) {
            addLayerWithPlacement(
                style,
                LineLayer(polygonsOutlineLayerId, polygonsSourceId).withProperties(
                    PropertyFactory.lineColor(Expression.get("outlineColor")),
                    PropertyFactory.lineWidth(config.defaultPolygonOutlineWidth),
                ),
            )
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
        val pointFeatures = state.points.map { point ->
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).also { feature ->
                feature.addStringProperty("id", point.id)
                point.title?.let { feature.addStringProperty("title", it) }
                point.iconImageId?.let { feature.addStringProperty("iconImageId", it) }
                point.pointRadius?.let { feature.addNumberProperty("pointRadius", it) }
                point.pointFillColorHex?.let { feature.addStringProperty("pointFillColorHex", it) }
                point.pointStrokeColorHex?.let { feature.addStringProperty("pointStrokeColorHex", it) }
                point.pointStrokeWidth?.let { feature.addNumberProperty("pointStrokeWidth", it) }
                point.labelTextColorHex?.let { feature.addStringProperty("labelTextColorHex", it) }
                point.labelTextSize?.let { feature.addNumberProperty("labelTextSize", it) }
                point.iconSize?.let { feature.addNumberProperty("iconSize", it) }
                point.iconRotationDegrees?.let { feature.addNumberProperty("iconRotationDegrees", it.toDouble()) }
            }
        }
        val lineFeatures = state.lines.map { line ->
            Feature.fromGeometry(
                LineString.fromLngLats(
                    line.coordinates.map { (lat, lon) -> Point.fromLngLat(lon, lat) },
                ),
            ).also { feature ->
                feature.addStringProperty("id", line.id)
                feature.addStringProperty(OutlinedGeoJsonLineLayers.PROPERTY_LINE_COLOR, line.lineColorHex)
            }
        }
        val polygonFeatures = state.polygons.map { polygon ->
            val rings = polygon.rings.map { ring ->
                ring.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
            }
            Feature.fromGeometry(Polygon.fromLngLats(rings)).also { feature ->
                feature.addStringProperty("id", polygon.id)
                feature.addStringProperty("fillColor", polygon.fillColorHex)
                feature.addStringProperty("outlineColor", polygon.outlineColorHex)
                if (!config.showPolygonOutline) {
                    feature.addStringProperty(
                        OutlinedGeoJsonLineLayers.PROPERTY_LINE_COLOR,
                        polygon.outlineColorHex,
                    )
                }
            }
        }
        return PreparedGeoJsonRenderState(
            points = FeatureCollection.fromFeatures(pointFeatures),
            lines = FeatureCollection.fromFeatures(lineFeatures),
            polygons = FeatureCollection.fromFeatures(polygonFeatures),
        )
    }

    private fun applyPreparedState(prepared: PreparedGeoJsonRenderState) {
        val style = map?.style ?: return
        updateSource(style, pointsSourceId, prepared.points)
        updateSource(style, linesSourceId, prepared.lines)
        updateSource(style, polygonsSourceId, prepared.polygons)
    }

    private fun updateSource(style: Style, id: String, featureCollection: FeatureCollection) {
        val source = style.getSourceAs<GeoJsonSource>(id) ?: return
        source.setGeoJson(featureCollection)
    }

    private fun ensureSource(
        style: Style,
        id: String,
        options: GeoJsonOptions? = buildGeoJsonOptions(pointClustering = null),
    ) {
        if (style.getSource(id) == null) {
            val emptyCollection = FeatureCollection.fromFeatures(emptyList())
            if (options == null) {
                style.addSource(GeoJsonSource(id, emptyCollection))
            } else {
                style.addSource(GeoJsonSource(id, emptyCollection, options))
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
        ) + config.markerStyles
    }

    private fun buildResolvedSymbolIconStyles(): Map<String, MapSymbolIconStyle> {
        return mapOf(
            CommonMapIconIds.STATION_POINT to CommonMapSymbolIconStyles.station(),
            CommonMapIconIds.STATION_POINT_SELECTED to CommonMapSymbolIconStyles.selectedStation(),
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
            withFilter(Expression.neq(Expression.get(PROPERTY_CLUSTER), true))
        }

    private fun SymbolLayer.withUnclusteredPointFilter(): SymbolLayer =
        if (config.pointClustering == null) {
            this
        } else {
            withFilter(Expression.neq(Expression.get(PROPERTY_CLUSTER), true))
        }

    private val pointsSourceId = pointsSourceId(sourceIdPrefix)
    private val linesSourceId = "$sourceIdPrefix-lines-source"
    private val polygonsSourceId = "$sourceIdPrefix-polygons-source"
    private val pointsCircleLayerId = "$sourceIdPrefix-points-circle-layer"
    /** Visible markers; always above linework when [GeoJsonRenderConfig.renderPointSymbolsAboveLines]. */
    private val pointsIconLayerId = pointsIconLayerId(sourceIdPrefix)
    /** Text stacked above [pointsIconLayerId]; collision hides overlapping labels, not icons. */
    private val pointsLabelLayerId = pointsLabelLayerId(sourceIdPrefix)
    private val lineOuterLayerId = "$sourceIdPrefix-lines-outer-layer"
    private val lineBorderLayerId = "$sourceIdPrefix-lines-border-layer"
    private val lineFillLayerId = "$sourceIdPrefix-lines-fill-layer"
    private val polygonsFillLayerId = "$sourceIdPrefix-polygons-fill-layer"
    private val polygonsOutlineLayerId = "$sourceIdPrefix-polygons-outline-layer"

    companion object {
        private const val PROPERTY_CLUSTER: String = "cluster"
        private const val PROPERTY_POINT_COUNT: String = "point_count"

        fun pointsSourceId(sourceIdPrefix: String): String = "$sourceIdPrefix-points-source"

        fun pointsIconLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-points-icon-layer"

        fun pointsLabelLayerId(sourceIdPrefix: String): String = "$sourceIdPrefix-points-label-layer"

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
