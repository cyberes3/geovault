package com.geovault.tracker.fragments.map

import org.maplibre.android.geometry.LatLng

sealed interface MapLockEvent {
    data class EnableTrackerFollow(
        val target: LatLng,
        val needsInitialZoom: Boolean
    ) : MapLockEvent

    data class RecenterTrackerFollow(val target: LatLng) : MapLockEvent

    data class CompleteTrackerInitialZoom(val reachedTargetZoom: Boolean) : MapLockEvent

    data object EnableGpsFollow : MapLockEvent

    data object EnableLiveFit : MapLockEvent

    data object DisableAll : MapLockEvent

    data object DisableTrackerFollow : MapLockEvent

    data object DisableGpsFollow : MapLockEvent

    data object DisableLiveFit : MapLockEvent

    data object ManualCameraInteraction : MapLockEvent
}

object MapLockReducer {
    fun reduce(current: MapLockState, event: MapLockEvent): MapLockState {
        return when (event) {
            is MapLockEvent.EnableTrackerFollow -> {
                MapLockState.TrackerFollow(
                    target = event.target,
                    needsInitialZoom = event.needsInitialZoom
                )
            }

            is MapLockEvent.RecenterTrackerFollow -> when (current) {
                is MapLockState.TrackerFollow -> current.copy(target = event.target)
                is MapLockState.TrackerFollowPending -> MapLockState.TrackerFollow(
                    target = event.target,
                    needsInitialZoom = current.needsInitialZoom
                )
                else -> current
            }

            is MapLockEvent.CompleteTrackerInitialZoom -> {
                if (!event.reachedTargetZoom) return current
                when (current) {
                    is MapLockState.TrackerFollow -> current.copy(needsInitialZoom = false)
                    is MapLockState.TrackerFollowPending -> current.copy(needsInitialZoom = false)
                    else -> current
                }
            }

            MapLockEvent.EnableGpsFollow -> MapLockState.GpsFollow

            MapLockEvent.EnableLiveFit -> MapLockState.LiveFit

            MapLockEvent.DisableAll -> MapLockState.None

            MapLockEvent.DisableTrackerFollow -> {
                if (current.mode == MapLockMode.TRACKER_FOLLOW) MapLockState.None else current
            }

            MapLockEvent.DisableGpsFollow -> {
                if (current is MapLockState.GpsFollow) MapLockState.None else current
            }

            MapLockEvent.DisableLiveFit -> {
                if (current is MapLockState.LiveFit) MapLockState.None else current
            }

            MapLockEvent.ManualCameraInteraction -> MapLockState.None
        }
    }
}

data class MapLockResumeInput(
    val lockState: MapLockState,
    val fallbackTrackPoint: LatLng?,
    val showMyLocationEnabled: Boolean,
    val liveActiveFitAvailable: Boolean
)

data class MapLockResumeDecision(
    val lockState: MapLockState,
    val followTarget: LatLng?,
    val shouldTrackGpsCamera: Boolean,
    val shouldApplyLiveFit: Boolean
)

object MapLockResumeResolver {
    fun resolve(input: MapLockResumeInput): MapLockResumeDecision {
        return when (val state = input.lockState) {
            MapLockState.None -> MapLockResumeDecision(
                lockState = MapLockState.None,
                followTarget = null,
                shouldTrackGpsCamera = false,
                shouldApplyLiveFit = false
            )

            is MapLockState.TrackerFollow -> {
                val target = input.fallbackTrackPoint ?: state.target
                MapLockResumeDecision(
                    lockState = state.copy(target = target),
                    followTarget = target,
                    shouldTrackGpsCamera = false,
                    shouldApplyLiveFit = false
                )
            }
            is MapLockState.TrackerFollowPending -> {
                val target = input.fallbackTrackPoint
                if (target == null) {
                    MapLockResumeDecision(
                        lockState = state,
                        followTarget = null,
                        shouldTrackGpsCamera = false,
                        shouldApplyLiveFit = false
                    )
                } else {
                    MapLockResumeDecision(
                        lockState = MapLockState.TrackerFollow(
                            target = target,
                            needsInitialZoom = state.needsInitialZoom
                        ),
                        followTarget = target,
                        shouldTrackGpsCamera = false,
                        shouldApplyLiveFit = false
                    )
                }
            }

            MapLockState.GpsFollow -> {
                if (!input.showMyLocationEnabled) {
                    MapLockResumeDecision(
                        lockState = MapLockState.None,
                        followTarget = null,
                        shouldTrackGpsCamera = false,
                        shouldApplyLiveFit = false
                    )
                } else {
                    MapLockResumeDecision(
                        lockState = MapLockState.GpsFollow,
                        followTarget = null,
                        shouldTrackGpsCamera = true,
                        shouldApplyLiveFit = false
                    )
                }
            }

            MapLockState.LiveFit -> {
                if (!input.liveActiveFitAvailable) {
                    MapLockResumeDecision(
                        // Keep lock intent sticky across lifecycle/data timing gaps.
                        // The camera fit is deferred until live-fit becomes available again.
                        lockState = MapLockState.LiveFit,
                        followTarget = null,
                        shouldTrackGpsCamera = false,
                        shouldApplyLiveFit = false
                    )
                } else {
                    MapLockResumeDecision(
                        lockState = MapLockState.LiveFit,
                        followTarget = null,
                        shouldTrackGpsCamera = false,
                        shouldApplyLiveFit = true
                    )
                }
            }
        }
    }
}

