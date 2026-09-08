package com.geovault.places.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.geovault.common.maps.geocoding.GeocodeSearchResult
import com.geovault.places.PlacesApplication
import com.geovault.places.di.PlacesAppServices
import com.geovault.places.domain.DeletePlaceOutcome
import com.geovault.places.domain.DeletePlaceUseCase
import com.geovault.places.domain.SavePlaceOutcome
import com.geovault.places.domain.SavePlaceUseCase
import com.geovault.places.model.PlaceKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaceEditArgs(
    val keyValue: String?,
)

sealed class PlaceEditEvent {
    data object Closed : PlaceEditEvent()
    data class Message(val text: String) : PlaceEditEvent()
}

class PlaceEditViewModel(
    application: Application,
    args: PlaceEditArgs,
    private val savePlace: SavePlaceUseCase,
    private val deletePlace: DeletePlaceUseCase,
    private val nowMillis: () -> Long = { System.nanoTime() / NANOSECONDS_PER_MILLISECOND },
) : AndroidViewModel(application) {
    private val store = (application as PlacesApplication).services.placesStore()

    private val requestedKey = args.keyValue?.let(::PlaceKey)
    private val _state = MutableStateFlow(
        PlaceEditUiState.from(
            place = requestedKey?.let(store::find),
            nowMillis = nowMillis(),
            keyOverride = requestedKey,
        )
    )
    val state: StateFlow<PlaceEditUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaceEditEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PlaceEditEvent> = _events.asSharedFlow()

    fun onNameChange(value: String) {
        _state.update { it.copy(name = value) }
    }

    fun onDescriptionChange(value: String) {
        _state.update { it.copy(description = value) }
    }

    fun onAddressChange(value: String) {
        _state.update { PlaceEditReducer.onAddressChange(it, value) }
    }

    fun restoreDraft(draft: PlaceEditFormDraft) {
        _state.update { PlaceEditReducer.restoreDraft(it, draft) }
    }

    fun onCoordinatesEdited(value: String) {
        _state.update { PlaceEditReducer.onCoordinatesEdited(it, value) }
    }

    fun parseCoordinatesFromInput() {
        _state.update { PlaceEditReducer.parseCoordinatesFromInput(it) }
    }

    fun setFromMapPoint(latitude: Double, longitude: Double): Boolean {
        val before = _state.value
        val next = PlaceEditReducer.setFromMapPoint(before, latitude, longitude, nowMillis())
        _state.value = next
        return next !== before
    }

    fun setFromDeviceLocation(latitude: Double, longitude: Double) {
        _state.update { PlaceEditReducer.setFromDeviceLocation(it, latitude, longitude) }
    }

    fun setFromSearchResult(result: GeocodeSearchResult) {
        _state.update { PlaceEditReducer.setFromSearchResult(it, result) }
    }

    fun markSelectionCameraFocusHandled() {
        _state.update { PlaceEditReducer.markSelectionCameraFocusHandled(it) }
    }

    fun setShowDiscardDialog(visible: Boolean) {
        _state.update { it.copy(showDiscardDialog = visible) }
    }

    fun setShowDeleteDialog(visible: Boolean) {
        _state.update { it.copy(showDeleteDialog = visible) }
    }

    fun save() {
        val current = _state.value
        if (current.saveInFlight) return
        val place = PlaceEditReducer.buildPlaceOrNull(current) ?: run {
            _state.update { it.copy(coordinatesError = "Invalid coordinates") }
            return
        }
        _state.update { it.copy(saveInFlight = true) }
        viewModelScope.launch {
            when (val outcome = savePlace.save(place, current.baseline)) {
                is SavePlaceOutcome.SavedOnline -> _events.emit(PlaceEditEvent.Closed)
                is SavePlaceOutcome.QueuedOffline -> {
                    _events.emit(PlaceEditEvent.Message(outcome.message))
                    _events.emit(PlaceEditEvent.Closed)
                }
                is SavePlaceOutcome.AuthRequired -> {
                    _state.update { it.copy(saveInFlight = false) }
                    _events.emit(PlaceEditEvent.Message(outcome.message))
                }
                is SavePlaceOutcome.FailedValidation -> {
                    _state.update { it.copy(saveInFlight = false) }
                    _events.emit(PlaceEditEvent.Message(outcome.message))
                }
            }
        }
    }

    fun deleteOrRevert() {
        val current = _state.value
        if (current.mode == PlaceEditMode.New) return
        val place = store.find(current.key) ?: PlaceEditReducer.buildPlaceOrNull(current) ?: return
        viewModelScope.launch {
            when (val outcome = deletePlace.deleteOrRevert(place)) {
                DeletePlaceOutcome.DeletedOnline,
                DeletePlaceOutcome.DiscardedLocal -> _events.emit(PlaceEditEvent.Closed)
                is DeletePlaceOutcome.Reverted -> {
                    _events.emit(PlaceEditEvent.Message(outcome.message))
                    _events.emit(PlaceEditEvent.Closed)
                }
                is DeletePlaceOutcome.QueuedOffline -> {
                    _events.emit(PlaceEditEvent.Message(outcome.message))
                    _events.emit(PlaceEditEvent.Closed)
                }
                is DeletePlaceOutcome.AuthRequired -> _events.emit(PlaceEditEvent.Message(outcome.message))
                is DeletePlaceOutcome.Failed -> _events.emit(PlaceEditEvent.Message(outcome.message))
            }
        }
    }

    companion object {
        private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L

        fun factory(services: PlacesAppServices, args: PlaceEditArgs): ViewModelProvider.Factory {
            return Factory(services, args)
        }
    }

    private class Factory(
        private val services: PlacesAppServices,
        private val args: PlaceEditArgs,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("PlaceEditViewModel.factory requires an Application")
            return modelClass.cast(
                PlaceEditViewModel(
                    application = application,
                    args = args,
                    savePlace = services.savePlaceUseCase(),
                    deletePlace = services.deletePlaceUseCase(),
                )
            ) ?: error("Unknown ViewModel class ${modelClass.name}")
        }
    }
}
