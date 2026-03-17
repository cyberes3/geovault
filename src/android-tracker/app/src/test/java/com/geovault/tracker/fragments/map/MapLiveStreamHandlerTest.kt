package com.geovault.tracker.fragments.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLiveStreamHandlerTest {

    @Test
    fun isMultiContext_trueWhenShowAllTrackers() {
        assertTrue(MapLiveStreamHandler.isMultiContext(showAllTrackers = true, mapViewContext = MapViewContext.SINGLE_TRACKER))
    }

    @Test
    fun isMultiContext_trueWhenGroupContext() {
        assertTrue(MapLiveStreamHandler.isMultiContext(showAllTrackers = false, mapViewContext = MapViewContext.GROUP))
    }

    @Test
    fun isMultiContext_falseWhenSingleTrackerContext() {
        assertFalse(MapLiveStreamHandler.isMultiContext(showAllTrackers = false, mapViewContext = MapViewContext.SINGLE_TRACKER))
    }

    @Test
    fun shouldHandleSingleTrackPoint_trueWhenDisplayedMatches() {
        assertTrue(MapLiveStreamHandler.shouldHandleSingleTrackPoint(trackId = "abc", displayedTrackerId = "abc"))
    }

    @Test
    fun shouldHandleSingleTrackPoint_falseWhenDisplayedDiffers() {
        assertFalse(MapLiveStreamHandler.shouldHandleSingleTrackPoint(trackId = "abc", displayedTrackerId = "def"))
    }

    @Test
    fun shouldHandleSingleTrackPoint_falseWhenDisplayedIsNull() {
        assertFalse(MapLiveStreamHandler.shouldHandleSingleTrackPoint(trackId = "abc", displayedTrackerId = null))
    }
}
