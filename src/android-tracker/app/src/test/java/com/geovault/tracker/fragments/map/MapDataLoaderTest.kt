package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapDataLoaderTest {

    @Test
    fun resolveActiveTrackerId_prefersDisplayed() {
        assertEquals("displayed", MapDataLoader.resolveActiveTrackerId("displayed", "selected"))
    }

    @Test
    fun resolveActiveTrackerId_fallsBackToSelected() {
        assertEquals("selected", MapDataLoader.resolveActiveTrackerId(null, "selected"))
    }

    @Test
    fun isExternalStreaming_trueWhenNotForcedAndDisplayedExists() {
        assertTrue(
            MapDataLoader.isExternalStreaming(
                forceReplace = false,
                hasTrackPoints = true,
                displayedTrackerId = "id-1"
            )
        )
    }

    @Test
    fun isExternalStreaming_falseWhenForceReplace() {
        assertFalse(
            MapDataLoader.isExternalStreaming(
                forceReplace = true,
                hasTrackPoints = true,
                displayedTrackerId = "id-1"
            )
        )
    }

    @Test
    fun shouldAutoZoomSingleTracker_trueWhenNoPointsYet() {
        assertTrue(MapDataLoader.shouldAutoZoomSingleTracker(trackPointsEmpty = true))
    }

    @Test
    fun shouldAutoZoomSingleTracker_falseWhenPointsAlreadyExist() {
        assertFalse(MapDataLoader.shouldAutoZoomSingleTracker(trackPointsEmpty = false))
    }

    @Test
    fun resolveSingleTrackerZoomTarget_prefersLatestTrackPoint() {
        val latest = LatLng(3.0, 4.0)
        val target = MapDataLoader.resolveSingleTrackerZoomTarget(
            trackPoints = listOf(LatLng(1.0, 2.0), latest),
            fallbackLastPoint = listOf(100.0, 200.0)
        )
        assertEquals(latest, target)
    }

    @Test
    fun resolveSingleTrackerZoomTarget_usesFallbackLastPoint() {
        val target = MapDataLoader.resolveSingleTrackerZoomTarget(
            trackPoints = emptyList(),
            fallbackLastPoint = listOf(10.0, 20.0)
        )
        assertEquals(LatLng(20.0, 10.0), target)
    }
}
