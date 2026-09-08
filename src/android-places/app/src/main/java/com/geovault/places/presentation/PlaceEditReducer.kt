package com.geovault.places.presentation

import com.geovault.common.geo.CoordinateParser
import com.geovault.common.geo.LonLat
import com.geovault.common.maps.geocoding.GeocodeSearchResult
import com.geovault.places.model.PendingChange
import com.geovault.places.model.Place
import com.geovault.places.model.PlaceContent
import com.geovault.places.model.PlaceKey

enum class PlaceEditMode {
    New,
    EditSynced,
    EditPendingUpdate,
    EditPendingCreate,
}

enum class PlaceEditCameraMotion {
    None,
    FocusSelection,
}

data class PlaceEditUiState(
    val mode: PlaceEditMode,
    val key: PlaceKey,
    val serverId: Int?,
    val createdAt: String?,
    val baseline: PlaceContent?,
    val name: String,
    val description: String,
    val coordinatesInput: String,
    val selectedLat: Double?,
    val selectedLon: Double?,
    val selectedAddress: String?,
    val coordinatesError: String? = null,
    val showDiscardDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showSelectedPointMarker: Boolean = true,
    val saveInFlight: Boolean = false,
    val pendingCameraMotion: PlaceEditCameraMotion = PlaceEditCameraMotion.None,
    val initialName: String,
    val initialDescription: String,
    val initialCoordinates: String,
    val initialAddress: String,
    val createdAtMillis: Long,
    val initialMapTapSuppressionMillis: Long = INITIAL_MAP_TAP_SUPPRESSION_MILLIS,
) {
    val title: String
        get() = when (mode) {
            PlaceEditMode.New -> "New Place"
            PlaceEditMode.EditPendingCreate,
            PlaceEditMode.EditPendingUpdate -> "Edit Place (Offline)"
            PlaceEditMode.EditSynced -> "Edit Place"
        }

    val hasUnsavedChanges: Boolean
        get() = name.trim() != initialName ||
            description.trim() != initialDescription ||
            coordinatesInput.trim() != initialCoordinates ||
            selectedAddress.orEmpty().trim() != initialAddress

    val isSaveEnabled: Boolean
        get() = name.trim().isNotEmpty() &&
            CoordinateParser.parse(coordinatesInput.trim()) != null &&
            !saveInFlight

    val deleteAction: PlacesOfflineDestructiveAction
        get() = when (mode) {
            PlaceEditMode.EditPendingUpdate -> PlacesOfflineDestructiveAction.Revert
            PlaceEditMode.EditPendingCreate -> PlacesOfflineDestructiveAction.Discard
            PlaceEditMode.New,
            PlaceEditMode.EditSynced -> PlacesOfflineDestructiveAction.Delete
        }

    companion object {
        const val INITIAL_MAP_TAP_SUPPRESSION_MILLIS = 350L

        fun from(place: Place?, nowMillis: Long, keyOverride: PlaceKey? = null): PlaceEditUiState {
            val key = place?.key ?: keyOverride ?: PlaceKey.local()
            val mode = when {
                place == null -> PlaceEditMode.New
                place.pending is PendingChange.Create -> PlaceEditMode.EditPendingCreate
                place.pending is PendingChange.Update -> PlaceEditMode.EditPendingUpdate
                else -> PlaceEditMode.EditSynced
            }
            val coords = place?.content?.location?.let {
                CoordinateParser.formatLatLon(it.latitude, it.longitude)
            }.orEmpty()
            return PlaceEditUiState(
                mode = mode,
                key = key,
                serverId = place?.serverId,
                createdAt = place?.createdAt,
                baseline = (place?.pending as? PendingChange.Update)?.baseline ?: place?.content,
                name = place?.content?.name.orEmpty(),
                description = place?.content?.description.orEmpty(),
                coordinatesInput = coords,
                selectedLat = place?.content?.location?.latitude,
                selectedLon = place?.content?.location?.longitude,
                selectedAddress = place?.content?.address,
                pendingCameraMotion = if (place != null) {
                    PlaceEditCameraMotion.FocusSelection
                } else {
                    PlaceEditCameraMotion.None
                },
                initialName = place?.content?.name.orEmpty().trim(),
                initialDescription = place?.content?.description.orEmpty().trim(),
                initialCoordinates = coords.trim(),
                initialAddress = place?.content?.address.orEmpty().trim(),
                createdAtMillis = nowMillis,
            )
        }
    }
}

data class PlaceEditFormDraft(
    val name: String,
    val description: String,
    val coordinatesInput: String,
    val address: String?,
    val selectedLat: Double?,
    val selectedLon: Double?,
) {
    companion object {
        fun from(state: PlaceEditUiState): PlaceEditFormDraft {
            return PlaceEditFormDraft(
                name = state.name,
                description = state.description,
                coordinatesInput = state.coordinatesInput,
                address = state.selectedAddress,
                selectedLat = state.selectedLat,
                selectedLon = state.selectedLon,
            )
        }
    }
}

