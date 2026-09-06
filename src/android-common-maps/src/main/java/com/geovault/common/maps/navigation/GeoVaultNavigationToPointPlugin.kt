package com.geovault.common.maps.navigation

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.geovault.common.geo.GeoMath
import com.geovault.common.maps.core.GeoVaultMapPlugin
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
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
/**
 * MapLibre plugin: a dashed line from the user to the target and an optional name/distance
 * label on the **user** coordinate (under the location puck). The **target** is not rendered as
 * a second map pin: the map’s own station / point layer (e.g. [GeoJsonRenderPlugin]) already shows
 * it; a duplicate at the same coordinate is omitted. Hosts drive this with
 * [start], [stop], and [updateUserLocation].
 *
 * @param lineLayerRenderBelowId When set (e.g. the host's `\*-points-icon-layer` id) and the
 *   layer exists, the nav dash is inserted with [Style.addLayerBelow] so it draws **under**
 *   point symbols but **over** KML/GeoJSON line and polygon layers.
 * @param overlayStackAnchorAboveId Retained for binary/API compatibility with existing call sites;
 *   label z-order is no longer derived from this id. The label [SymbolLayer] is appended with
 *   [Style.addLayer] so it stays the topmost style layer (above GeoJSON symbols, basemap paint,
 *   and MapLibre's specialized location-indicator puck + accuracy).
 */
