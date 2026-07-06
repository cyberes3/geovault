package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapCameraDirective
import com.geovault.tracker.presentation.TrackerMapCameraDirectiveInput
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
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
        val coordinator = TrackerMapCameraCoordinator()

        coordinator.resolveFromLockState(selectionLockInput())
        val first = coordinator.directive.value

        coordinator.resolveFromLockState(selectionLockInput())
        val second = coordinator.directive.value

        assertEquals(
            "Two back-to-back resolutions that resolve identically must not mint a new directive/id.",
            first.id,
            second.id,
        )
        assertEquals(first, second)
    }

    @Test
    fun resolveFromLockState_changedResolutionMintsANewDirective() {
        val coordinator = TrackerMapCameraCoordinator()

        coordinator.resolveFromLockState(selectionLockInput(lat = 1.0, lon = 2.0))
        val first = coordinator.directive.value

        coordinator.resolveFromLockState(selectionLockInput(lat = 3.0, lon = 4.0))
        val second = coordinator.directive.value

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun onUserGestureStarted_directiveMintedBeforeReportsStaleGenerationAfterward() {
        val coordinator = TrackerMapCameraCoordinator()

        coordinator.resolveFromLockState(selectionLockInput())
        val mintedDirective = coordinator.directive.value
        val generationAtMintTime = mintedDirective.generation

        coordinator.onUserGestureStarted()

        assertNotEquals(
            "A directive's stamped generation must go stale once a user gesture bumps the coordinator's generation.",
            generationAtMintTime,
            coordinator.generation,
        )
        assertNotEquals(mintedDirective.generation, coordinator.generation)
    }

    @Test
    fun onUserGestureStarted_bumpsGenerationEvenWithNoPriorDirective() {
        val coordinator = TrackerMapCameraCoordinator()
        val generationBefore = coordinator.generation

        coordinator.onUserGestureStarted()

        assertNotEquals(generationBefore, coordinator.generation)
    }

    @Test
    fun requestExplicitFit_carriesTheRequestedAnimatedMode() {
        val coordinator = TrackerMapCameraCoordinator()

        coordinator.requestExplicitFit(sampleBounds, TrackerMapFitTrailMode.Animated)

        val directive = coordinator.directive.value
        assertTrue(directive is TrackerMapCameraDirective.FitBounds)
        assertEquals(TrackerMapFitTrailMode.Animated, (directive as TrackerMapCameraDirective.FitBounds).mode)
    }

    @Test
    fun requestExplicitFit_carriesTheRequestedInstantMode() {
        val coordinator = TrackerMapCameraCoordinator()

        coordinator.requestExplicitFit(sampleBounds, TrackerMapFitTrailMode.Instant)

        val directive = coordinator.directive.value
        assertTrue(directive is TrackerMapCameraDirective.FitBounds)
        assertEquals(TrackerMapFitTrailMode.Instant, (directive as TrackerMapCameraDirective.FitBounds).mode)
    }

    @Test
    fun requestExplicitFit_nullBoundsIsANoOp() {
        val coordinator = TrackerMapCameraCoordinator()
        coordinator.resolveFromLockState(selectionLockInput())
        val before = coordinator.directive.value

        coordinator.requestExplicitFit(null, TrackerMapFitTrailMode.Animated)

        assertEquals(before, coordinator.directive.value)
    }

    @Test
    fun resetLastResolution_forcesNextResolveToMintEvenWhenResolutionUnchanged() {
        val coordinator = TrackerMapCameraCoordinator()
        coordinator.resolveFromLockState(selectionLockInput())
        val first = coordinator.directive.value

        coordinator.resetLastResolution()
        coordinator.resolveFromLockState(selectionLockInput())
        val second = coordinator.directive.value

        assertNotEquals(
            "resetLastResolution must force a fresh mint even for an identical resolution.",
            first.id,
            second.id,
        )
    }

    @Test
    fun resetLastResolution_forcesMintEvenWhenBothResolutionsAreNone() {
        val coordinator = TrackerMapCameraCoordinator()
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
        coordinator.resolveFromLockState(noneInput)
        val first = coordinator.directive.value

        coordinator.resetLastResolution()
        coordinator.resolveFromLockState(noneInput)
        val second = coordinator.directive.value

        assertNotEquals(
            "A reset viewport re-resolving to None must still mint a fresh directive so the " +
                "consumer's LaunchedEffect re-runs for the new viewport.",
            first.id,
            second.id,
        )
    }

    @Test
    fun requestExplicitFit_resetsLastResolutionSoNextResolveAlwaysMints() {
        val coordinator = TrackerMapCameraCoordinator()
        coordinator.resolveFromLockState(selectionLockInput())

        coordinator.requestExplicitFit(sampleBounds, TrackerMapFitTrailMode.Instant)
        val afterExplicitFit = coordinator.directive.value

        coordinator.resolveFromLockState(selectionLockInput())
        val afterReResolve = coordinator.directive.value

        assertNotEquals(
            "An explicit fit must not let a subsequent identical precedence resolution dedupe away.",
            afterExplicitFit.id,
            afterReResolve.id,
        )
    }

    @Test
    fun generationFlow_reflectsGestureBumpsLive() {
        val coordinator = TrackerMapCameraCoordinator()
        val before = coordinator.generationFlow.value

        coordinator.onUserGestureStarted()

        assertNotEquals(before, coordinator.generationFlow.value)
        assertEquals(coordinator.generation, coordinator.generationFlow.value)
    }
}
