package com.geovault.common.map

import android.content.Context
import androidx.core.content.ContextCompat
import com.geovault.common.R
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * Helper to add and update a single "user location" point on a MapLibre style
 * (e.g. device position or any position with optional bearing and label).
 */
object UserLocationMapLayer {

    const val USER_LOCATION_SOURCE_ID = "gv_common_user_location_source"
    const val USER_LOCATION_LAYER_ID = "gv_common_user_location_layer"
    private const val DEFAULT_ICON_ID = "gv_common_user_location_icon"

    data class Options(
        val iconDrawableResId: Int = R.drawable.gv_common_ic_user_location_arrow,
        val iconSize: Float = 0.75f,
        val showLabel: Boolean = false,
        val tintColor: Int? = null
    )

    /**
     * Adds the user location GeoJSON source and SymbolLayer to the style.
     * Call once from onMapReady/onStyleLoaded.
     */
    fun attachToStyle(style: Style, context: Context, options: Options = Options()) {
        if (style.getSource(USER_LOCATION_SOURCE_ID) != null) return
        style.addSource(GeoJsonSource(USER_LOCATION_SOURCE_ID))
        val iconBitmap = if (options.tintColor != null) {
            MapMarkerUtils.getMarkerBitmap(context, options.iconDrawableResId, options.tintColor!!)
        } else {
            MapMarkerUtils.getMarkerBitmap(context, options.iconDrawableResId)
        }
        if (iconBitmap != null && style.getImage(DEFAULT_ICON_ID) == null) {
            style.addImage(DEFAULT_ICON_ID, iconBitmap)
        }
        val symbolLayer = SymbolLayer(USER_LOCATION_LAYER_ID, USER_LOCATION_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.iconImage(DEFAULT_ICON_ID),
                PropertyFactory.iconSize(options.iconSize),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
            if (options.showLabel) {
                setProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textOffset(arrayOf(0f, 1.25f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textSize(14f),
                    PropertyFactory.textColor(ContextCompat.getColor(context, R.color.gv_common_primary_blue)),
                    PropertyFactory.textHaloColor(ContextCompat.getColor(context, android.R.color.white)),
                    PropertyFactory.textHaloWidth(2f),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textAllowOverlap(true)
                )
            }
        }
        style.addLayer(symbolLayer)
    }

    /**
     * Updates the single-point GeoJSON feature for the user location.
     * [bearingDegrees] rotates the icon (e.g. for compass); [labelText] is optional (e.g. distance).
     */
    fun updateLocation(
        style: Style,
        latLng: LatLng,
        bearingDegrees: Float? = null,
        labelText: String? = null
    ) {
        val source = style.getSourceAs<GeoJsonSource>(USER_LOCATION_SOURCE_ID) ?: return
        val point = Point.fromLngLat(latLng.longitude, latLng.latitude)
        val feature = Feature.fromGeometry(point)
        feature.addNumberProperty("bearing", (bearingDegrees ?: 0f).toDouble())
        feature.addStringProperty("label", labelText ?: "")
        source.setGeoJson(feature)
    }
}
