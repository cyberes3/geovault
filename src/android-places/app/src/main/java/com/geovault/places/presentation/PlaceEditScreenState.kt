package com.geovault.places.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.geovault.common.geo.CoordinateParser
import com.geovault.common.maps.geocoding.GeocodeSearchResult
import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties

class PlaceEditScreenState(
    private val initial: Feature?,
    val isOfflineEdit: Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / NANOSECONDS_PER_MILLISECOND },
    private val initialMapTapSuppressionMillis: Long = INITIAL_MAP_TAP_SUPPRESSION_MILLIS,
) {
    private enum class CameraMotionRequest {
        None,
        FocusSelection,
    }

    var name by mutableStateOf(initial?.properties?.name.orEmpty())
    var description by mutableStateOf(initial?.properties?.description.orEmpty())
    var coordinatesInput by mutableStateOf(coordinatesFromGeometry(initial))
    var selectedLat by mutableStateOf(initial?.geometry?.coordinates?.getOrNull(1))
    var selectedLon by mutableStateOf(initial?.geometry?.coordinates?.getOrNull(0))
    var selectedAddress by mutableStateOf(initial?.properties?.address)
    var showSelectedPointMarker by mutableStateOf(true)
    var coordinatesError by mutableStateOf<String?>(null)
    var showDiscardDialog by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    private var pendingCameraMotion by mutableStateOf(
        if (initial != null) CameraMotionRequest.FocusSelection else CameraMotionRequest.None
    )
    private val createdAtMillis = nowMillis()

    private val initialName = initial?.properties?.name.orEmpty().trim()
    private val initialDescription = initial?.properties?.description.orEmpty().trim()
    private val initialCoordinates = coordinatesFromGeometry(initial).trim()
    private val initialAddress = initial?.properties?.address.orEmpty().trim()

    val title: String
        get() = when {
            initial == null -> "New Place"
            isOfflineEdit -> "Edit Place (Offline)"
            else -> "Edit Place"
        }

    val hasUnsavedChanges: Boolean
        get() = name.trim() != initialName ||
            description.trim() != initialDescription ||
            coordinatesInput.trim() != initialCoordinates ||
            selectedAddress.orEmpty().trim() != initialAddress

    fun setFromMapPoint(latitude: Double, longitude: Double): Boolean {
        if (shouldSuppressInitialMapTap()) return false
        selectedLat = latitude
        selectedLon = longitude
        selectedAddress = null
        coordinatesInput = CoordinateParser.formatLatLon(latitude, longitude)
        coordinatesError = null
        showSelectedPointMarker = true
        pendingCameraMotion = CameraMotionRequest.None
        return true
    }

    /** Sets coordinates from a device location fix: show the edit marker and pan/zoom the map to it. */
    fun setFromDeviceLocation(latitude: Double, longitude: Double) {
        selectedLat = latitude
        selectedLon = longitude
        selectedAddress = null
        coordinatesInput = CoordinateParser.formatLatLon(latitude, longitude)
        coordinatesError = null
        showSelectedPointMarker = true
        pendingCameraMotion = CameraMotionRequest.FocusSelection
    }

    fun setFromSearchResult(result: GeocodeSearchResult) {
        val coords = result.coordinates ?: return
        if (coords.size < 2) return
        selectedLon = coords[0]
        selectedLat = coords[1]
        selectedAddress = result.place_name ?: result.text
        coordinatesInput = CoordinateParser.formatLatLon(coords[1], coords[0])
        coordinatesError = null
        showSelectedPointMarker = true
        pendingCameraMotion = CameraMotionRequest.FocusSelection
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
            selectedLat = parsed.latitude
            selectedLon = parsed.longitude
            selectedAddress = null
            coordinatesInput = CoordinateParser.formatLatLon(parsed)
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
        val parsed = CoordinateParser.parse(coordinatesInput.trim())
        if (parsed == null) {
            coordinatesError = "Invalid coordinates"
            return null
        }
        selectedLat = parsed.latitude
        selectedLon = parsed.longitude
        return Feature(
            geometry = Geometry(coordinates = listOf(parsed.longitude, parsed.latitude)),
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
        val feature = initial
            ?: return PlacesOfflineBehaviorPolicy.destructiveActionLabel(PlacesOfflineDestructiveAction.Delete)
        return if (isOfflineEdit) {
            if (feature.properties.database_id != null) {
                PlacesOfflineBehaviorPolicy.destructiveActionLabel(PlacesOfflineDestructiveAction.Revert)
            } else {
                PlacesOfflineBehaviorPolicy.destructiveActionLabel(PlacesOfflineDestructiveAction.Discard)
            }
        } else {
            PlacesOfflineBehaviorPolicy.destructiveActionLabel(PlacesOfflineDestructiveAction.Delete)
        }
    }

    fun shouldFocusCameraOnSelection(): Boolean {
        return pendingCameraMotion == CameraMotionRequest.FocusSelection
    }

    fun markSelectionCameraFocusHandled() {
        pendingCameraMotion = CameraMotionRequest.None
    }

    private fun shouldSuppressInitialMapTap(): Boolean {
        if (initial != null || initialMapTapSuppressionMillis <= 0) return false
        if (coordinatesInput.isNotBlank() || selectedLat != null || selectedLon != null) return false
        return nowMillis() - createdAtMillis < initialMapTapSuppressionMillis
    }

    companion object {
        private const val INITIAL_MAP_TAP_SUPPRESSION_MILLIS = 350L
        private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L

        private fun coordinatesFromGeometry(feature: Feature?): String {
            val coords = feature?.geometry?.coordinates?.takeIf { it.size >= 2 } ?: return ""
            return CoordinateParser.formatLatLon(coords[1], coords[0])
        }
    }
}
