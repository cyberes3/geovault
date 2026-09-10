package com.geovault.places.presentation

import com.geovault.common.maps.core.geoVaultLatLngBoundsForPoints
import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.common.maps.render.MapRenderPoint
import com.geovault.common.maps.render.MapRenderState
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceKey
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

object PlacesMapStateTransforms {
    fun reconcileSelectedKey(places: List<Place>, selectedKey: PlaceKey?): PlaceKey? {
        val key = selectedKey ?: return null
        return if (places.any { it.key == key }) key else null
    }

    fun featureBounds(places: List<Place>): LatLngBounds? {
        val points = places.mapNotNull { place ->
            val location = place.content.location
            if (!location.isValidGeographic()) return@mapNotNull null
            LatLng(location.latitude, location.longitude)
        }
        return geoVaultLatLngBoundsForPoints(points)
    }

    fun buildRenderState(places: List<Place>): MapRenderState {
        val points = places.mapNotNull { place ->
            val location = place.content.location
            if (!location.isValidGeographic()) return@mapNotNull null
            MapRenderPoint(
                id = place.key.value,
                latitude = location.latitude,
                longitude = location.longitude,
                title = place.content.name,
                iconImageId = CommonMapIconIds.MARKER_DEFAULT,
                iconSize = 1f,
            )
        }
        return MapRenderState(points = points)
    }
}
