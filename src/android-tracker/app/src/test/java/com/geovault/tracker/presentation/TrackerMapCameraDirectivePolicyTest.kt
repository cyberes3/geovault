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
    fun resolve_followLockHoldsCameraWhenGpsNotCollecting() {
        // A claimed follow lock must never fall through to a lower-precedence directive
        // underneath it (live active fit, InitialFit) while GPS isn't actively producing a fix --
        // that fallthrough used to let a full-extent/other fit win while the follow-lock FAB
        // still showed armed.
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

        assertEquals(TrackerMapCameraDirective.Reason.NoOp, resolution.reason)
        assertNull(resolution.bounds)
    }

    @Test
    fun resolve_followLockHoldsCameraWhenTargetCoordinatesMissing() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = true,
                gpsCollecting = true,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = true,
                bounds = sampleBounds,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.NoOp, resolution.reason)
        assertNull(resolution.bounds)
    }

    @Test
    fun resolve_bothLocksEngagedBeatsFollowLockDefensively() {
        // Not expected to occur in practice (follow lock and selection/live-fit locks are
        // mutually exclusive by construction), but the precedence order documented on
        // TrackerMapCameraDirectivePolicy places selection+live-fit above follow lock, so the
        // resolver must honor that even if this invariant were ever violated upstream.
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = true,
                gpsCollecting = true,
                followTargetLat = 10.0,
                followTargetLon = 11.0,
                selectionLockEnabled = true,
                selectionLockLat = 1.0,
                selectionLockLon = 2.0,
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
    fun resolve_selectionLockHoldsCameraWhenCoordinatesMissing() {
        // A claimed selection lock must never fall through to a bounds-based directive
        // underneath it -- that's exactly what let a full-extent fit win the race the instant a
        // stream starts, before the tracker's first point has resolved a coordinate.
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

        assertEquals(TrackerMapCameraDirective.Reason.NoOp, resolution.reason)
        assertNull(resolution.bounds)
    }

    @Test
    fun resolve_liveActiveFitBeatsSelectionLockWhenBothEnabledAndBoundsResolved() {
        // Selection lock + live active fit together is the "keep re-fitting bounds around the
        // locked tracker" combo the secondary FAB exists for (TrackerMapLiveActiveFitPolicy only
        // shows that FAB while already locked) -- it must produce a bounds fit, not freeze the
        // camera on a single point and ignore the live-fit request.
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = true,
                selectionLockLat = 1.0,
                selectionLockLon = 2.0,
                liveActiveFitEnabled = true,
                bounds = sampleBounds,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.LiveActiveFit, resolution.reason)
        assertEquals(sampleBounds, resolution.bounds)
    }

    @Test
    fun resolve_bothEnabledFallsBackToSelectionLockWhenBoundsUnresolved() {
        // Bounds haven't resolved yet (e.g. the instant live active fit is toggled on before a
        // trail exists) but the locked point has -- center on it rather than going fully idle.
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = true,
                selectionLockLat = 1.0,
                selectionLockLon = 2.0,
                liveActiveFitEnabled = true,
                bounds = null,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.SelectionLock, resolution.reason)
        assertEquals(1.0, resolution.centerLat!!, 0.0)
        assertEquals(2.0, resolution.centerLon!!, 0.0)
    }

    @Test
    fun resolve_bothEnabledHoldsCameraWhenNeitherCoordinatesNorBoundsResolved() {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = null,
                followTargetLon = null,
                selectionLockEnabled = true,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = true,
                bounds = null,
            )
        )

        assertEquals(TrackerMapCameraDirective.Reason.NoOp, resolution.reason)
    }
}
