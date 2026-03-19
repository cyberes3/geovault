package com.geovault.tracker.fragments.map

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MapSavedStateTest {
    @Test
    fun writeAndRead_preservesExtendedLockFields() {
        val bundle = Bundle()
        val state = MapSavedState(
            lockMode = MapLockMode.TRACKER_FOLLOW,
            lockTargetLat = 12.34,
            lockTargetLon = 56.78,
            lockNeedsInitialZoom = true,
            cameraZoom = 15.5,
            showMyLocationEnabled = true,
            displayedTrackerId = "t1",
            displayedTrackerName = "Tracker 1",
            displayedGroupName = "Group A",
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER
        )

        state.writeTo(bundle)
        val restored = MapSavedState.readFrom(bundle)

        assertEquals(MapLockMode.TRACKER_FOLLOW, restored.lockMode)
        assertTrue(restored.lockNeedsInitialZoom)
        assertEquals(12.34, restored.lockTargetLat ?: 0.0, 0.000001)
        assertEquals(56.78, restored.lockTargetLon ?: 0.0, 0.000001)
        assertEquals(15.5, restored.cameraZoom ?: 0.0, 0.000001)
        assertTrue(restored.showMyLocationEnabled)
        assertEquals("t1", restored.displayedTrackerId)
        assertEquals("Tracker 1", restored.displayedTrackerName)
        assertEquals("Group A", restored.displayedGroupName)
        assertFalse(restored.showAllTrackers)
        assertEquals(MapViewContext.SINGLE_TRACKER, restored.mapViewContext)
    }

    @Test
    fun readFrom_missingOptionalLockTarget_returnsNullTarget() {
        val bundle = Bundle().apply {
            putString("map_lock_mode", MapLockMode.TRACKER_FOLLOW.name)
            putBoolean("map_lock_initial_zoom", false)
            putBoolean("show_my_location", false)
        }

        val restored = MapSavedState.readFrom(bundle)

        assertEquals(MapLockMode.TRACKER_FOLLOW, restored.lockMode)
        assertFalse(restored.lockNeedsInitialZoom)
        assertNull(restored.lockTargetLat)
        assertNull(restored.lockTargetLon)
        assertNull(restored.cameraZoom)
    }
}
