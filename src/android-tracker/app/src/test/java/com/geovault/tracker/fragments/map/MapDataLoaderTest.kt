package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
