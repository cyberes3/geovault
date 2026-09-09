package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapCameraDirective
import com.geovault.tracker.presentation.TrackerMapCameraDirectiveInput
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import com.geovault.tracker.presentation.TrackerMapUserLocationInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

class TrackerMapCameraCoordinatorTest {

    private val sampleBounds: LatLngBounds = LatLngBounds.Builder()
        .include(LatLng(25.0, -80.0))
        .include(LatLng(26.0, -79.0))
        .build()

    private fun selectionLockInput(lat: Double = 1.0, lon: Double = 2.0) = TrackerMapCameraDirectiveInput(
        followLockEnabled = false,
        gpsCollecting = false,
        followTargetLat = null,
        followTargetLon = null,
        selectionLockEnabled = true,
        selectionLockLat = lat,
        selectionLockLon = lon,
        liveActiveFitEnabled = false,
        bounds = sampleBounds,
    )

    @Test
    fun resolveFromLockState_identicalResolutionsReuseTheSameDirective() {
        val engine = MapRenderEngine()

        engine.resolveFromLockState(selectionLockInput())
        val first = engine.cameraDirective.value

        engine.resolveFromLockState(selectionLockInput())
        val second = engine.cameraDirective.value

        assertEquals(
            "Two back-to-back resolutions that resolve identically must not mint a new directive/id.",
            first.id,
            second.id,
        )
        assertEquals(first, second)
    }

