package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapHistoryClearPolicyTest {
    @Test
    fun resolvesDisplayedTrackerToSingleForceReplace() {
        val action = MapHistoryClearPolicy.resolve(
            MapHistoryClearInput(
                clearedTrackerId = "t1",
                showAllTrackers = false,
                mapViewContext = MapViewContext.SINGLE_TRACKER,
                displayedTrackerId = "t1",
                selectedTrackerId = "t1"
            )
        )
        assertEquals(MapHistoryClearAction.REFRESH_DISPLAYED_SINGLE_FORCE_REPLACE, action)
    }

    @Test
    fun resolvesStreamedDisplayedTrackerToSingleForceReplace() {
        val action = MapHistoryClearPolicy.resolve(
            MapHistoryClearInput(
                clearedTrackerId = "streamed-tracker",
                showAllTrackers = false,
                mapViewContext = MapViewContext.SINGLE_TRACKER,
                displayedTrackerId = "streamed-tracker",
                selectedTrackerId = "default-selected"
            )
        )
        assertEquals(MapHistoryClearAction.REFRESH_DISPLAYED_SINGLE_FORCE_REPLACE, action)
    }

    @Test
    fun resolvesSelectedTrackerWhenDisplayedIsEmpty() {
        val action = MapHistoryClearPolicy.resolve(
            MapHistoryClearInput(
                clearedTrackerId = "t1",
                showAllTrackers = false,
                mapViewContext = MapViewContext.SINGLE_TRACKER,
                displayedTrackerId = null,
                selectedTrackerId = "t1"
            )
        )
        assertEquals(MapHistoryClearAction.REFRESH_SELECTED_SINGLE_FORCE_REPLACE, action)
    }

    @Test
    fun resolvesAllTrackersContextToRefreshAll() {
        val action = MapHistoryClearPolicy.resolve(
            MapHistoryClearInput(
                clearedTrackerId = "t1",
                showAllTrackers = true,
                mapViewContext = MapViewContext.SINGLE_TRACKER,
                displayedTrackerId = "t2",
                selectedTrackerId = "t2"
            )
        )
        assertEquals(MapHistoryClearAction.REFRESH_ALL, action)
    }

    @Test
    fun resolvesGroupContextToGroupOrAllRefresh() {
        val action = MapHistoryClearPolicy.resolve(
            MapHistoryClearInput(
                clearedTrackerId = "t1",
                showAllTrackers = false,
                mapViewContext = MapViewContext.GROUP,
                displayedTrackerId = "t2",
                selectedTrackerId = "t2"
            )
        )
        assertEquals(MapHistoryClearAction.REFRESH_GROUP_OR_ALL, action)
    }

    @Test
    fun resolvesUnrelatedSingleTrackerToNoOp() {
        val action = MapHistoryClearPolicy.resolve(
            MapHistoryClearInput(
                clearedTrackerId = "t1",
                showAllTrackers = false,
                mapViewContext = MapViewContext.SINGLE_TRACKER,
                displayedTrackerId = "t2",
                selectedTrackerId = "t2"
            )
        )
        assertEquals(MapHistoryClearAction.NO_OP, action)
    }
}
