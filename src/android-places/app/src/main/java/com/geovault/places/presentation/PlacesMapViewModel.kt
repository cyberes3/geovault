package com.geovault.places.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.model.Feature
import com.geovault.places.model.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds

data class PlacesMapState(
    val features: List<Feature> = emptyList(),
    val selectedFeature: Feature? = null,
)

class PlacesMapViewModel(application: Application) : AndroidViewModel(application) {
    private val placesStore = PlacesAppServices.from(application).placesStore()
    private val _state = MutableStateFlow(PlacesMapState())
    val state: StateFlow<PlacesMapState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            placesStore.snapshot.collect { snap ->
                val nextFeatures = snap.displayFeatures
                val nextSelected = PlacesMapStateTransforms.reconcileSelectedFeature(
                    features = nextFeatures,
                    selectedFeature = _state.value.selectedFeature,
                )
                _state.value = _state.value.copy(
                    features = nextFeatures,
                    selectedFeature = nextSelected,
                )
            }
        }
    }

    fun selectByDatabaseId(id: Int?) {
        if (id == null || id < 0) return
        val selected = _state.value.features.firstOrNull { it.properties.database_id == id }
        _state.value = _state.value.copy(selectedFeature = selected)
    }

    fun selectByRenderId(renderId: String): Boolean {
        val selected = _state.value.features
            .withIndex()
            .firstOrNull { (index, feature) ->
                PlacesMapStateTransforms.renderIdForFeature(index, feature) == renderId
            }
            ?.value
        _state.value = _state.value.copy(selectedFeature = selected)
        return selected != null
    }

    fun setSelectedFeature(feature: Feature?) {
        _state.value = _state.value.copy(selectedFeature = feature)
    }

    fun featureBounds(): LatLngBounds? {
        return PlacesMapStateTransforms.featureBounds(_state.value.features)
    }

    fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState {
        return PlacesMapStateTransforms.buildRenderState(
            features = _state.value.features,
            selectedId = _state.value.selectedFeature?.properties?.database_id,
            selectedFeature = _state.value.selectedFeature,
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
