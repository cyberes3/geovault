package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapListRefreshPolicyTest {
    @Test
    fun returnsLoadAll_forAllTrackersNonGroupContext() {
        val action = MapListRefreshPolicy.resolve(
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            selectedTrackerId = "selected",
            displayedTrackerId = "displayed"
        )
        assertEquals(MapListRefreshAction.LOAD_ALL, action)
    }

    @Test
    fun returnsRefreshSelectedTracker_forSingleContextMismatch() {
        val action = MapListRefreshPolicy.resolve(
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            selectedTrackerId = "selected",
            displayedTrackerId = "displayed"
        )
        assertEquals(MapListRefreshAction.REFRESH_SELECTED_TRACKER, action)
    }

    @Test
    fun returnsNoOp_forSingleContextWhenIdsMatch() {
        val action = MapListRefreshPolicy.resolve(
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            selectedTrackerId = "selected",
            displayedTrackerId = "selected"
        )
        assertEquals(MapListRefreshAction.NO_OP, action)
    }

    @Test
    fun returnsNoOp_forGroupContext() {
        val action = MapListRefreshPolicy.resolve(
            showAllTrackers = false,
            mapViewContext = MapViewContext.GROUP,
            selectedTrackerId = "selected",
            displayedTrackerId = "displayed"
        )
        assertEquals(MapListRefreshAction.NO_OP, action)
    }
}
