package com.geovault.tracker.presentation

import org.maplibre.android.geometry.LatLngBounds

/**
 * CAMERA-DIRECTIVE: single source of truth for "what the camera should do next".
 *
 * Every camera command -- precedence-driven (lock resolution) or explicit (a one-shot fit
 * request) -- flows through this same sealed type via
 * [com.geovault.common.maps.ui.camera.GeoVaultMapCameraDirectiveBus]. The consumer applies it and waits for
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

data class TrackerMapCameraDirectiveResolution(
    val reason: TrackerMapCameraDirective.Reason,
    val centerLat: Double?,
    val centerLon: Double?,
    val bounds: LatLngBounds?,
) {
    companion object {
        val None = TrackerMapCameraDirectiveResolution(
            reason = TrackerMapCameraDirective.Reason.NoOp,
            centerLat = null,
            centerLon = null,
            bounds = null,
        )
    }
}
