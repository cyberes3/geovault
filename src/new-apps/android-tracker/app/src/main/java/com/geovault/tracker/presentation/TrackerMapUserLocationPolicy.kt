package com.geovault.tracker.presentation

data class TrackerMapUserLocationInput(
    val isMapActive: Boolean,
    val hasLocationPermission: Boolean,
    val isMapReady: Boolean,
    val userFollowLockArmedThisSession: Boolean,
    val followLockEnabled: Boolean,
    val runtimeRunning: Boolean,
)

enum class TrackerMapUserLocationBlocker {
    MapInactive,
    MissingPermission,
    MapNotReady,
    FollowLockNotArmedThisSession,
    FollowLockDisabled,
    RuntimeTrackingActive,
}

data class TrackerMapUserLocationDecision(
    val shouldStreamGps: Boolean,
    val shouldEnablePuck: Boolean,
    val shouldEnableFollowCamera: Boolean,
    val blockers: Set<TrackerMapUserLocationBlocker>,
)

/**
 * Central authority for map user-location behavior.
 *
 * Design goal: location streaming must be impossible unless the user has explicitly
 * armed follow-lock in this session. This prevents launch-time auto activation.
 */
class TrackerMapUserLocationPolicy {
    fun evaluate(input: TrackerMapUserLocationInput): TrackerMapUserLocationDecision {
        val blockers = linkedSetOf<TrackerMapUserLocationBlocker>()
        if (!input.isMapActive) blockers += TrackerMapUserLocationBlocker.MapInactive
        if (!input.hasLocationPermission) blockers += TrackerMapUserLocationBlocker.MissingPermission
        if (!input.isMapReady) blockers += TrackerMapUserLocationBlocker.MapNotReady
        if (!input.userFollowLockArmedThisSession) {
            blockers += TrackerMapUserLocationBlocker.FollowLockNotArmedThisSession
        }
        if (!input.followLockEnabled) blockers += TrackerMapUserLocationBlocker.FollowLockDisabled
        if (!TrackerMapCameraLockPolicy.shouldRenderUserLocation(input.runtimeRunning)) {
            blockers += TrackerMapUserLocationBlocker.RuntimeTrackingActive
        }
        val allowPuck = blockers.isEmpty()
        return TrackerMapUserLocationDecision(
            shouldStreamGps = allowPuck,
            shouldEnablePuck = allowPuck,
            shouldEnableFollowCamera = allowPuck &&
                TrackerMapCameraLockPolicy.shouldEnableFollowCamera(
                    runtimeRunning = input.runtimeRunning,
                    followLockEnabled = input.followLockEnabled
                ),
            blockers = blockers,
        )
    }
}
