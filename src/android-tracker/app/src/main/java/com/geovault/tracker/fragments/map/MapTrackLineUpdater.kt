package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.common.map.LocationComponentHelper
import com.geovault.tracker.defaultTrackerColorHex
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

internal object MapTrackLineUpdater {
    private fun isValidPoint(latLng: LatLng): Boolean {
        return latLng.latitude.isFinite() &&
            latLng.longitude.isFinite() &&
            latLng.latitude in -90.0..90.0 &&
            latLng.longitude in -180.0..180.0
    }

    fun updateTrackLine(
        style: Style,
        trackSourceId: String,
        trackPoints: List<LatLng>,
        lineColor: String,
        outlineColor: String,
        maxJumpMeters: Float
    ): Boolean {
        val source = style.getSourceAs<GeoJsonSource>(trackSourceId) ?: return false
        if (trackPoints.size < 2) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return false
        }
        val feature = MapTrackGeometryRenderer.buildTrackFeature(
            trackPoints = trackPoints,
            lineColor = lineColor,
            outlineColor = outlineColor,
            maxJumpMeters = maxJumpMeters
        ) ?: run {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return false
        }
        source.setGeoJson(feature)
        return true
    }

    fun applyPositionSymbolUpdate(
        context: Context,
        map: MapLibreMap,
        style: Style,
        trackPoints: List<LatLng>,
        currentTrackerColor: String?,
        showMyLocationEnabled: Boolean,
        lastStreamedAccuracyMeters: Float?,
        trackingServiceAccuracyMeters: Float?,
        trackPositionSourceId: String,
        trackPositionAccuracySourceId: String,
        ensureArrowImage: (Style, String) -> Unit
    ) {
        val source = style.getSourceAs<GeoJsonSource>(trackPositionSourceId) ?: return
        val accuracySource = style.getSourceAs<GeoJsonSource>(trackPositionAccuracySourceId)

        if (trackPoints.isEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            accuracySource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }

        val toLatLng = trackPoints.lastOrNull(::isValidPoint)
        if (toLatLng == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            accuracySource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val toRotation = MapTrackGeometryRenderer.getTrackDirectionDegrees(trackPoints)
        val hexColor = currentTrackerColor ?: defaultTrackerColorHex(context)
        val imageId = "track-direction-arrow-${hexColor.replace("#", "")}"
        var symbolIconId = imageId

        ensureArrowImage(style, hexColor)
        if (style.getImage(imageId) == null) {
            symbolIconId = "track-direction-arrow"
        }

        // Unify marker behavior for all trackers: use one symbol pipeline and prefer streamed accuracy
        // while still falling back to tracking-service accuracy when needed.
        val accuracyMeters = lastStreamedAccuracyMeters ?: trackingServiceAccuracyMeters
        val accuracyValue = (accuracyMeters?.takeIf { it > 0f } ?: 0f).toDouble()

        if (!showMyLocationEnabled) {
            LocationComponentHelper.setEnabled(map, false)
        }

        val point = Point.fromLngLat(toLatLng.longitude, toLatLng.latitude)
        val feature = Feature.fromGeometry(point)
        feature.addStringProperty("icon", symbolIconId)
        feature.addNumberProperty("rotate", toRotation)
        feature.addNumberProperty("accuracy", accuracyValue)
        source.setGeoJson(feature)

        if (accuracyValue > 0.0) {
            val circle = MapTrackGeometryRenderer.buildAccuracyPolygon(toLatLng, accuracyValue)
            if (circle != null) {
                val accuracyFeature = Feature.fromGeometry(circle)
                accuracySource?.setGeoJson(accuracyFeature)
            } else {
                accuracySource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            }
        } else {
            accuracySource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }
}
