package com.geovault.tracker.fragments.map

import android.util.Log
import org.maplibre.geojson.Feature
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection

internal object MapLayerVisibility {
    fun setAnnotationLayersVisibility(style: Style, visible: Boolean) {
        val visibility = if (visible) Property.VISIBLE else Property.NONE
        style.getLayer(MapConstants.TRACK_OUTER_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(MapConstants.TRACK_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(MapConstants.TRACK_FILL_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(MapConstants.TRACK_POSITION_ACCURACY_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(MapConstants.TRACK_POSITION_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.layers.forEach { layer ->
            val id = layer.id
            if (id.startsWith("mapbox-android-") || id.startsWith("org.maplibre.annotations")) {
                layer.setProperties(PropertyFactory.visibility(visibility))
            }
        }
    }

    fun setAllTrackLayersVisibility(style: Style, visible: Boolean) {
        val visibility = if (visible) Property.VISIBLE else Property.NONE
        style.getLayer(MapConstants.ALL_TRACKS_OUTER_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(MapConstants.ALL_TRACKS_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(MapConstants.ALL_TRACKS_FILL_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
        style.getLayer(MapConstants.ALL_TRACKS_POINTS_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
    }

    fun clearAllTrackSources(style: Style) {
        style.getSourceAs<GeoJsonSource>(MapConstants.ALL_TRACKS_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        style.getSourceAs<GeoJsonSource>(MapConstants.ALL_TRACKS_POINTS_SOURCE_ID)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    fun updateAllTrackSources(
        style: Style,
        lineFeatures: List<Feature>,
        pointFeatures: List<Feature>,
        logTag: String
    ): Boolean {
        val lineSource = style.getSourceAs<GeoJsonSource>(MapConstants.ALL_TRACKS_SOURCE_ID)
        val pointSource = style.getSourceAs<GeoJsonSource>(MapConstants.ALL_TRACKS_POINTS_SOURCE_ID)
        if (lineSource == null || pointSource == null) {
            Log.e(
                logTag,
                "all-trackers source missing: lineSource=${lineSource != null}, pointSource=${pointSource != null}"
            )
            return false
        }
        lineSource.setGeoJson(FeatureCollection.fromFeatures(lineFeatures))
        pointSource.setGeoJson(FeatureCollection.fromFeatures(pointFeatures))
        return true
    }
}
