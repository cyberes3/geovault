package com.geovault.common.map

import android.content.Context
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * Helper to make the user location chevron's white outer circle fill and thin black border
 * slightly transparent, and to restore it to normal. Use with a composite icon built from
 * [MapMarkerUtils.getMarkerBitmapWithTintedForeground] (circle background + tinted chevron).
 *
 * Usage:
 * 1. When adding the normal icon to the style, also call [ensureDimmedIconInStyle] to add
 *    a dimmed variant (circle + border at [backgroundAlpha]).
 * 2. To make the circle and border slightly transparent, call [setCircleAndBorderDimmed]
 *    with dimmed = true and the current location state.
 * 3. To restore to normal, call [setCircleAndBorderDimmed] with dimmed = false.
 */
object UserLocationIconHelper {

    const val DIMMED_ICON_SUFFIX = "_dimmed"

    /**
     * Adds a dimmed variant of the user location icon to the style (same composite but with
     * the background drawable — white circle + black border — drawn at [backgroundAlpha]).
     * Id of the dimmed image is [normalIconId][DIMMED_ICON_SUFFIX].
     *
     * @return The dimmed icon id to pass to [setCircleAndBorderDimmed].
     */
    fun ensureDimmedIconInStyle(
        style: Style,
        context: Context,
        normalIconId: String,
        backgroundResId: Int,
        foregroundFillResId: Int,
        foregroundStrokeResId: Int,
        tintColor: Int,
        backgroundAlpha: Float = 0.5f
    ): String {
        val dimmedIconId = normalIconId + DIMMED_ICON_SUFFIX
        if (style.getImage(dimmedIconId) != null) return dimmedIconId
        val bitmap = MapMarkerUtils.getMarkerBitmapWithTintedForeground(
            context,
            backgroundResId,
            foregroundFillResId,
            foregroundStrokeResId,
            tintColor,
            backgroundAlpha = backgroundAlpha
        )
        if (bitmap != null) {
            style.addImage(dimmedIconId, bitmap)
        }
        return dimmedIconId
    }

    /**
     * Switches the user location icon between normal and dimmed (transparent circle/border).
     * Updates the source feature so the layer's data-driven icon image changes. Pass the
     * current location state so the feature is unchanged except for the icon id.
     *
     * @param sourceId GeoJSON source id (e.g. [UserLocationMapLayer.USER_LOCATION_SOURCE_ID] or "user-location-source").
     * @param normalIconId Id of the normal icon image in the style.
     * @param dimmedIconId Id of the dimmed icon image (from [ensureDimmedIconInStyle]).
     */
    fun setCircleAndBorderDimmed(
        style: Style,
        sourceId: String,
        normalIconId: String,
        dimmedIconId: String,
        dimmed: Boolean,
        latLng: LatLng,
        bearingDegrees: Float? = null,
        labelText: String? = null
    ) {
        val source = style.getSourceAs<GeoJsonSource>(sourceId) ?: return
        val iconId = if (dimmed) dimmedIconId else normalIconId
        val point = Point.fromLngLat(latLng.longitude, latLng.latitude)
        val feature = Feature.fromGeometry(point)
        feature.addStringProperty("icon", iconId)
        feature.addNumberProperty("bearing", (bearingDegrees ?: 0f).toDouble())
        val label = labelText ?: ""
        feature.addStringProperty("label", label)
        feature.addStringProperty("distance_text", label)
        source.setGeoJson(feature)
    }
}