object PlaceEditReducer {
    fun setFromMapPoint(state: PlaceEditUiState, latitude: Double, longitude: Double, nowMillis: Long): PlaceEditUiState {
        if (shouldSuppressInitialMapTap(state, nowMillis)) return state
        return state.copy(
            selectedLat = latitude,
            selectedLon = longitude,
            selectedAddress = null,
            coordinatesInput = CoordinateParser.formatLatLon(latitude, longitude),
            coordinatesError = null,
            showSelectedPointMarker = true,
            pendingCameraMotion = PlaceEditCameraMotion.None,
        )
    }

    fun setFromDeviceLocation(state: PlaceEditUiState, latitude: Double, longitude: Double): PlaceEditUiState {
        return state.copy(
            selectedLat = latitude,
            selectedLon = longitude,
            selectedAddress = null,
            coordinatesInput = CoordinateParser.formatLatLon(latitude, longitude),
            coordinatesError = null,
            showSelectedPointMarker = true,
            pendingCameraMotion = PlaceEditCameraMotion.FocusSelection,
        )
    }

    fun setFromSearchResult(state: PlaceEditUiState, result: GeocodeSearchResult): PlaceEditUiState {
        val coords = result.coordinates ?: return state
        if (coords.size < 2) return state
        return state.copy(
            selectedLon = coords[0],
            selectedLat = coords[1],
            selectedAddress = result.place_name ?: result.text,
            coordinatesInput = CoordinateParser.formatLatLon(coords[1], coords[0]),
            coordinatesError = null,
            showSelectedPointMarker = true,
            pendingCameraMotion = PlaceEditCameraMotion.FocusSelection,
        )
    }

    fun onAddressChange(state: PlaceEditUiState, value: String): PlaceEditUiState {
        return state.copy(selectedAddress = value.ifBlank { null })
    }

    fun restoreDraft(state: PlaceEditUiState, draft: PlaceEditFormDraft): PlaceEditUiState {
        return state.copy(
            name = draft.name,
            description = draft.description,
            coordinatesInput = draft.coordinatesInput,
            selectedAddress = draft.address,
            selectedLat = draft.selectedLat,
            selectedLon = draft.selectedLon,
        )
    }

    fun onCoordinatesEdited(state: PlaceEditUiState, value: String): PlaceEditUiState {
        val cleared = value.isBlank()
        return state.copy(
            coordinatesInput = value,
            coordinatesError = null,
            selectedLat = if (cleared) null else state.selectedLat,
            selectedLon = if (cleared) null else state.selectedLon,
            selectedAddress = if (cleared) null else state.selectedAddress,
        )
    }

    fun parseCoordinatesFromInput(state: PlaceEditUiState): PlaceEditUiState {
        val parsed = CoordinateParser.parse(state.coordinatesInput.trim())
        return if (parsed != null) {
            state.copy(
                selectedLat = parsed.latitude,
                selectedLon = parsed.longitude,
                selectedAddress = null,
                coordinatesInput = CoordinateParser.formatLatLon(parsed),
                coordinatesError = null,
                showSelectedPointMarker = true,
                pendingCameraMotion = PlaceEditCameraMotion.FocusSelection,
            )
        } else {
            state.copy(coordinatesError = "Invalid coordinate format")
        }
    }

    fun buildPlaceOrNull(state: PlaceEditUiState): Place? {
        val normalizedName = state.name.trim()
        if (normalizedName.isEmpty()) return null
        val parsed = CoordinateParser.parse(state.coordinatesInput.trim()) ?: return null
        val location = LonLat(longitude = parsed.longitude, latitude = parsed.latitude)
        if (!location.isValidGeographic()) return null
        val pending = when (state.mode) {
            PlaceEditMode.New,
            PlaceEditMode.EditPendingCreate -> PendingChange.Create
            PlaceEditMode.EditPendingUpdate -> PendingChange.Update(state.baseline ?: return null)
            PlaceEditMode.EditSynced -> null
        }
        return Place(
            key = state.key,
            serverId = state.serverId,
            content = PlaceContent(
                name = normalizedName,
                description = state.description.trim(),
                location = location,
                address = state.selectedAddress,
            ),
            createdAt = state.createdAt,
            pending = pending,
        )
    }

    fun markSelectionCameraFocusHandled(state: PlaceEditUiState): PlaceEditUiState {
        return state.copy(pendingCameraMotion = PlaceEditCameraMotion.None)
    }

    private fun shouldSuppressInitialMapTap(state: PlaceEditUiState, nowMillis: Long): Boolean {
        if (state.mode != PlaceEditMode.New || state.initialMapTapSuppressionMillis <= 0) return false
        if (state.coordinatesInput.isNotBlank() || state.selectedLat != null || state.selectedLon != null) {
            return false
        }
        return nowMillis - state.createdAtMillis < state.initialMapTapSuppressionMillis
    }
}
