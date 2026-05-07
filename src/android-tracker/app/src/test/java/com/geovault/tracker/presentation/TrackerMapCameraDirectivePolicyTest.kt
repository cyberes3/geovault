package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

class TrackerMapCameraDirectivePolicyTest {

    private val sampleBounds: LatLngBounds = LatLngBounds.Builder()
        .include(LatLng(25.0, -80.0))
        .include(LatLng(26.0, -79.0))
        .build()

    @Test
    fun resolve_selectionLockBeatsFollowLock() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = true,
                gpsCollecting = true,
                followTargetLat = 10.0,
                followTargetLon = 11.0,
                selectionLockEnabled = true,
                selectionLockLat = 1.0,
                selectionLockLon = 2.0,
                liveActiveFitEnabled = false,
                bounds = sampleBounds,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.SelectionLock, resolution.reason)
        assertEquals(1.0, resolution.centerLat!!, 0.0)
        assertEquals(2.0, resolution.centerLon!!, 0.0)
        assertNull(resolution.bounds)
    }

    @Test
    fun resolve_followLockBeatsLiveActiveFitWhenGpsCollecting() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = true,
                gpsCollecting = true,
                followTargetLat = 10.0,
                followTargetLon = 11.0,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = true,
                bounds = sampleBounds,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.FollowLock, resolution.reason)
        assertEquals(10.0, resolution.centerLat!!, 0.0)
    }

    @Test
    fun resolve_followLockSkippedWhenGpsNotCollecting() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = true,
                gpsCollecting = false,
                followTargetLat = 10.0,
                followTargetLon = 11.0,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = true,
                bounds = sampleBounds,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.LiveActiveFit, resolution.reason)
        assertEquals(sampleBounds, resolution.bounds)
    }

    @Test
    fun resolve_liveActiveFitBeatsInitialFit() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = true,
                bounds = sampleBounds,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.LiveActiveFit, resolution.reason)
    }

    @Test
    fun resolve_initialFitWhenOnlyBoundsAvailable() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = false,
                bounds = sampleBounds,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.InitialFit, resolution.reason)
    }

    @Test
    fun resolve_noOpWhenAllInputsBlank() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = false,
                bounds = null,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.NoOp, resolution.reason)
    }

    @Test
    fun resolve_selectionLockSkippedWhenCoordinatesMissing() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = true,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = false,
                bounds = sampleBounds,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.InitialFit, resolution.reason)
    }
}
