package com.geovault.common.maps.render

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.geovault.common.maps.core.MapMarkerUtils
import com.geovault.common.maps.core.GeoVaultMapPlugin
import com.geovault.common.maps.core.OutlinedGeoJsonLineLayers
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

class GeoJsonRenderPlugin(
    private val sourceIdPrefix: String = "gv-common-render",
    private val config: GeoJsonRenderConfig = GeoJsonRenderConfig(),
    private val context: Context? = null,
) : GeoVaultMapPlugin, GeoVaultRenderCapability {
    private var renderState: MapRenderState = MapRenderState()
    private var map: MapLibreMap? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var marshaledApply: Runnable? = null

    override fun setRenderState(newState: MapRenderState) {
        renderState = newState
        fun applyNow() {
            marshaledApply = null
            val style = map?.style ?: return
            applyState(style, renderState)
        }
        if (config.synchronousGeoJsonApplication) {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "GeoJsonRenderPlugin: synchronousGeoJsonApplication requires main thread"
            }
            applyNow()
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyNow()
        } else {
            marshaledApply?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable { applyNow() }
            marshaledApply = runnable
            mainHandler.post(runnable)
        }
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
    }

    override fun onStyleLoaded(map: MapLibreMap, style: Style) {
        this.map = map
        ensureCommonPlacemarkImages(style)
        ensureLayers(style)
        applyState(style, renderState)
    }

    private fun ensureLayers(style: Style) {
        ensureSource(style, pointsSourceId)
        ensureSource(style, linesSourceId)
        ensureSource(style, polygonsSourceId)

        if (config.showPointCircles && style.getLayer(pointsCircleLayerId) == null) {
            addLayerWithPlacement(
                style,
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
                ),
            )
        }
        if (config.showPointLabelsAndIcons && style.getLayer(pointsSymbolLayerId) == null) {
            addLayerWithPlacement(
                style,
                SymbolLayer(pointsSymbolLayerId, pointsSourceId).withProperties(
                    PropertyFactory.textField(
                        if (config.showPointTextLabels) {
                            Expression.get("title")
                        } else {
                            Expression.literal("")
                        },
                    ),
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
                    PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
                    PropertyFactory.iconImage(Expression.get("iconImageId")),
                    PropertyFactory.iconSize(
                        Expression.coalesce(
                            Expression.get("iconSize"),
                            Expression.literal(config.defaultIconSize),
                        ),
                    ),
                    PropertyFactory.iconRotate(
                        Expression.coalesce(
                            Expression.toNumber(Expression.get("iconRotationDegrees")),
                            Expression.literal(0.0),
                        ),
                    ),
                    PropertyFactory.iconAllowOverlap(true),
                ),
            )
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
                    context = context?.applicationContext,
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
    }

    private fun applyState(style: Style, state: MapRenderState) {
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
                feature.addStringProperty(OutlinedGeoJsonLineLayers.PROPERTY_OUTLINE_COLOR, line.outlineColorHex)
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
            }
        }
        updateSource(style, pointsSourceId, pointFeatures)
        updateSource(style, linesSourceId, lineFeatures)
        updateSource(style, polygonsSourceId, polygonFeatures)
    }

    private fun updateSource(style: Style, id: String, features: List<Feature>) {
        val source = style.getSourceAs<GeoJsonSource>(id) ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun ensureSource(style: Style, id: String) {
        if (style.getSource(id) == null) {
            style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList())))
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
    }

    private fun buildResolvedMarkerStyles(context: Context): Map<String, MapMarkerStyle> {
        return mapOf(
            CommonMapIconIds.MARKER_DEFAULT to CommonMapMarkerStyles.default(context),
            CommonMapIconIds.MARKER_SELECTED to CommonMapMarkerStyles.selected(context),
        ) + config.markerStyles
    }

    private fun addLayerWithPlacement(style: Style, layer: org.maplibre.android.style.layers.Layer) {
        val belowLayerId = config.belowLayerId
        if (!belowLayerId.isNullOrBlank() && style.getLayer(belowLayerId) != null) {
            style.addLayerBelow(layer, belowLayerId)
        } else {
            style.addLayer(layer)
        }
    }

    private val pointsSourceId = "$sourceIdPrefix-points-source"
    private val linesSourceId = "$sourceIdPrefix-lines-source"
    private val polygonsSourceId = "$sourceIdPrefix-polygons-source"
    private val pointsCircleLayerId = "$sourceIdPrefix-points-circle-layer"
    private val pointsSymbolLayerId = "$sourceIdPrefix-points-label-layer"
    private val lineOuterLayerId = "$sourceIdPrefix-lines-outer-layer"
    private val lineBorderLayerId = "$sourceIdPrefix-lines-border-layer"
    private val lineFillLayerId = "$sourceIdPrefix-lines-fill-layer"
    private val polygonsFillLayerId = "$sourceIdPrefix-polygons-fill-layer"
    private val polygonsOutlineLayerId = "$sourceIdPrefix-polygons-outline-layer"
}
