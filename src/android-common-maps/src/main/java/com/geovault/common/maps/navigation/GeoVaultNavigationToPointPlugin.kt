package com.geovault.common.maps.navigation

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.maps.core.GeoVaultMapPlugin
import com.geovault.common.maps.core.MapMarkerUtils
import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.common.maps.render.CommonMapMarkerStyles
import com.geovault.common.ui.theme.GeoVaultColorTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MapLibre plugin: dashed line from user to target, purple triple-ring target icon (same draw
 * style as the default map point, via [MapMarkerUtils] + [CommonMapMarkerStyles]),
 * and an optional name/distance label on the **user** coordinate (under the location puck;
 * same placement as the legacy Android survey `MapFragment` navigation text). Hosts drive it
 * with [start], [stop], and [updateUserLocation].
 *
 * @param lineLayerRenderBelowId When set (e.g. the host's `\*-points-icon-layer` id) and the
 *   layer exists, the nav dash is inserted with [Style.addLayerBelow] so it draws **under**
 *   point symbols but **over** KML/GeoJSON line and polygon layers.
 * @param overlayStackAnchorAboveId When set (e.g. the host's top `\*-points-label-layer` id) and
 *   the layer exists, the nav target icon and the user text label are inserted with
 *   [Style.addLayerAbove] so they draw **on top of** the point symbol stack (so the purple
 *   target is not covered by a blue point icon at the same coordinate).
 */
class GeoVaultNavigationToPointPlugin(
    context: Context,
    private val lineLayerRenderBelowId: String? = null,
    private val overlayStackAnchorAboveId: String? = null,
) : GeoVaultMapPlugin {

    /** [Context.getApplicationContext] for bitmaps and other app-scoped work. */
    private val context: Context = context.applicationContext
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private var target: LatLon? = null
    private var targetTitle: String? = null
    private var userLocation: LatLon? = null

    private val _distanceMeters = MutableStateFlow<Double?>(null)
    val distanceMeters: StateFlow<Double?> = _distanceMeters.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun start(latitude: Double, longitude: Double, title: String? = null) {
        target = LatLon(latitude, longitude)
        targetTitle = title?.trim()?.takeIf { it.isNotBlank() }
        _isActive.value = true
        applyToStyle()
    }

    fun stop() {
        target = null
        targetTitle = null
        _isActive.value = false
        _distanceMeters.value = null
        applyToStyle()
    }

    fun updateUserLocation(latitude: Double, longitude: Double) {
        userLocation = LatLon(latitude, longitude)
        recomputeDistance()
        applyToStyle()
    }

    override fun onMapAttached(map: MapLibreMap) {
        this.map = map
    }

    override fun onMapDetached() {
        map = null
        style = null
    }

    override fun onStyleLoaded(map: MapLibreMap, style: Style) {
        this.map = map
        this.style = style
        ensureSources(style)
        ensureLineLayer(style)
        ensureNavigationImage(style)
        ensureTargetIconLayer(style)
        ensureLabelLayer(style)
        applyToStyle()
    }

    override fun onPluginDestroyed() {
        map = null
        style = null
        target = null
        targetTitle = null
        userLocation = null
        _isActive.value = false
        _distanceMeters.value = null
    }

    private fun ensureSources(style: Style) {
        if (style.getSource(SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        }
        if (style.getSource(LABEL_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(LABEL_SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
        }
    }

    private fun ensureNavigationImage(style: Style) {
        if (style.getImage(CommonMapIconIds.NAVIGATION_TO_POINT_TARGET) != null) return
        val bitmap = MapMarkerUtils.buildMarkerBitmap(
            context,
            CommonMapMarkerStyles.navigationToPointTarget(),
        )
        style.addImage(CommonMapIconIds.NAVIGATION_TO_POINT_TARGET, bitmap, false)
    }

    private fun ensureLineLayer(style: Style) {
        if (style.getLayer(LINE_LAYER_ID) != null) return
        val lineLayer = LineLayer(LINE_LAYER_ID, SOURCE_ID)
            .withProperties(
                PropertyFactory.lineColor(GeoVaultColorTokens.MainPurple.toArgb()),
                PropertyFactory.lineWidth(LINE_WIDTH_PX),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                PropertyFactory.lineOpacity(LINE_OPACITY),
            )
        lineLayer.setFilter(
            Expression.eq(Expression.geometryType(), Expression.literal("LineString")),
        )
        if (lineLayerRenderBelowId != null && style.getLayer(lineLayerRenderBelowId) != null) {
            style.addLayerBelow(lineLayer, lineLayerRenderBelowId)
        } else {
            style.addLayer(lineLayer)
        }
    }

    private fun ensureTargetIconLayer(style: Style) {
        if (style.getLayer(TARGET_ICON_LAYER_ID) != null) return
        val targetLayer = SymbolLayer(TARGET_ICON_LAYER_ID, SOURCE_ID)
            .withProperties(
                PropertyFactory.iconImage(CommonMapIconIds.NAVIGATION_TO_POINT_TARGET),
                PropertyFactory.iconSize(ICON_SIZE),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            )
        targetLayer.setFilter(
            Expression.eq(Expression.geometryType(), Expression.literal("Point")),
        )
        if (overlayStackAnchorAboveId != null && style.getLayer(overlayStackAnchorAboveId) != null) {
            style.addLayerAbove(targetLayer, overlayStackAnchorAboveId)
        } else {
            style.addLayer(targetLayer)
        }
    }

    private fun ensureLabelLayer(style: Style) {
        if (style.getLayer(LABEL_LAYER_ID) != null) return
        val textLayer = SymbolLayer(LABEL_LAYER_ID, LABEL_SOURCE_ID)
            .withProperties(
                PropertyFactory.textField(Expression.get(TEXT_PROPERTY)),
                PropertyFactory.textSize(LABEL_TEXT_SIZE_SP),
                PropertyFactory.textColor(GeoVaultColorTokens.MapLabelText.toArgb()),
                PropertyFactory.textHaloColor(GeoVaultColorTokens.MapLineworkHalo.toArgb()),
                PropertyFactory.textHaloWidth(LABEL_HALO_WIDTH_PX),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, LABEL_OFFSET_EM)),
                PropertyFactory.textJustify(Property.TEXT_JUSTIFY_CENTER),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            )
        if (style.getLayer(TARGET_ICON_LAYER_ID) != null) {
            style.addLayerAbove(textLayer, TARGET_ICON_LAYER_ID)
        } else if (overlayStackAnchorAboveId != null && style.getLayer(overlayStackAnchorAboveId) != null) {
            style.addLayerAbove(textLayer, overlayStackAnchorAboveId)
        } else {
            style.addLayer(textLayer)
        }
    }

    private fun applyToStyle() {
        val style = style ?: return
        val navSource = style.getSource(SOURCE_ID) as? GeoJsonSource
        val labelSource = style.getSource(LABEL_SOURCE_ID) as? GeoJsonSource
        navSource?.setGeoJson(buildFeatureCollection(target, userLocation))
        labelSource?.setGeoJson(
            buildLabelFeatureCollection(
                userLocation = userLocation,
                title = targetTitle,
                distanceMeters = _distanceMeters.value,
            ),
        )
    }

    private fun recomputeDistance() {
        val t = target
        val u = userLocation
        _distanceMeters.value = if (t != null && u != null) haversineMeters(t, u) else null
    }

    private data class LatLon(val latitude: Double, val longitude: Double)

    internal object RenderGeometry {

        const val TARGET_FEATURE_ID: String = "gv-nav-target"
        const val LINE_FEATURE_ID: String = "gv-nav-line"
        const val LABEL_FEATURE_ID: String = "gv-nav-label"

        fun buildFeatureCollection(
            targetLatitude: Double?,
            targetLongitude: Double?,
            userLatitude: Double?,
            userLongitude: Double?,
        ): FeatureCollection {
            val features = mutableListOf<Feature>()
            if (targetLatitude != null && targetLongitude != null) {
                val targetPoint = Point.fromLngLat(targetLongitude, targetLatitude)
                features.add(Feature.fromGeometry(targetPoint, null, TARGET_FEATURE_ID))
                if (userLatitude != null && userLongitude != null) {
                    val userPoint = Point.fromLngLat(userLongitude, userLatitude)
                    features.add(
                        Feature.fromGeometry(
                            LineString.fromLngLats(listOf(userPoint, targetPoint)),
                            null,
                            LINE_FEATURE_ID,
                        ),
                    )
                }
            }
            return FeatureCollection.fromFeatures(features)
        }

        /**
         * Point at **user** lat/lon so text+offset sit under the location puck (legacy survey
         * `NAV_TARGET_NAME` point-at-user-lat-lon in the old survey `MapFragment`).
         */
        fun buildLabelFeatureCollection(
            userLatitude: Double?,
            userLongitude: Double?,
            title: String?,
            distanceMeters: Double?,
        ): FeatureCollection {
            if (userLatitude == null || userLongitude == null) {
                return FeatureCollection.fromFeatures(emptyList())
            }
            val text = NavigationDistanceFormatter.format(title, distanceMeters)
            if (text.isBlank()) return FeatureCollection.fromFeatures(emptyList())
            val feature = Feature.fromGeometry(
                Point.fromLngLat(userLongitude, userLatitude),
                null,
                LABEL_FEATURE_ID,
            )
            feature.addStringProperty(TEXT_PROPERTY, text)
            return FeatureCollection.fromFeatures(listOf(feature))
        }

        fun haversineMeters(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val rLat1 = Math.toRadians(lat1)
            val rLat2 = Math.toRadians(lat2)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
        }

        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }

    private fun buildFeatureCollection(
        target: LatLon?,
        user: LatLon?,
    ): FeatureCollection = RenderGeometry.buildFeatureCollection(
        targetLatitude = target?.latitude,
        targetLongitude = target?.longitude,
        userLatitude = user?.latitude,
        userLongitude = user?.longitude,
    )

    private fun buildLabelFeatureCollection(
        userLocation: LatLon?,
        title: String?,
        distanceMeters: Double?,
    ): FeatureCollection = RenderGeometry.buildLabelFeatureCollection(
        userLatitude = userLocation?.latitude,
        userLongitude = userLocation?.longitude,
        title = title,
        distanceMeters = distanceMeters,
    )

    private fun haversineMeters(a: LatLon, b: LatLon): Double =
        RenderGeometry.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    companion object {
        const val SOURCE_ID: String = "gv-common-nav-to-point-source"
        const val LABEL_SOURCE_ID: String = "gv-common-nav-to-point-label-source"
        const val LINE_LAYER_ID: String = "gv-common-nav-to-point-line-layer"
        const val TARGET_ICON_LAYER_ID: String = "gv-common-nav-to-point-target-icon-layer"
        const val LABEL_LAYER_ID: String = "gv-common-nav-to-point-label-layer"

        internal const val TEXT_PROPERTY: String = "text"

        private const val LINE_WIDTH_PX = 4f
        private const val LINE_OPACITY = 0.85f
        private const val ICON_SIZE = 1f

        private const val LABEL_TEXT_SIZE_SP = 13f
        private const val LABEL_HALO_WIDTH_PX = 1.5f
        /**
         * Ems below the [Property.TEXT_ANCHOR_TOP] anchor; legacy survey `MapFragment` used
         * 1.25f so the block sits just under the user location puck.
         */
        private const val LABEL_OFFSET_EM = 1.25f
    }
}
