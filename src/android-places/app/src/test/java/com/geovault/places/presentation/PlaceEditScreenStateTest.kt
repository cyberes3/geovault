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
    fun newPlace_initializesWithEmptyCoordinates() {
        val state = PlaceEditScreenState(initial = null, isOfflineEdit = false)

        assertEquals("", state.coordinatesInput)
        assertNull(state.selectedLat)
        assertNull(state.selectedLon)
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun hasUnsavedChanges_isFalse_whenInitializedWithSameValues() {
        val initial = sampleFeature()

        val state = PlaceEditScreenState(initial = initial, isOfflineEdit = false)

        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun setFromMapPoint_updatesCoordinatesAndClearsError() {
        val state = PlaceEditScreenState(
            initial = null,
            isOfflineEdit = false,
            initialMapTapSuppressionMillis = 0L,
        )
        state.coordinatesError = "Invalid"

        val accepted = state.setFromMapPoint(latitude = 12.34, longitude = 56.78)

        assertTrue(accepted)
        assertEquals(12.34, state.selectedLat!!, 0.0)
        assertEquals(56.78, state.selectedLon!!, 0.0)
        assertEquals("12.340000, 56.780000", state.coordinatesInput)
        assertNull(state.coordinatesError)
        assertTrue(state.showSelectedPointMarker)
        assertFalse(state.shouldFocusCameraOnSelection())
    }

    @Test
    fun setFromMapPoint_ignoresInitialTapForNewPlaceOnlyDuringStartupWindow() {
        var nowMillis = 1_000L
        val state = PlaceEditScreenState(
            initial = null,
            isOfflineEdit = false,
            nowMillis = { nowMillis },
            initialMapTapSuppressionMillis = 350L,
        )

        val ignored = state.setFromMapPoint(latitude = 12.34, longitude = 56.78)

        assertFalse(ignored)
        assertEquals("", state.coordinatesInput)
        assertNull(state.selectedLat)
        assertNull(state.selectedLon)

        nowMillis += 350L
        val accepted = state.setFromMapPoint(latitude = 12.34, longitude = 56.78)

        assertTrue(accepted)
        assertEquals(12.34, state.selectedLat!!, 0.0)
        assertEquals(56.78, state.selectedLon!!, 0.0)
        assertEquals("12.340000, 56.780000", state.coordinatesInput)
    }

    @Test
    fun setFromMapPoint_acceptsInitialTapForExistingPlace() {
        val nowMillis = 1_000L
        val state = PlaceEditScreenState(
            initial = sampleFeature(),
            isOfflineEdit = false,
            nowMillis = { nowMillis },
            initialMapTapSuppressionMillis = 350L,
        )

        val accepted = state.setFromMapPoint(latitude = 12.34, longitude = 56.78)

        assertTrue(accepted)
        assertEquals(12.34, state.selectedLat!!, 0.0)
        assertEquals(56.78, state.selectedLon!!, 0.0)
        assertEquals("12.340000, 56.780000", state.coordinatesInput)
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
        assertEquals("San Francisco, CA", state.selectedAddress)
        assertEquals("37.774900, -122.419400", state.coordinatesInput)
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

    @Test
    fun coordinatesSeedFromGeometry_notAddress() {
        val initial = Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0)),
            properties = Properties(
                database_id = 9,
                name = "Initial Place",
                description = "Initial Description",
                address = "Some Street Address",
            ),
        )
        val state = PlaceEditScreenState(initial = initial, isOfflineEdit = false)
        assertEquals("1.000000, 2.000000", state.coordinatesInput)
        assertEquals("Some Street Address", state.selectedAddress)
    }

    private fun sampleFeature(): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0)),
            properties = Properties(
                database_id = 9,
                name = "Initial Place",
                description = "Initial Description",
                address = "Some Street Address",
            ),
        )
    }
}
