package com.geovault.places.presentation

import com.geovault.common.maps.geocoding.GeocodeSearchResult
import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceEditScreenStateTest {

    @Test
    fun hasUnsavedChanges_isFalse_whenInitializedWithSameValues() {
        val initial = sampleFeature()

        val state = PlaceEditScreenState(initial = initial, isOfflineEdit = false)

        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun setFromMapPoint_updatesCoordinatesAndClearsError() {
        val state = PlaceEditScreenState(initial = null, isOfflineEdit = false)
        state.coordinatesError = "Invalid"

        state.setFromMapPoint(latitude = 12.34, longitude = 56.78)

        assertEquals(12.34, state.selectedLat!!, 0.0)
        assertEquals(56.78, state.selectedLon!!, 0.0)
        assertEquals("12.340000, 56.780000", state.coordinatesInput)
        assertNull(state.coordinatesError)
        assertTrue(state.showSelectedPointMarker)
        assertFalse(state.shouldFocusCameraOnSelection())
    }

    @Test
    fun setFromDeviceLocation_updatesCoordinates_showsSelectedMarker_andRequestsCameraFocus() {
        val state = PlaceEditScreenState(initial = null, isOfflineEdit = false)

        state.setFromDeviceLocation(latitude = 12.34, longitude = 56.78)

        assertEquals(12.34, state.selectedLat!!, 0.0)
        assertEquals(56.78, state.selectedLon!!, 0.0)
        assertEquals("12.340000, 56.780000", state.coordinatesInput)
        assertTrue(state.showSelectedPointMarker)
        assertTrue(state.shouldFocusCameraOnSelection())
        state.markSelectionCameraFocusHandled()
        assertFalse(state.shouldFocusCameraOnSelection())
    }

    @Test
    fun setFromSearchResult_updatesMarkerAndAddress() {
        val state = PlaceEditScreenState(initial = null, isOfflineEdit = false)

        state.setFromSearchResult(
            GeocodeSearchResult(
                coordinates = listOf(-122.4194, 37.7749),
                place_name = "San Francisco, CA",
                text = "San Francisco",
            ),
        )

        assertEquals(37.7749, state.selectedLat!!, 0.0)
        assertEquals(-122.4194, state.selectedLon!!, 0.0)
        assertEquals("San Francisco, CA", state.coordinatesInput)
        assertTrue(state.showSelectedPointMarker)
        assertTrue(state.shouldFocusCameraOnSelection())
    }

    @Test
    fun buildFeatureOrNull_returnsNull_forInvalidCoordinates() {
        val state = PlaceEditScreenState(initial = null, isOfflineEdit = false)
        state.name = "Point"
        state.coordinatesInput = "bad"

        val built = state.buildFeatureOrNull()

        assertNull(built)
        assertEquals("Invalid coordinates", state.coordinatesError)
    }

    @Test
    fun buildFeatureOrNull_buildsFeature_withExistingDatabaseId() {
        val initial = sampleFeature()
        val state = PlaceEditScreenState(initial = initial, isOfflineEdit = false)
        state.name = "Updated Place"
        state.description = "Updated"
        state.setFromMapPoint(latitude = 40.0, longitude = -70.0)

        val built = state.buildFeatureOrNull()

        assertNotNull(built)
        assertEquals(9, built?.properties?.database_id)
        assertEquals("Updated Place", built?.properties?.name)
        assertEquals("Updated", built?.properties?.description)
        assertEquals(listOf(-70.0, 40.0), built?.geometry?.coordinates)
    }

    @Test
    fun deleteActionLabel_matchesOnlineAndOfflineModes() {
        val existing = sampleFeature()
        val online = PlaceEditScreenState(initial = existing, isOfflineEdit = false)
        val offlineExisting = PlaceEditScreenState(initial = existing, isOfflineEdit = true)
        val offlineNew = PlaceEditScreenState(
            initial = existing.copy(properties = existing.properties.copy(database_id = null)),
            isOfflineEdit = true,
        )

        assertEquals("Delete", online.deleteActionLabel())
        assertEquals("Revert", offlineExisting.deleteActionLabel())
        assertEquals("Discard", offlineNew.deleteActionLabel())
    }

    private fun sampleFeature(): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(-122.0, 37.0)),
            properties = Properties(
                database_id = 9,
                name = "Initial Place",
                description = "Initial Description",
                address = "37.000000, -122.000000",
            ),
        )
    }
}
