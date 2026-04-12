package com.geovault.places.presentation

import com.geovault.common.maps.core.geoVaultLatLngBoundsForPoints
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.places.model.Feature
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

object PlacesMapStateTransforms {
    fun reconcileSelectedFeature(features: List<Feature>, selectedFeature: Feature?): Feature? {
        val current = selectedFeature ?: return null
        val selectedId = current.properties.database_id
        if (selectedId != null) {
            return features.firstOrNull { it.properties.database_id == selectedId }
        }
        val currentCoords = current.geometry.coordinates
        if (currentCoords.size < 2) return null
        return features.firstOrNull { candidate ->
            val coords = candidate.geometry.coordinates
            coords.size >= 2 &&
                coords[0] == currentCoords[0] &&
                coords[1] == currentCoords[1] &&
                candidate.properties.name == current.properties.name
        }
    }

    fun featureBounds(features: List<Feature>): LatLngBounds? {
        val points = features.mapNotNull { feature ->
            val coords = feature.geometry.coordinates
            if (coords.size < 2) return@mapNotNull null
            LatLng(coords[1], coords[0])
        }
        return geoVaultLatLngBoundsForPoints(points)
    }

    fun buildRenderState(
        features: List<Feature>,
        selectedId: Int?,
    ): MapRenderState {
        val points = features.mapIndexed { index, feature ->
            val coordinates = feature.geometry.coordinates
            val lat = coordinates.getOrNull(1) ?: 0.0
            val lon = coordinates.getOrNull(0) ?: 0.0
            val dbId = feature.properties.database_id
            val isSelected = selectedId != null && dbId == selectedId
            MapRenderPoint(
                id = dbId?.toString() ?: "temp-$index-${feature.properties.name.orEmpty()}",
                latitude = lat,
                longitude = lon,
                title = feature.properties.name,
                iconImageId = if (isSelected) CommonMapIconIds.MARKER_SELECTED else CommonMapIconIds.MARKER_DEFAULT,
                iconSize = if (isSelected) 1.08f else 1f,
            )
        }
        return MapRenderState(points = points)
    }
}
