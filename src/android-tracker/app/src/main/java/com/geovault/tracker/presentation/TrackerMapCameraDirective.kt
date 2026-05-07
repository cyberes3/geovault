package com.geovault.tracker.presentation

import org.maplibre.android.geometry.LatLngBounds

/**
 * CAMERA-DIRECTIVE: single source of truth for "what the camera should do next".
 *
 * Replaces a handful of overlapping `LaunchedEffect` blocks in `MapScreen` that each watched a
 * different slice of state and could interleave in unpredictable ways. Each emission represents
 * the resolved precedence winner; the consumer applies it and waits for the next non-equal
 * emission.
 *
 * `id` lets the consumer key a `LaunchedEffect` deterministically. When the resolved directive
 * changes meaningfully (different reason, different target, or a fresh ExplicitFit gesture) the
 * id increments and the effect re-runs; consecutive identical resolutions share an id and do
 * not retrigger camera animations.
 */
sealed class TrackerMapCameraDirective {
    abstract val id: Long
    abstract val reason: Reason

    data class None(override val id: Long = 0L) : TrackerMapCameraDirective() {
        override val reason: Reason get() = Reason.NoOp
    }

    data class CenterPreserveZoom(
        val latitude: Double,
        val longitude: Double,
        override val reason: Reason,
        override val id: Long,
    ) : TrackerMapCameraDirective()

    data class FitBounds(
        val bounds: LatLngBounds,
        override val reason: Reason,
        override val id: Long,
    ) : TrackerMapCameraDirective()

    enum class Reason {
        NoOp,
        SelectionLock,
        FollowLock,
        LiveActiveFit,
        InitialFit,
        ExplicitFit,
    }
}

data class TrackerMapCameraDirectiveInput(
    val followLockEnabled: Boolean,
    val gpsCollecting: Boolean,
    val followTargetLat: Double?,
    val followTargetLon: Double?,
    val selectionLockEnabled: Boolean,
    val selectionLockLat: Double?,
    val selectionLockLon: Double?,
    val liveActiveFitEnabled: Boolean,
    val bounds: LatLngBounds?,
)

/**
 * CAMERA-DIRECTIVE: precedence is hard-coded so consumers don't have to reason about it. Tests
 * cover every (input -> directive) row, which means adding a new lock kind in the future starts
 * with a failing test for the precedence row.
 *
 * NOTE: [TrackerMapCameraDirective.Reason.ExplicitFit] is intentionally _not_ produced here —
 * it is a one-shot user gesture routed through a separate channel/event so multiple presses
 * always re-fit even when the resulting bounds are equal to a previously-rendered fit.
 */
object TrackerMapCameraDirectivePolicy {
    fun resolve(input: TrackerMapCameraDirectiveInput): Resolution {
        if (
            input.selectionLockEnabled &&
            input.selectionLockLat != null &&
            input.selectionLockLon != null
        ) {
            return Resolution(
                reason = TrackerMapCameraDirective.Reason.SelectionLock,
                centerLat = input.selectionLockLat,
                centerLon = input.selectionLockLon,
                bounds = null,
            )
        }
        if (
            input.followLockEnabled &&
            input.gpsCollecting &&
            input.followTargetLat != null &&
            input.followTargetLon != null
        ) {
            return Resolution(
                reason = TrackerMapCameraDirective.Reason.FollowLock,
                centerLat = input.followTargetLat,
                centerLon = input.followTargetLon,
                bounds = null,
            )
        }
        if (input.liveActiveFitEnabled && input.bounds != null) {
            return Resolution(
                reason = TrackerMapCameraDirective.Reason.LiveActiveFit,
                centerLat = null,
                centerLon = null,
                bounds = input.bounds,
            )
        }
        if (input.bounds != null) {
            return Resolution(
                reason = TrackerMapCameraDirective.Reason.InitialFit,
                centerLat = null,
                centerLon = null,
                bounds = input.bounds,
            )
        }
        return Resolution.None
    }

    /**
     * Pure resolution result. The producer is responsible for stamping a stable `id` and turning
     * this into a concrete [TrackerMapCameraDirective]; that lets callers cache the previous
     * resolution and only mint a new id when the resolution semantically differs.
     */
    data class Resolution(
        val reason: TrackerMapCameraDirective.Reason,
        val centerLat: Double?,
        val centerLon: Double?,
        val bounds: LatLngBounds?,
    ) {
        companion object {
            val None = Resolution(
                reason = TrackerMapCameraDirective.Reason.NoOp,
                centerLat = null,
                centerLon = null,
                bounds = null,
            )
        }
    }
}
