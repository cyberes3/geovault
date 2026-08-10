package com.geovault.places.presentation

import com.geovault.common.maps.core.geoVaultLatLngBoundsForPoints
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
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
            val lat = coords[1]
            val lon = coords[0]
            if (!isValidMapLibreGeographicLatLng(lat, lon)) return@mapNotNull null
            LatLng(lat, lon)
        }
        return geoVaultLatLngBoundsForPoints(points)
    }

    fun buildRenderState(
        features: List<Feature>,
        selectedId: Int?,
        selectedFeature: Feature? = null,
    ): MapRenderState {
        val points = features.mapIndexed { index, feature ->
            val coordinates = feature.geometry.coordinates
            val lat = coordinates.getOrNull(1) ?: 0.0
            val lon = coordinates.getOrNull(0) ?: 0.0
            val isSelected = isSameDisplayFeature(feature, selectedId, selectedFeature)
            MapRenderPoint(
                id = renderIdForFeature(index, feature),
                latitude = lat,
                longitude = lon,
                title = feature.properties.name,
                iconImageId = markerIconId(isSelected),
                iconSize = if (isSelected) 1.08f else 1f,
            )
        }
        return MapRenderState(points = points)
    }

    private fun isSameDisplayFeature(
        feature: Feature,
        selectedId: Int?,
        selectedFeature: Feature?,
    ): Boolean {
        val dbId = feature.properties.database_id
        if (selectedId != null && dbId == selectedId) return true
        val selected = selectedFeature ?: return false
        if (dbId != null && selected.properties.database_id == dbId) return true
        if (dbId != null || selected.properties.database_id != null) return false
        val coords = feature.geometry.coordinates
        val selectedCoords = selected.geometry.coordinates
        return coords.size >= 2 &&
            selectedCoords.size >= 2 &&
            coords[0] == selectedCoords[0] &&
            coords[1] == selectedCoords[1] &&
            feature.properties.name == selected.properties.name
    }

    private fun markerIconId(isSelected: Boolean): String =
        if (isSelected) CommonMapIconIds.MARKER_SELECTED else CommonMapIconIds.MARKER_DEFAULT

    fun renderIdForFeature(index: Int, feature: Feature): String =
        feature.properties.database_id?.toString()
            ?: "temp-$index-${feature.properties.name.orEmpty()}-${feature.geometry.coordinates.take(2)}"
}
