package com.geovault.places.presentation

import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties
import com.geovault.common.maps.render.CommonMapIconIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlacesMapStateTransformsTest {
    @Test
    fun reconcileSelectedFeature_keepsSelectionUpdatedAfterModify() {
        val original = feature(id = 7, lat = 10.0, lon = 20.0, name = "Old")
        val updated = feature(id = 7, lat = 11.0, lon = 21.0, name = "New")

        val resolved = PlacesMapStateTransforms.reconcileSelectedFeature(
            features = listOf(updated),
            selectedFeature = original,
        )

        assertNotNull(resolved)
        assertEquals("New", resolved?.properties?.name)
        assertEquals(11.0, resolved!!.geometry.coordinates[1], 0.000001)
    }

    @Test
    fun reconcileSelectedFeature_clearsSelectionAfterDelete() {
        val selected = feature(id = 7, lat = 10.0, lon = 20.0, name = "Gone")

        val resolved = PlacesMapStateTransforms.reconcileSelectedFeature(
            features = emptyList(),
            selectedFeature = selected,
        )

        assertEquals(null, resolved)
    }

    @Test
    fun reconcileSelectedFeature_supportsNewFeaturesWithoutIdByCoordinates() {
        val selected = feature(id = null, lat = 1.0, lon = 2.0, name = "Draft")
        val reloaded = feature(id = null, lat = 1.0, lon = 2.0, name = "Draft")

        val resolved = PlacesMapStateTransforms.reconcileSelectedFeature(
            features = listOf(reloaded),
            selectedFeature = selected,
        )

        assertNotNull(resolved)
    }

    @Test
    fun buildRenderState_appliesSelectedPointStyle() {
        val features = listOf(
            feature(id = 1, lat = 10.0, lon = 11.0, name = "A"),
            feature(id = 2, lat = 12.0, lon = 13.0, name = "B"),
        )

        val renderState = PlacesMapStateTransforms.buildRenderState(
            features = features,
            selectedId = 2,
        )
        val selected = renderState.points.first { it.id == "2" }
        val normal = renderState.points.first { it.id == "1" }

        assertEquals(CommonMapIconIds.MARKER_SELECTED, selected.iconImageId)
        assertEquals(1.08f, selected.iconSize)
        assertEquals(CommonMapIconIds.MARKER_DEFAULT, normal.iconImageId)
        assertEquals(1f, normal.iconSize)
    }

    @Test
    fun featureBounds_returnsBoundsForMappedPoints() {
        val bounds = PlacesMapStateTransforms.featureBounds(
            listOf(
                feature(id = 1, lat = 40.0, lon = -120.0, name = "A"),
                feature(id = 2, lat = 41.0, lon = -121.0, name = "B"),
            ),
        )

        assertNotNull(bounds)
        assertEquals(40.0, bounds!!.southWest.latitude, 0.000001)
        assertEquals(41.0, bounds.northEast.latitude, 0.000001)
        assertEquals(-121.0, bounds.southWest.longitude, 0.000001)
        assertEquals(-120.0, bounds.northEast.longitude, 0.000001)
    }

    private fun feature(id: Int?, lat: Double, lon: Double, name: String): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(lon, lat)),
            properties = Properties(database_id = id, name = name),
        )
    }
}
