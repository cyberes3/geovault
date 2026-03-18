package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

class MapSelectionUtilsTest {
    @Test
    fun selectedFromDisplayedState_prefersStreamTimestamp() {
        val selected = MapSelectionUtils.selectedFromDisplayedState(
            displayedTrackerId = "t1",
            displayedTrackerName = "Tracker",
            displayedTrackerIsOwner = true,
            lat = 10.0,
            lon = 20.0,
            currentTrackerColor = null,
            defaultHexColor = "#112233",
            lastStreamedPointTimeMs = 3000L,
            lastCachedUpdateTimeMs = 2000L,
            displayedTrackerLastUpdateMs = 1000L,
            lastKnownUpdateMs = 500L
        )

        assertEquals("t1", selected.id)
        assertEquals(3000L, selected.lastUpdateMs)
        assertEquals("#112233", selected.hexColor)
    }

    @Test
    fun selectedFromFeature_mapsFeatureProperties() {
        val feature = Feature.fromGeometry(Point.fromLngLat(1.0, 2.0))
        feature.addStringProperty("trackerId", "t2")
        feature.addStringProperty("trackerName", "Two")
        feature.addNumberProperty("lat", 50.0)
        feature.addNumberProperty("lon", 60.0)
        feature.addNumberProperty("lastUpdateMs", 1234.0)
        feature.addNumberProperty("isOwner", 1.0)
        feature.addStringProperty("hexColor", "#abcdef")

        val selected = MapSelectionUtils.selectedFromFeature(
            feature = feature,
            defaultHexColor = "#000000",
            lastKnownById = emptyMap(),
            resolveTrackerIsOwner = { false }
        )

        assertNotNull(selected)
        assertEquals("t2", selected?.id)
        assertTrue(selected?.isOwner == true)
        assertEquals("#abcdef", selected?.hexColor)
    }
}