    @Test
    fun resolveFromLockState_changedResolutionMintsANewDirective() {
        val engine = MapRenderEngine()

        engine.resolveFromLockState(selectionLockInput(lat = 1.0, lon = 2.0))
        val first = engine.cameraDirective.value

        engine.resolveFromLockState(selectionLockInput(lat = 3.0, lon = 4.0))
        val second = engine.cameraDirective.value

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun onUserGestureStarted_directiveMintedBeforeReportsStaleGenerationAfterward() {
        val engine = MapRenderEngine()

        engine.resolveFromLockState(selectionLockInput())
        val mintedDirective = engine.cameraDirective.value
        val generationAtMintTime = mintedDirective.generation

        engine.onUserGestureStarted()

        assertNotEquals(
            "A directive's stamped generation must go stale once a user gesture bumps the coordinator's generation.",
            generationAtMintTime,
            engine.cameraGeneration,
        )
        assertNotEquals(mintedDirective.generation, engine.cameraGeneration)
    }

    @Test
    fun onUserGestureStarted_bumpsGenerationEvenWithNoPriorDirective() {
        val engine = MapRenderEngine()
        val generationBefore = engine.cameraGeneration

        engine.onUserGestureStarted()

        assertNotEquals(generationBefore, engine.cameraGeneration)
    }

    @Test
    fun requestExplicitFit_carriesTheRequestedAnimatedMode() {
        val engine = MapRenderEngine()

        engine.requestExplicitFit(sampleBounds, TrackerMapFitTrailMode.Animated)

        val directive = engine.cameraDirective.value
        assertTrue(directive is TrackerMapCameraDirective.FitBounds)
        assertEquals(TrackerMapFitTrailMode.Animated, (directive as TrackerMapCameraDirective.FitBounds).mode)
    }

    @Test
    fun requestExplicitFit_carriesTheRequestedInstantMode() {
        val engine = MapRenderEngine()

        engine.requestExplicitFit(sampleBounds, TrackerMapFitTrailMode.Instant)

        val directive = engine.cameraDirective.value
        assertTrue(directive is TrackerMapCameraDirective.FitBounds)
        assertEquals(TrackerMapFitTrailMode.Instant, (directive as TrackerMapCameraDirective.FitBounds).mode)
    }

    @Test
    fun requestExplicitFit_nullBoundsIsANoOp() {
        val engine = MapRenderEngine()
        engine.resolveFromLockState(selectionLockInput())
        val before = engine.cameraDirective.value

        engine.requestExplicitFit(null, TrackerMapFitTrailMode.Animated)

        assertEquals(before, engine.cameraDirective.value)
    }

    @Test
    fun resetLastResolution_forcesNextResolveToMintEvenWhenResolutionUnchanged() {
        val engine = MapRenderEngine()
        engine.resolveFromLockState(selectionLockInput())
        val first = engine.cameraDirective.value

        engine.resetLastResolution()
        engine.resolveFromLockState(selectionLockInput())
        val second = engine.cameraDirective.value

        assertNotEquals(
            "resetLastResolution must force a fresh mint even for an identical resolution.",
            first.id,
            second.id,
        )
    }

    @Test
    fun resetLastResolution_forcesMintEvenWhenBothResolutionsAreNone() {
        val engine = MapRenderEngine()
        val noneInput = TrackerMapCameraDirectiveInput(
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
        engine.resolveFromLockState(noneInput)
        val first = engine.cameraDirective.value

        engine.resetLastResolution()
        engine.resolveFromLockState(noneInput)
        val second = engine.cameraDirective.value

        assertNotEquals(
            "A reset viewport re-resolving to None must still mint a fresh directive so the " +
                "consumer's LaunchedEffect re-runs for the new viewport.",
            first.id,
            second.id,
        )
    }

    @Test
    fun requestExplicitFit_resetsLastResolutionSoNextResolveAlwaysMints() {
        val engine = MapRenderEngine()
        engine.resolveFromLockState(selectionLockInput())

        engine.requestExplicitFit(sampleBounds, TrackerMapFitTrailMode.Instant)
        val afterExplicitFit = engine.cameraDirective.value

        engine.resolveFromLockState(selectionLockInput())
        val afterReResolve = engine.cameraDirective.value

        assertNotEquals(
            "An explicit fit must not let a subsequent identical precedence resolution dedupe away.",
            afterExplicitFit.id,
            afterReResolve.id,
        )
    }

    @Test
    fun generationFlow_reflectsGestureBumpsLive() {
        val engine = MapRenderEngine()
        val before = engine.cameraGenerationFlow.value

        engine.onUserGestureStarted()

        assertNotEquals(before, engine.cameraGenerationFlow.value)
        assertEquals(engine.cameraGeneration, engine.cameraGenerationFlow.value)
    }

    @Test
    fun onUserOwnedZoom_bumpsGenerationWithoutClearingUserOwnsZoom() {
        val engine = MapRenderEngine()
        val before = engine.cameraGeneration

        engine.onUserOwnedZoom()

        assertTrue(engine.userOwnsZoom)
        assertNotEquals(before, engine.cameraGeneration)
    }

    @Test
    fun onUserOwnedZoom_afterLiveFit_remintsCenterOnPoint() {
        val engine = MapRenderEngine()
        engine.resolveFromLockState(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = 3.0,
                followTargetLon = 4.0,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = true,
                bounds = sampleBounds,
                userOwnsZoom = false,
            )
        )
        assertTrue(engine.cameraDirective.value is TrackerMapCameraDirective.FitBounds)

        engine.onUserOwnedZoom()
        engine.resolveFromLockState(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = false,
                gpsCollecting = false,
                followTargetLat = 3.0,
                followTargetLon = 4.0,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = true,
                bounds = sampleBounds,
                userOwnsZoom = engine.userOwnsZoom,
            )
        )

        val directive = engine.cameraDirective.value
        assertTrue(directive is TrackerMapCameraDirective.CenterOnPoint)
        val center = directive as TrackerMapCameraDirective.CenterOnPoint
        assertEquals(TrackerMapCameraDirective.Reason.LiveActiveFit, center.reason)
        assertEquals(3.0, center.latitude, 0.0)
        assertEquals(4.0, center.longitude, 0.0)
    }

