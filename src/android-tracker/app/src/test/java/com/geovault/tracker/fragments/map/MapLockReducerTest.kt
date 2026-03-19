package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapLockReducerTest {
    @Test
    fun enableTrackerFollow_replacesPreviousGpsLock() {
        val next = MapLockReducer.reduce(
            MapLockState.GpsFollow,
            MapLockEvent.EnableTrackerFollow(
                target = LatLng(10.0, 20.0),
                needsInitialZoom = true
            )
        )

        assertTrue(next is MapLockState.TrackerFollow)
        val follow = next as MapLockState.TrackerFollow
        assertEquals(10.0, follow.target.latitude, 0.0)
        assertEquals(20.0, follow.target.longitude, 0.0)
        assertTrue(follow.needsInitialZoom)
    }

    @Test
    fun manualInteraction_disablesAnyActiveLock() {
        val next = MapLockReducer.reduce(
            MapLockState.LiveFit,
            MapLockEvent.ManualCameraInteraction
        )

        assertEquals(MapLockState.None, next)
    }

    @Test
    fun completeTrackerInitialZoom_turnsOffInitialZoomFlag() {
        val current = MapLockState.TrackerFollow(
            target = LatLng(1.0, 2.0),
            needsInitialZoom = true
        )
        val next = MapLockReducer.reduce(
            current,
            MapLockEvent.CompleteTrackerInitialZoom(reachedTargetZoom = true)
        )

        assertTrue(next is MapLockState.TrackerFollow)
        assertEquals(false, (next as MapLockState.TrackerFollow).needsInitialZoom)
    }
}
