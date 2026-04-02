package com.geovault.places.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geovault.common.CoordinateParser
import com.geovault.places.model.AddressSearchResult
import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties

class PlaceEditScreenState(
    private val initial: Feature?,
    val isOfflineEdit: Boolean,
) {
    private enum class CameraMotionRequest {
        None,
        FocusSelection,
    }

    var name by mutableStateOf(initial?.properties?.name.orEmpty())
    var description by mutableStateOf(initial?.properties?.description.orEmpty())
    var coordinatesInput by mutableStateOf(
        initial?.properties?.address
            ?: initial?.geometry?.coordinates?.takeIf { it.size >= 2 }?.let {
                String.format("%.6f, %.6f", it[1], it[0])
            }.orEmpty(),
    )
    var selectedLat by mutableStateOf(initial?.geometry?.coordinates?.getOrNull(1))
    var selectedLon by mutableStateOf(initial?.geometry?.coordinates?.getOrNull(0))
    var selectedAddress by mutableStateOf(initial?.properties?.address)
    var showSelectedPointMarker by mutableStateOf(true)
    var mapSearchQuery by mutableStateOf("")
    var mapSearchResults by mutableStateOf<List<AddressSearchResult>>(emptyList())
    var isSearching by mutableStateOf(false)
    var coordinatesError by mutableStateOf<String?>(null)
    var showSearchPanel by mutableStateOf(false)
    var showDiscardDialog by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    private var pendingCameraMotion by mutableStateOf(
        if (initial != null) CameraMotionRequest.FocusSelection else CameraMotionRequest.None
    )

    private val initialName = initial?.properties?.name.orEmpty().trim()
    private val initialDescription = initial?.properties?.description.orEmpty().trim()
    private val initialCoordinates = (
        initial?.properties?.address
            ?: initial?.geometry?.coordinates?.takeIf { it.size >= 2 }?.let {
                String.format("%.6f, %.6f", it[1], it[0])
            }.orEmpty()
        ).trim()

    val title: String
        get() = when {
            initial == null -> "New Place"
            isOfflineEdit -> "Edit Place (Offline)"
            else -> "Edit Place"
        }

    val hasUnsavedChanges: Boolean
        get() = name.trim() != initialName ||
            description.trim() != initialDescription ||
            coordinatesInput.trim() != initialCoordinates

    fun setFromMapPoint(latitude: Double, longitude: Double) {
        selectedLat = latitude
        selectedLon = longitude
        selectedAddress = null
        coordinatesInput = String.format("%.6f, %.6f", latitude, longitude)
        coordinatesError = null
        showSelectedPointMarker = true
        pendingCameraMotion = CameraMotionRequest.None
    }

    fun setFromGpsLocation(latitude: Double, longitude: Double) {
        selectedLat = latitude
        selectedLon = longitude
        selectedAddress = null
        coordinatesInput = String.format("%.6f, %.6f", latitude, longitude)
        coordinatesError = null
        // Avoid drawing the edit-point marker on top of the location puck.
        showSelectedPointMarker = false
        pendingCameraMotion = CameraMotionRequest.FocusSelection
    }

    fun setFromSearchResult(result: AddressSearchResult) {
        val coords = result.coordinates ?: return
        if (coords.size < 2) return
        selectedLon = coords[0]
        selectedLat = coords[1]
        selectedAddress = result.place_name ?: result.text
        coordinatesInput = selectedAddress ?: String.format("%.6f, %.6f", coords[1], coords[0])
        mapSearchResults = emptyList()
        mapSearchQuery = ""
        showSearchPanel = false
        coordinatesError = null
        showSelectedPointMarker = true
        pendingCameraMotion = CameraMotionRequest.FocusSelection
    }

    fun clearMapSearch() {
        mapSearchQuery = ""
        mapSearchResults = emptyList()
        isSearching = false
    }

    fun onCoordinatesEdited(value: String) {
        coordinatesInput = value
        coordinatesError = null
        if (value.isBlank()) {
            selectedLat = null
            selectedLon = null
            selectedAddress = null
        }
    }

    fun parseCoordinatesFromInput(): Boolean {
        val parsed = CoordinateParser.parse(coordinatesInput.trim())
        if (parsed != null) {
            selectedLat = parsed.first
            selectedLon = parsed.second
            selectedAddress = null
            coordinatesInput = String.format("%.6f, %.6f", parsed.first, parsed.second)
            coordinatesError = null
            showSelectedPointMarker = true
            pendingCameraMotion = CameraMotionRequest.FocusSelection
            return true
        }
        coordinatesError = "Invalid coordinate format"
        return false
    }

    fun buildFeatureOrNull(): Feature? {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return null
        val parsed = if (selectedLat != null && selectedLon != null) null else CoordinateParser.parse(coordinatesInput.trim())
        val finalLat = selectedLat ?: parsed?.first
        val finalLon = selectedLon ?: parsed?.second
        if (finalLat == null || finalLon == null) {
            coordinatesError = "Invalid coordinates"
            return null
        }
        return Feature(
            geometry = Geometry(coordinates = listOf(finalLon, finalLat)),
            properties = Properties(
                database_id = initial?.properties?.database_id,
                name = normalizedName,
                description = description.trim(),
                created_at = initial?.properties?.created_at,
                address = selectedAddress,
            ),
        )
    }

    fun deleteActionLabel(): String {
        val feature = initial ?: return "Delete"
        return if (isOfflineEdit) {
            if (feature.properties.database_id != null) "Revert" else "Discard"
        } else {
            "Delete"
        }
    }

    fun shouldFocusCameraOnSelection(): Boolean {
        return pendingCameraMotion == CameraMotionRequest.FocusSelection
    }

    fun markSelectionCameraFocusHandled() {
        pendingCameraMotion = CameraMotionRequest.None
    }
}
