package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapCameraDirective
import com.geovault.tracker.presentation.TrackerMapCameraDirectiveInput
import com.geovault.tracker.presentation.TrackerMapCameraDirectivePolicy
import com.geovault.tracker.presentation.TrackerMapFitTrailMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLngBounds

/**
 * CAMERA-COORDINATOR: single owner of "what should the camera do next" for the tracker map.
 * Mirrors [com.geovault.common.maps.ui.camerafollow.GeoVaultMapCameraFollowController]'s shape --
 * one internal state holder, one flow out, one place that enforces "a stale command can never be
 * applied."
 *
 * Replaces two previously-independent delivery paths (a `cameraDirective` `StateFlow` and a
 * separate conflated `fitTrailEvents` `Channel`) that could each move the camera with no shared
 * staleness check between them -- the root cause of the map occasionally snapping to a
 * full-extent fit mid-gesture. Every camera command, precedence-driven ([resolveFromLockState])
 * or explicit ([requestExplicitFit]), now flows through [directive], stamped with the
 * [generation] active at mint time. The single consumer in `MapScreen` discards any directive
 * whose generation is behind the coordinator's current generation, so a command queued a moment
 * before a user gesture can never apply after the user has taken over the camera.
 */
internal class TrackerMapCameraCoordinator {
    private val directiveMutable =
        MutableStateFlow<TrackerMapCameraDirective>(TrackerMapCameraDirective.None())
    val directive: StateFlow<TrackerMapCameraDirective> = directiveMutable.asStateFlow()

    private var generationCounter: Long = 0L
    val generation: Long get() = generationCounter

    private var lastResolution: TrackerMapCameraDirectivePolicy.Resolution =
        TrackerMapCameraDirectivePolicy.Resolution.None
    private var nextId: Long = 1L

    /**
     * Invalidates any in-flight directive. Called unconditionally on every user gesture
     * (regardless of whether a lock was active beforehand) so a directive minted just before the
     * gesture can never land after it.
     */
    fun onUserGestureStarted() {
        generationCounter++
    }

    /**
     * Resolves the current precedence-driven camera target and only mints a new directive when
     * the resolution semantically changes. Equal back-to-back resolutions reuse the prior
     * directive (and therefore its id), so a `LaunchedEffect(directive.id)` consumer doesn't
     * re-animate on noisy, camera-irrelevant state churn.
     */
    fun resolveFromLockState(input: TrackerMapCameraDirectiveInput) {
        val resolution = TrackerMapCameraDirectivePolicy.resolve(input)
        if (resolution == lastResolution) return
        lastResolution = resolution
        directiveMutable.value = directiveFor(resolution)
    }

    /**
     * Replaces the old `requestFitTrail`/`fitTrailSignal` channel. Bounds must be computed by
     * the caller synchronously at request time (e.g. immediately after a reload commit or a new
     * point is accepted) rather than lazily inside the Compose collector -- strictly fresher than
     * the previous lazy pull, and removes the need for the consumer to call back into the
     * ViewModel to compute bounds. A `null` bounds means there's nothing to fit; silently a no-op.
     */
    fun requestExplicitFit(bounds: LatLngBounds?, mode: TrackerMapFitTrailMode) {
        if (bounds == null) return
        directiveMutable.value = TrackerMapCameraDirective.FitBounds(
            bounds = bounds,
            mode = mode,
            reason = TrackerMapCameraDirective.Reason.ExplicitFit,
            id = nextId++,
            generation = generationCounter,
        )
    }

    private fun directiveFor(resolution: TrackerMapCameraDirectivePolicy.Resolution): TrackerMapCameraDirective {
        val id = nextId++
        return when (resolution.reason) {
            TrackerMapCameraDirective.Reason.SelectionLock,
            TrackerMapCameraDirective.Reason.FollowLock -> {
                val lat = resolution.centerLat
                val lon = resolution.centerLon
                if (lat != null && lon != null) {
                    TrackerMapCameraDirective.CenterOnPoint(
                        latitude = lat,
                        longitude = lon,
                        reason = resolution.reason,
                        id = id,
                        generation = generationCounter,
                    )
                } else {
                    TrackerMapCameraDirective.None(id = id, generation = generationCounter)
                }
            }
            TrackerMapCameraDirective.Reason.LiveActiveFit,
            TrackerMapCameraDirective.Reason.InitialFit -> {
                val bounds = resolution.bounds
                if (bounds != null) {
                    TrackerMapCameraDirective.FitBounds(
                        bounds = bounds,
                        mode = TrackerMapFitTrailMode.Instant,
                        reason = resolution.reason,
                        id = id,
                        generation = generationCounter,
                    )
                } else {
                    TrackerMapCameraDirective.None(id = id, generation = generationCounter)
                }
            }
            TrackerMapCameraDirective.Reason.ExplicitFit,
            TrackerMapCameraDirective.Reason.NoOp -> TrackerMapCameraDirective.None(id = id, generation = generationCounter)
        }
    }
}
