package com.geovault.tracker.presentation

import org.maplibre.android.geometry.LatLngBounds

/**
 * CAMERA-DIRECTIVE: single source of truth for "what the camera should do next".
 *
 * Every camera command -- precedence-driven (lock resolution) or explicit (a one-shot fit
 * request) -- flows through this same sealed type via
 * [com.geovault.tracker.map.TrackerMapCameraCoordinator]. The consumer applies it and waits for
 * the next non-equal emission.
 *
 * `id` lets the consumer key a `LaunchedEffect` deterministically. When the resolved directive
 * changes meaningfully (different reason, different target, or a fresh explicit-fit request) the
 * id increments and the effect re-runs; consecutive identical resolutions share an id and do
 * not retrigger camera animations.
 *
 * `generation` is the coordinator's manual-control generation at mint time. A user gesture bumps
 * the generation unconditionally; the consumer discards any directive whose `generation` is
 * behind the coordinator's current generation, so a command queued a moment before the user took
 * over the camera can never apply after the fact.
 */
sealed class TrackerMapCameraDirective {
    abstract val id: Long
    abstract val generation: Long
    abstract val reason: Reason

    data class None(override val id: Long = 0L, override val generation: Long = 0L) : TrackerMapCameraDirective() {
        override val reason: Reason get() = Reason.NoOp
    }

    /**
     * Center on a tracker/GPS position for [Reason.SelectionLock] or [Reason.FollowLock]. Despite
     * the "center" framing, the consumer does not strictly preserve whatever zoom is currently on
     * screen -- it zooms in to at least a sensible floor (never zooming back out past it), so
     * engaging a lock actually focuses on the position instead of leaving the camera at whatever
     * zoom a prior fit happened to land on. See `geoVaultCenterCameraWithMinimumZoom`.
     */
    data class CenterOnPoint(
        val latitude: Double,
        val longitude: Double,
        override val reason: Reason,
        override val id: Long,
        override val generation: Long,
    ) : TrackerMapCameraDirective()

    data class FitBounds(
        val bounds: LatLngBounds,
        val mode: TrackerMapFitTrailMode,
        override val reason: Reason,
        override val id: Long,
        override val generation: Long,
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
    val userOwnsZoom: Boolean = false,
)

/**
 * CAMERA-DIRECTIVE: precedence is hard-coded so consumers don't have to reason about it. Tests
 * cover every (input -> directive) row, which means adding a new lock kind in the future starts
 * with a failing test for the precedence row.
 *
 * Precedence, highest to lowest:
 *  1. Selection lock + live active fit BOTH on ([TrackerMapLiveActiveFitPolicy] composes these
 *     two in SINGLE_SESSION) -- fit bounds; fall back to centering on the locked point if bounds
 *     haven't resolved yet, so the "keep re-fitting around the lock" request still does
 *     *something* useful during that gap instead of going idle. Checked ahead of follow lock
 *     defensively -- these two locks are not expected to ever be engaged simultaneously with
 *     follow lock, but if that invariant were ever violated, the composed selection+live-fit
 *     intent must still win per this documented order rather than depending on branch order below.
 *  2. Selection lock alone -- fixed center-on-point. Deliberately holds the camera still (None)
 *     rather than falling through to a bounds-based directive when the locked point hasn't
 *     resolved yet (e.g. the instant a stream starts, before its first point has landed) --
 *     that fallthrough used to hand the camera to a full-extent InitialFit fit, which is exactly
 *     the "lock conflicts with full extent" behavior this guards against.
 *  3. Follow lock (GPS) -- center-on-point. Also holds (None) rather than falling through when
 *     GPS isn't actively collecting or hasn't resolved a fix yet, for the same reason as #2 --
 *     otherwise the FAB shows the lock armed while the camera silently snaps to a full-extent fit.
 *  4. Live active fit alone -- fit bounds, or center at the current zoom when the user owns zoom.
 *  5. No lock active -- one-shot InitialFit of whatever bounds are available.
 *  6. Nothing resolvable -- None.
 */
object TrackerMapCameraDirectivePolicy {
    fun resolve(input: TrackerMapCameraDirectiveInput): Resolution {
        val bothLocksEngaged = input.selectionLockEnabled && input.liveActiveFitEnabled
        if (input.selectionLockEnabled && !bothLocksEngaged) {
            return selectionLockCenterOrHold(input)
        }
        if (bothLocksEngaged) {
            liveActiveFitOrHold(input)?.let { return it }
            // Live active fit's bounds haven't resolved yet -- fall back to the locked point
            // rather than dropping the camera lock entirely for that gap.
            return selectionLockCenterOrHold(input)
        }
        if (input.followLockEnabled) {
            return if (
                input.gpsCollecting &&
                input.followTargetLat != null &&
                input.followTargetLon != null
            ) {
                Resolution(
                    reason = TrackerMapCameraDirective.Reason.FollowLock,
                    centerLat = input.followTargetLat,
                    centerLon = input.followTargetLon,
                    bounds = null,
                )
            } else {
                // HOLD: GPS isn't actively producing a fix right now (e.g. collection paused,
                // permission dropped mid-session) -- hold the camera instead of falling through
                // to a full-extent InitialFit while the follow-lock FAB still shows armed.
                Resolution.None
            }
        }
        if (input.liveActiveFitEnabled) {
            liveActiveFitOrHold(input)?.let { return it }
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

    private fun liveActiveFitOrHold(input: TrackerMapCameraDirectiveInput): Resolution? {
        if (input.userOwnsZoom) {
            val lat = input.followTargetLat
                ?: input.selectionLockLat
                ?: input.bounds?.let { (it.latitudeNorth + it.latitudeSouth) / 2.0 }
            val lon = input.followTargetLon
                ?: input.selectionLockLon
                ?: input.bounds?.let { (it.longitudeEast + it.longitudeWest) / 2.0 }
            if (lat != null && lon != null) {
                return Resolution(
                    reason = TrackerMapCameraDirective.Reason.LiveActiveFit,
                    centerLat = lat,
                    centerLon = lon,
                    bounds = null,
                )
            }
            return null
        }
        val bounds = input.bounds ?: return null
        return Resolution(
            reason = TrackerMapCameraDirective.Reason.LiveActiveFit,
            centerLat = null,
            centerLon = null,
            bounds = bounds,
        )
    }

    private fun selectionLockCenterOrHold(input: TrackerMapCameraDirectiveInput): Resolution {
        val lat = input.selectionLockLat
        val lon = input.selectionLockLon
        return if (lat != null && lon != null) {
            Resolution(
                reason = TrackerMapCameraDirective.Reason.SelectionLock,
                centerLat = lat,
                centerLon = lon,
                bounds = null,
            )
        } else {
            Resolution.None
        }
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
