package com.geovault.places.presentation

import com.geovault.common.maps.geocoding.GeocodeSearchResult
import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.samplePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceEditReducerTest {
    @Test
    fun newPlaceInitializesEmptyCoordinates() {
        val state = PlaceEditUiState.from(place = null, nowMillis = 1_000L)

        assertEquals("", state.coordinatesInput)
        assertNull(state.selectedLat)
        assertNull(state.selectedLon)
        assertFalse(state.hasUnsavedChanges)
        assertEquals(PlaceEditMode.New, state.mode)
    }

    @Test
    fun setFromMapPointUpdatesCoordinatesAndClearsError() {
        val start = PlaceEditUiState.from(place = null, nowMillis = 1_000L, keyOverride = PlaceKey.local("n"))
            .copy(coordinatesError = "Invalid", initialMapTapSuppressionMillis = 0L)

        val next = PlaceEditReducer.setFromMapPoint(start, latitude = 10.0, longitude = 20.0, nowMillis = 1_000L)

        assertEquals(10.0, next.selectedLat!!, 0.0)
        assertEquals(20.0, next.selectedLon!!, 0.0)
        assertEquals("10.000000, 20.000000", next.coordinatesInput)
        assertNull(next.coordinatesError)
        assertNull(next.selectedAddress)
        assertTrue(next.showSelectedPointMarker)
        assertEquals(PlaceEditCameraMotion.None, next.pendingCameraMotion)
    }

    @Test
    fun setFromMapPointIgnoresInitialTapForNewPlaceDuringStartupWindow() {
        val start = PlaceEditUiState.from(place = null, nowMillis = 1_000L)

        val ignored = PlaceEditReducer.setFromMapPoint(start, 10.0, 20.0, nowMillis = 1_000L)
        assertEquals(start, ignored)

        val accepted = PlaceEditReducer.setFromMapPoint(start, 10.0, 20.0, nowMillis = 1_350L)
        assertEquals(10.0, accepted.selectedLat!!, 0.0)
    }

    @Test
    fun setFromMapPointAcceptsInitialTapForExistingPlace() {
        val start = PlaceEditUiState.from(samplePlace(), nowMillis = 1_000L)

        val next = PlaceEditReducer.setFromMapPoint(start, 12.0, 22.0, nowMillis = 1_000L)

        assertEquals(12.0, next.selectedLat!!, 0.0)
        assertEquals(22.0, next.selectedLon!!, 0.0)
    }

    @Test
    fun setFromDeviceLocationRequestsCameraFocusAndClearsAddress() {
        val start = PlaceEditUiState.from(place = null, nowMillis = 1L).copy(selectedAddress = "Keep?")

        val next = PlaceEditReducer.setFromDeviceLocation(start, 10.0, 20.0)

        assertEquals("10.000000, 20.000000", next.coordinatesInput)
        assertNull(next.selectedAddress)
        assertEquals(PlaceEditCameraMotion.FocusSelection, next.pendingCameraMotion)
        val handled = PlaceEditReducer.markSelectionCameraFocusHandled(next)
        assertEquals(PlaceEditCameraMotion.None, handled.pendingCameraMotion)
    }

    @Test
    fun setFromSearchResultUpdatesMarkerAndAddress() {
        val start = PlaceEditUiState.from(place = null, nowMillis = 1L)

        val next = PlaceEditReducer.setFromSearchResult(
            start,
            GeocodeSearchResult(
                coordinates = listOf(20.0, 10.0),
                place_name = "Sample Place",
                text = "Sample",
            ),
        )

        assertEquals(10.0, next.selectedLat!!, 0.0)
        assertEquals(20.0, next.selectedLon!!, 0.0)
        assertEquals("Sample Place", next.selectedAddress)
        assertEquals("10.000000, 20.000000", next.coordinatesInput)
        assertEquals(PlaceEditCameraMotion.FocusSelection, next.pendingCameraMotion)
    }

    @Test
    fun buildPlaceOrNullReturnsNullForInvalidCoordinates() {
        val start = PlaceEditUiState.from(place = null, nowMillis = 1L).copy(name = "Point", coordinatesInput = "bad")

        assertNull(PlaceEditReducer.buildPlaceOrNull(start))
        assertFalse(start.isSaveEnabled)
    }

    @Test
    fun buildPlaceOrNullUsesTypedCoordinates() {
        val start = PlaceEditUiState.from(samplePlace(), nowMillis = 1L)
        val edited = PlaceEditReducer.onCoordinatesEdited(start, "11.000000, 21.000000")
        val parsed = PlaceEditReducer.parseCoordinatesFromInput(edited)
        val built = PlaceEditReducer.buildPlaceOrNull(parsed.copy(name = "Updated Place"))

        assertNotNull(built)
        assertEquals(21.0, built!!.content.location.longitude, 0.0)
        assertEquals(11.0, built.content.location.latitude, 0.0)
        assertEquals(1, built.serverId)
    }

    @Test
    fun buildPlaceOrNullForNewPlaceHasNoServerId() {
        val start = PlaceEditUiState.from(place = null, nowMillis = 1L, keyOverride = PlaceKey.local("n"))
            .copy(name = "New Camp", description = "Notes", initialMapTapSuppressionMillis = 0L)
        val withCoords = PlaceEditReducer.setFromMapPoint(start, 10.0, 20.0, nowMillis = 2L)
        val built = PlaceEditReducer.buildPlaceOrNull(withCoords)

        assertNotNull(built)
        assertNull(built!!.serverId)
        assertEquals(PendingChange.Create, built.pending)
        assertEquals("New Camp", built.content.name)
    }

    @Test
    fun deleteActionMatchesPendingMode() {
        val synced = PlaceEditUiState.from(samplePlace(), nowMillis = 1L)
        val pendingUpdate = PlaceEditUiState.from(
            samplePlace(pending = PendingChange.Update(samplePlace().content)),
            nowMillis = 1L,
        )
        val pendingCreate = PlaceEditUiState.from(
            samplePlace(key = PlaceKey.local("n"), serverId = null, pending = PendingChange.Create),
            nowMillis = 1L,
        )

        assertEquals(PlacesOfflineDestructiveAction.Delete, synced.deleteAction)
        assertEquals(PlacesOfflineDestructiveAction.Revert, pendingUpdate.deleteAction)
        assertEquals(PlacesOfflineDestructiveAction.Discard, pendingCreate.deleteAction)
    }

    @Test
    fun coordinatesSeedFromGeometryNotAddress() {
        val state = PlaceEditUiState.from(
            samplePlace(name = "Initial Place", address = "Some Street Address"),
            nowMillis = 1L,
        )

        assertEquals("10.000000, 20.000000", state.coordinatesInput)
        assertEquals("Some Street Address", state.selectedAddress)
    }

    @Test
    fun saveEnabledRequiresNameAndParsableCoordinates() {
        val empty = PlaceEditUiState.from(place = null, nowMillis = 1L)
        assertFalse(empty.isSaveEnabled)
        val named = empty.copy(name = "Camp")
        assertFalse(named.isSaveEnabled)
        val ready = named.copy(coordinatesInput = "10.000000, 20.000000")
        assertTrue(ready.isSaveEnabled)
    }
}