    @Test
    fun followLock_withPuckTarget_mintsCenterOnPuck() {
        val engine = MapRenderEngine()
        engine.setFollowPuck(12.0, 34.0)
        val followTarget = com.geovault.tracker.presentation.TrackerMapFollowLockTarget.resolve(
            followLockEnabled = true,
            puckLatitude = engine.followPuckLatitude(),
            puckLongitude = engine.followPuckLongitude(),
            liveHead = null,
        )
        engine.resolveFromLockState(
            TrackerMapCameraDirectiveInput(
                followLockEnabled = true,
                gpsCollecting = true,
                followTargetLat = followTarget?.first,
                followTargetLon = followTarget?.second,
                selectionLockEnabled = false,
                selectionLockLat = null,
                selectionLockLon = null,
                liveActiveFitEnabled = false,
                bounds = sampleBounds,
            )
        )

        val directive = engine.cameraDirective.value
        assertTrue(directive is TrackerMapCameraDirective.CenterOnPoint)
        val center = directive as TrackerMapCameraDirective.CenterOnPoint
        assertEquals(TrackerMapCameraDirective.Reason.FollowLock, center.reason)
        assertEquals(12.0, center.latitude, 0.0)
        assertEquals(34.0, center.longitude, 0.0)
    }

    @Test
    fun requestExplicitFit_unionsGpsHomeAnchorIntoBounds() {
        val engine = MapRenderEngine()
        engine.setGpsHomeAnchor(10.0, -70.0)

        engine.requestExplicitFit(sampleBounds, TrackerMapFitTrailMode.Animated)

        val fit = engine.cameraDirective.value as TrackerMapCameraDirective.FitBounds
        assertTrue(fit.bounds.contains(LatLng(10.0, -70.0)))
        assertTrue(fit.bounds.contains(LatLng(25.0, -80.0)))
        assertTrue(fit.bounds.contains(LatLng(26.0, -79.0)))
    }

    @Test
    fun resolveFromLockState_liveActiveFitUnionsFollowPuckWhenPuckEnabled() {
        val engine = MapRenderEngine()
        engine.updateLocationSurface(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = true,
            )
        )
        engine.setFollowPuck(10.0, -70.0)
        engine.resolveFromLockState(
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

        val fit = engine.cameraDirective.value as TrackerMapCameraDirective.FitBounds
        assertTrue(fit.bounds.contains(LatLng(10.0, -70.0)))
        assertTrue(fit.bounds.contains(LatLng(25.0, -80.0)))
    }

    @Test
    fun resolveFromLockState_liveActiveFitDoesNotUnionPuckWhenPuckDisabled() {
        val engine = MapRenderEngine()
        engine.setFollowPuck(10.0, -70.0)
        engine.resolveFromLockState(
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

        val fit = engine.cameraDirective.value as TrackerMapCameraDirective.FitBounds
        assertEquals(sampleBounds, fit.bounds)
    }

    @Test
    fun updateLocationSurface_puckDisableClearsHomeAnchorSoExplicitFitStaysTrailOnly() {
        val engine = MapRenderEngine()
        engine.updateLocationSurface(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = true,
            )
        )
        engine.setGpsHomeAnchor(10.0, -70.0)
        engine.updateLocationSurface(
            TrackerMapUserLocationInput(
                isMapActive = true,
                hasLocationPermission = true,
                isMapReady = true,
                userLocationRequestedThisSession = false,
            )
        )

        engine.requestExplicitFit(sampleBounds, TrackerMapFitTrailMode.Instant)

        val fit = engine.cameraDirective.value as TrackerMapCameraDirective.FitBounds
        assertEquals(sampleBounds, fit.bounds)
    }

    @Test
    fun onUserGestureStarted_clearsUserOwnsZoom() {
        val engine = MapRenderEngine()
        engine.onUserOwnedZoom()
        engine.onUserGestureStarted()
        assertEquals(false, engine.userOwnsZoom)
    }
}
