package com.geovault.places.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.geovault.common.maps.render.MapRenderState
import com.geovault.places.PlacesApplication
import com.geovault.places.data.PlacesStore
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.domain.PlacesListProjection
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.maplibre.android.geometry.LatLngBounds

class PlacesMapViewModel(
    application: Application,
    placesStore: PlacesStore,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        (application as PlacesApplication).services,
    )

    constructor(application: Application, services: PlacesAppServices) : this(
        application,
        services.placesStore(),
    )

    val places: StateFlow<List<Place>> = placesStore.document
        .map { PlacesListProjection.exportable(it.toDomain()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var lastAppliedCameraRequestToken: Long? = null

    fun shouldApplyInitialCamera(requestToken: Long): Boolean {
        return lastAppliedCameraRequestToken != requestToken
    }

    fun markInitialCameraApplied(requestToken: Long) {
        lastAppliedCameraRequestToken = requestToken
    }

    fun featureBounds(): LatLngBounds? {
        return PlacesMapStateTransforms.featureBounds(places.value)
    }

    fun buildMapRenderState(selectedKey: PlaceKey?): MapRenderState {
        return PlacesMapStateTransforms.buildRenderState(places.value, selectedKey)
    }

    fun selectedPlaceLabel(place: Place?): String {
        return place?.content?.name?.takeIf { it.isNotBlank() } ?: "Select a place"
    }

    companion object {
        fun factory(services: PlacesAppServices): ViewModelProvider.Factory = Factory(services)
    }

    private class Factory(
        private val services: PlacesAppServices,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("PlacesMapViewModel.factory requires an Application")
            return modelClass.cast(PlacesMapViewModel(application, services))
                ?: error("Unknown ViewModel class ${modelClass.name}")
        }
    }
}