class GeoVaultNavigationToPointPlugin(
    @Suppress("unused") context: Context,
    private val lineLayerRenderBelowId: String? = null,
    @Suppress("unused") private val overlayStackAnchorAboveId: String? = null,
) : GeoVaultMapPlugin {
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

    private fun ensureLineLayer(style: Style) {
        if (style.getLayer(LINE_LAYER_ID) != null) return
        val lineLayer = LineLayer(LINE_LAYER_ID, SOURCE_ID)
            .withProperties(
                PropertyFactory.lineColor(GeoVaultColorTokens.MainPurple.toArgb()),
                PropertyFactory.lineWidth(LINE_WIDTH_PX),
                // Butt caps end stroke exactly at the line vertices. Round caps extend past each
                // end by ~lineWidth/2 px, so the nav dash looked like it ran past the target pin.
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                PropertyFactory.lineOpacity(LINE_OPACITY),
            )
        lineLayer.setFilter(
            Expression.eq(Expression.geometryType(), Expression.literal("LineString")),
        )
        if (style.getLayer(LABEL_LAYER_ID) != null) {
            style.addLayerBelow(lineLayer, LABEL_LAYER_ID)
        } else if (lineLayerRenderBelowId != null && style.getLayer(lineLayerRenderBelowId) != null) {
            style.addLayerBelow(lineLayer, lineLayerRenderBelowId)
        } else {
            style.addLayer(lineLayer)
        }
    }

    private fun ensureLabelLayer(style: Style) {
        if (style.getLayer(LABEL_LAYER_ID) != null) return
        addLabelLayer(style)
    }

    /**
     * Builds the navigation-text [SymbolLayer] and appends it with [Style.addLayer] so it is
     * the topmost layer in the style (above basemap content, GeoVault GeoJSON symbols, and
     * MapLibre’s location indicator including accuracy when using the specialized indicator layer).
     */
    private fun addLabelLayer(style: Style) {
        val textLayer = SymbolLayer(LABEL_LAYER_ID, LABEL_SOURCE_ID)
            .withProperties(
                PropertyFactory.textField(Expression.get(TEXT_PROPERTY)),
                PropertyFactory.textSize(LABEL_TEXT_SIZE_SP),
                PropertyFactory.textColor(GeoVaultColorTokens.MainPurple.toArgb()),
                PropertyFactory.textHaloColor(GeoVaultColorTokens.MapLineworkHalo.toArgb()),
                PropertyFactory.textHaloWidth(LABEL_HALO_WIDTH_PX),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, LABEL_OFFSET_EM)),
                PropertyFactory.textJustify(Property.TEXT_JUSTIFY_CENTER),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(false),
            )
        style.addLayer(textLayer)
    }

    /**
     * Ensures the navigation label remains the topmost style layer. MapLibre or other plugins
     * can insert layers after ours (e.g. style reload listener ordering); if [LABEL_LAYER_ID] is
     * not last in [Style.layers], we remove and re-append via [addLabelLayer].
     *
     * Cheap when there is nothing to do: a single list walk and no work when the label is
     * already last.
     */
    private fun ensureLabelTopmost(style: Style) {
        if (style.getLayer(LABEL_LAYER_ID) == null) return
        val layers = style.layers
        if (layers.isEmpty()) return
        if (layers.last().id == LABEL_LAYER_ID) return
        style.removeLayer(LABEL_LAYER_ID)
        addLabelLayer(style)
    }

    private fun ensureLineBelowLabelLayer(style: Style) {
        if (style.getLayer(LINE_LAYER_ID) == null || style.getLayer(LABEL_LAYER_ID) == null) return
        val layers = style.layers
        val lineIdx = layers.indexOfFirst { it.id == LINE_LAYER_ID }
        val labelIdx = layers.indexOfFirst { it.id == LABEL_LAYER_ID }
        if (lineIdx < 0 || labelIdx < 0 || lineIdx < labelIdx) return
        style.removeLayer(LINE_LAYER_ID)
        ensureLineLayer(style)
    }

    private fun applyToStyle() {
        val style = style ?: return
        ensureLabelTopmost(style)
        ensureLineBelowLabelLayer(style)
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

        const val LINE_FEATURE_ID: String = "gv-nav-line"
        const val LABEL_FEATURE_ID: String = "gv-nav-label"

        fun buildFeatureCollection(
            targetLatitude: Double?,
            targetLongitude: Double?,
            userLatitude: Double?,
            userLongitude: Double?,
        ): FeatureCollection {
            if (targetLatitude == null || targetLongitude == null) {
                return FeatureCollection.fromFeatures(emptyList())
            }
            if (userLatitude == null || userLongitude == null) {
                // No line until we have a user fix; the map's own point layer shows the target.
                return FeatureCollection.fromFeatures(emptyList())
            }
            if (!isValidMapLibreGeographicLatLng(targetLatitude, targetLongitude) ||
                !isValidMapLibreGeographicLatLng(userLatitude, userLongitude)
            ) {
                return FeatureCollection.fromFeatures(emptyList())
            }
            val targetPoint = Point.fromLngLat(targetLongitude, targetLatitude)
            val userPoint = Point.fromLngLat(userLongitude, userLatitude)
            val line = Feature.fromGeometry(
                LineString.fromLngLats(listOf(userPoint, targetPoint)),
                null,
                LINE_FEATURE_ID,
            )
            return FeatureCollection.fromFeatures(listOf(line))
        }

        /** Point at **user** lat/lon so text+offset sit under the location puck. */
        fun buildLabelFeatureCollection(
            userLatitude: Double?,
            userLongitude: Double?,
            title: String?,
            distanceMeters: Double?,
        ): FeatureCollection {
            if (userLatitude == null || userLongitude == null) {
                return FeatureCollection.fromFeatures(emptyList())
            }
            if (!isValidMapLibreGeographicLatLng(userLatitude, userLongitude)) {
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
        GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    companion object {
        const val SOURCE_ID: String = "gv-common-nav-to-point-source"
        const val LABEL_SOURCE_ID: String = "gv-common-nav-to-point-label-source"
        const val LINE_LAYER_ID: String = "gv-common-nav-to-point-line-layer"
        const val LABEL_LAYER_ID: String = "gv-common-nav-to-point-label-layer"

        internal const val TEXT_PROPERTY: String = "text"

        private const val LINE_WIDTH_PX = 4f
        private const val LINE_OPACITY = 1f
        private const val LABEL_TEXT_SIZE_SP = 13f
        private const val LABEL_HALO_WIDTH_PX = 1.5f
        /** Ems below the [Property.TEXT_ANCHOR_TOP] anchor (sits just under the location puck). */
        private const val LABEL_OFFSET_EM = 1.25f
    }
}
