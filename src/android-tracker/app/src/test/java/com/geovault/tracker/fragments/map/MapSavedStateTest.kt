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
            followLockEnabled = true,
            followLockNeedsInitialZoom = true,
            lockTargetLat = 12.34,
            lockTargetLon = 56.78,
            showMyLocationEnabled = true,
            gpsLocationLockActive = true,
            liveActiveFitEnabled = true,
            displayedTrackerId = "t1",
            displayedTrackerName = "Tracker 1",
            displayedGroupName = "Group A",
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER
        )

        state.writeTo(bundle)
        val restored = MapSavedState.readFrom(bundle)

        assertTrue(restored.followLockEnabled)
        assertTrue(restored.followLockNeedsInitialZoom)
        assertEquals(12.34, restored.lockTargetLat ?: 0.0, 0.000001)
        assertEquals(56.78, restored.lockTargetLon ?: 0.0, 0.000001)
        assertTrue(restored.showMyLocationEnabled)
        assertTrue(restored.gpsLocationLockActive)
        assertTrue(restored.liveActiveFitEnabled)
        assertEquals("t1", restored.displayedTrackerId)
        assertEquals("Tracker 1", restored.displayedTrackerName)
        assertEquals("Group A", restored.displayedGroupName)
        assertFalse(restored.showAllTrackers)
        assertEquals(MapViewContext.SINGLE_TRACKER, restored.mapViewContext)
    }

    @Test
    fun readFrom_missingOptionalLockTarget_returnsNullTarget() {
        val bundle = Bundle().apply {
            putBoolean("follow_lock", true)
            putBoolean("follow_lock_initial_zoom", false)
            putBoolean("show_my_location", false)
            putBoolean("gps_location_lock_active", false)
            putBoolean("live_active_fit_enabled", false)
        }

        val restored = MapSavedState.readFrom(bundle)

        assertTrue(restored.followLockEnabled)
        assertFalse(restored.followLockNeedsInitialZoom)
        assertNull(restored.lockTargetLat)
        assertNull(restored.lockTargetLon)
    }
}
