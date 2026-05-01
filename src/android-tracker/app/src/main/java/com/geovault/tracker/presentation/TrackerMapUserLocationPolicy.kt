package com.geovault.tracker.presentation

import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionInput
import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionPolicy

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
class TrackerMapUserLocationPolicy(
    private val commonPolicy: GeoVaultMapLocationSessionPolicy = GeoVaultMapLocationSessionPolicy(),
) {
    fun evaluate(input: TrackerMapUserLocationInput): TrackerMapUserLocationDecision {
        val blockers = linkedSetOf<TrackerMapUserLocationBlocker>()
        if (!input.isMapActive) blockers += TrackerMapUserLocationBlocker.MapInactive
        if (!input.hasLocationPermission) blockers += TrackerMapUserLocationBlocker.MissingPermission
        if (!input.isMapReady) blockers += TrackerMapUserLocationBlocker.MapNotReady
        if (!input.userFollowLockArmedThisSession) {
            blockers += TrackerMapUserLocationBlocker.FollowLockNotArmedThisSession
        }
        if (!TrackerMapCameraLockPolicy.shouldRenderUserLocation(input.runtimeRunning)) {
            blockers += TrackerMapUserLocationBlocker.RuntimeTrackingActive
        }
        val commonDecision = commonPolicy.decide(
            GeoVaultMapLocationSessionInput(
                isActive = input.isMapActive,
                hasLocationPermission = input.hasLocationPermission,
                isMapReady = input.isMapReady,
                userLocationRequested = input.userFollowLockArmedThisSession,
                positionFollowDesired = false,
                headingFollowDesired = false,
            ),
        )
        val allowPuck = blockers.isEmpty() && commonDecision.shouldEnablePuck
        return TrackerMapUserLocationDecision(
            shouldStreamGps = allowPuck,
            shouldEnablePuck = allowPuck,
            shouldEnableFollowCamera = allowPuck &&
                input.followLockEnabled &&
                TrackerMapCameraLockPolicy.shouldEnableFollowCamera(
                    runtimeRunning = input.runtimeRunning,
                    followLockEnabled = input.followLockEnabled
                ),
            blockers = blockers,
        )
    }
}
