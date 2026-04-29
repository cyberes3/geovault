package com.geovault.places.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.geovault.common.maps.core.isValidMapLibreGeographicLatLng
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import com.geovault.places.model.Properties
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

data class PlacesMapState(
    val features: List<Feature> = emptyList(),
    val selectedFeature: Feature? = null,
)

class PlacesMapViewModel(application: Application) : AndroidViewModel(application) {
    private val cache = PlacesAppServices.from(application).cacheStore()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(PlacesMapState())
    val state: kotlinx.coroutines.flow.StateFlow<PlacesMapState> = _state

    fun loadFromCache() {
        val nextFeatures = cache.getDisplayFeatures()
        val nextSelected = PlacesMapStateTransforms.reconcileSelectedFeature(
            features = nextFeatures,
            selectedFeature = _state.value.selectedFeature,
        )
        _state.value = _state.value.copy(
            features = nextFeatures,
            selectedFeature = nextSelected,
        )
    }

    fun selectByDatabaseId(id: Int?) {
        if (id == null || id < 0) return
        val selected = _state.value.features.firstOrNull { it.properties.database_id == id }
        _state.value = _state.value.copy(selectedFeature = selected)
    }

    fun setSelectedFeature(feature: Feature?) {
        _state.value = _state.value.copy(selectedFeature = feature)
    }

    fun findFeaturesNearTap(
        tapLatLng: LatLng,
        projection: org.maplibre.android.maps.Projection,
        hitRadiusPx: Float,
    ): List<Feature> {
        val tapPoint = projection.toScreenLocation(tapLatLng)
        val tapX = tapPoint.x
        val tapY = tapPoint.y
        return _state.value.features.filter { feature ->
            val coords = feature.geometry.coordinates
            if (coords.size < 2) return@filter false
            val lon = coords[0]
            val lat = coords[1]
            if (!isValidMapLibreGeographicLatLng(lat, lon)) return@filter false
            val point = projection.toScreenLocation(LatLng(lat, lon))
            val dx = point.x - tapX
            val dy = point.y - tapY
            (dx * dx + dy * dy) <= hitRadiusPx * hitRadiusPx
        }
    }

    fun featureBounds(): LatLngBounds? {
        return PlacesMapStateTransforms.featureBounds(_state.value.features)
    }

    fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState {
        return PlacesMapStateTransforms.buildRenderState(
            features = _state.value.features,
            selectedId = _state.value.selectedFeature?.properties?.database_id,
        )
    }

    fun selectedFeatureLabel(properties: Properties?): String {
        return properties?.name?.takeIf { it.isNotBlank() } ?: "Select a place"
    }

    private var lastAppliedCameraRequestToken: Long? = null

    fun shouldApplyInitialCamera(requestToken: Long): Boolean {
        return lastAppliedCameraRequestToken != requestToken
    }

    fun markInitialCameraApplied(requestToken: Long) {
        lastAppliedCameraRequestToken = requestToken
    }
}
