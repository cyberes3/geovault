package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapLockResumeResolverTest {
    @Test
    fun resolve_followLock_usesFallbackTrackPointWhenAvailable() {
        val input = MapLockResumeInput(
            lockState = MapLockState.TrackerFollow(
                target = LatLng(1.0, 1.0),
                needsInitialZoom = true
            ),
            fallbackTrackPoint = LatLng(2.0, 3.0),
            showMyLocationEnabled = false,
            liveActiveFitAvailable = false
        )

        val decision = MapLockResumeResolver.resolve(input)

        assertTrue(decision.lockState is MapLockState.TrackerFollow)
        assertEquals(2.0, decision.followTarget?.latitude ?: 0.0, 0.0)
        assertEquals(3.0, decision.followTarget?.longitude ?: 0.0, 0.0)
        assertFalse(decision.shouldTrackGpsCamera)
        assertFalse(decision.shouldApplyLiveFit)
    }

    @Test
    fun resolve_gpsLock_disablesWhenMyLocationModeOff() {
        val decision = MapLockResumeResolver.resolve(
            MapLockResumeInput(
                lockState = MapLockState.GpsFollow,
                fallbackTrackPoint = null,
                showMyLocationEnabled = false,
                liveActiveFitAvailable = false
            )
        )

        assertEquals(MapLockState.None, decision.lockState)
        assertFalse(decision.shouldTrackGpsCamera)
    }

    @Test
    fun resolve_liveFit_disablesWhenUnavailable() {
        val decision = MapLockResumeResolver.resolve(
            MapLockResumeInput(
                lockState = MapLockState.LiveFit,
                fallbackTrackPoint = null,
                showMyLocationEnabled = true,
                liveActiveFitAvailable = false
            )
        )

        assertEquals(MapLockState.None, decision.lockState)
        assertFalse(decision.shouldApplyLiveFit)
    }
}
