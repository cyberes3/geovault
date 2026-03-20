package com.geovault.tracker.fragments.map

import com.geovault.tracker.pipeline.TrackPointSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapModeStateMachineTest {
    @Test
    fun derive_returnsTrackingSingleWhenTrackingRunning() {
        val state = MapModeStateMachine.derive(
            MapModeStateInput(
                trackingRunning = true,
                showAllTrackers = true,
                mapViewContext = MapViewContext.GROUP
            )
        )
        assertEquals(MapModeState.TRACKING_SINGLE, state)
    }

    @Test
    fun derive_returnsGroupStateWhenBrowsingGroup() {
        val state = MapModeStateMachine.derive(
            MapModeStateInput(
                trackingRunning = false,
                showAllTrackers = false,
                mapViewContext = MapViewContext.GROUP
            )
        )
        assertEquals(MapModeState.BROWSING_GROUP, state)
    }

    @Test
    fun sourcePolicy_isDeterministicByState() {
        assertTrue(MapModeStateMachine.acceptsSource(MapModeState.TRACKING_SINGLE, TrackPointSource.LOCAL_GPS))
        assertTrue(MapModeStateMachine.acceptsSource(MapModeState.TRACKING_SINGLE, TrackPointSource.REMOTE_STREAM))
        assertFalse(MapModeStateMachine.acceptsSource(MapModeState.BROWSING_SINGLE, TrackPointSource.LOCAL_GPS))
        assertTrue(MapModeStateMachine.acceptsSource(MapModeState.BROWSING_SINGLE, TrackPointSource.REMOTE_STREAM))
    }

    @Test
    fun transition_rejectsModeSwitchesDuringTracking() {
        val tracking = MapModeState.TRACKING_SINGLE
        assertEquals(tracking, MapModeStateMachine.transition(tracking, MapModeEvent.SHOW_GROUP))
        assertEquals(tracking, MapModeStateMachine.transition(tracking, MapModeEvent.SHOW_ALL_TRACKERS))
        assertEquals(tracking, MapModeStateMachine.transition(tracking, MapModeEvent.SHOW_SINGLE))
    }
}
