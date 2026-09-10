package com.geovault.places.presentation

import com.geovault.common.maps.render.CommonMapIconIds
import com.geovault.places.model.PlaceKey
import com.geovault.places.samplePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesMapStateTransformsTest {
    @Test
    fun reconcileSelectedKeyKeepsSelectionAfterContentChange() {
        val key = PlaceKey.server(7)
        val updated = samplePlace(key = key, serverId = 7, name = "New", latitude = 11.0, longitude = 21.0)

        val resolved = PlacesMapStateTransforms.reconcileSelectedKey(listOf(updated), key)

        assertEquals(key, resolved)
    }

    @Test
    fun reconcileSelectedKeyClearsSelectionAfterDelete() {
        val resolved = PlacesMapStateTransforms.reconcileSelectedKey(
            places = emptyList(),
            selectedKey = PlaceKey.server(7),
        )
        assertNull(resolved)
    }

    @Test
    fun reconcileSelectedKeyMatchesLocalDraftByKeyNotName() {
        val key = PlaceKey.local("draft-1")
        val reloaded = samplePlace(key = key, serverId = null, name = "Renamed")

        val resolved = PlacesMapStateTransforms.reconcileSelectedKey(listOf(reloaded), key)

        assertEquals(key, resolved)
    }

    @Test
    fun buildRenderStateUsesDefaultMarkerForEveryPlace() {
        val first = samplePlace(key = PlaceKey.server(1), serverId = 1, name = "A", latitude = 10.0, longitude = 11.0)
        val second = samplePlace(key = PlaceKey.server(2), serverId = 2, name = "B", latitude = 12.0, longitude = 13.0)

        val renderState = PlacesMapStateTransforms.buildRenderState(listOf(first, second))
        val selected = renderState.points.first { it.id == second.key.value }
        val normal = renderState.points.first { it.id == first.key.value }

        assertEquals(CommonMapIconIds.MARKER_DEFAULT, selected.iconImageId)
        assertEquals(1f, selected.iconSize)
        assertEquals(CommonMapIconIds.MARKER_DEFAULT, normal.iconImageId)
        assertEquals(1f, normal.iconSize)
    }

    @Test
    fun buildRenderStateSkipsInvalidCoordinates() {
        val invalid = samplePlace(latitude = 100.0, longitude = 20.0)
        val valid = samplePlace(key = PlaceKey.server(2), serverId = 2, name = "Ok")

        val renderState = PlacesMapStateTransforms.buildRenderState(listOf(invalid, valid))

        assertEquals(listOf(valid.key.value), renderState.points.map { it.id })
    }

    @Test
    fun featureBoundsReturnsBoundsForMappedPoints() {
        val bounds = PlacesMapStateTransforms.featureBounds(
            listOf(
                samplePlace(key = PlaceKey.server(1), serverId = 1, latitude = 40.0, longitude = -120.0),
                samplePlace(key = PlaceKey.server(2), serverId = 2, latitude = 41.0, longitude = -121.0),
            ),
        )

        assertNotNull(bounds)
        assertEquals(40.0, bounds!!.southWest.latitude, 0.000001)
        assertEquals(41.0, bounds.northEast.latitude, 0.000001)
        assertEquals(-121.0, bounds.southWest.longitude, 0.000001)
        assertEquals(-120.0, bounds.northEast.longitude, 0.000001)
    }

    @Test
    fun featureBoundsUsesShortLongitudeArcAcrossPacific() {
        val bounds = PlacesMapStateTransforms.featureBounds(
            listOf(
                samplePlace(key = PlaceKey.server(1), serverId = 1, latitude = 39.0, longitude = -95.0),
                samplePlace(key = PlaceKey.server(2), serverId = 2, latitude = 55.0, longitude = 37.0),
            ),
        )

        assertNotNull(bounds)
        val lonSpan = bounds!!.longitudeEast - bounds.longitudeWest
        assertTrue("span should be the short arc (~132°), not ~292°", lonSpan < 200.0)
    }
}
