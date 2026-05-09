package com.geovault.tracker.presentation

import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionInput
import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionPolicy

data class TrackerMapUserLocationInput(
    val isMapActive: Boolean,
    val hasLocationPermission: Boolean,
    val isMapReady: Boolean,
    val userLocationRequestedThisSession: Boolean,
    val runtimeRunning: Boolean,
)

enum class TrackerMapUserLocationBlocker {
    MapInactive,
    MissingPermission,
    MapNotReady,
    LocationNotRequestedThisSession,
    RuntimeTrackingActive,
}

data class TrackerMapUserLocationDecision(
    val shouldStreamGps: Boolean,
    val shouldEnablePuck: Boolean,
    val blockers: Set<TrackerMapUserLocationBlocker>,
)

/**
 * Central authority for map user-location behavior.
 *
 * Design goal: location streaming must be impossible unless the user has explicitly
 * requested the live GPS puck in this session. This prevents launch-time auto activation.
 */
class TrackerMapUserLocationPolicy(
    private val commonPolicy: GeoVaultMapLocationSessionPolicy = GeoVaultMapLocationSessionPolicy(),
) {
    fun evaluate(input: TrackerMapUserLocationInput): TrackerMapUserLocationDecision {
        val blockers = linkedSetOf<TrackerMapUserLocationBlocker>()
        if (!input.isMapActive) blockers += TrackerMapUserLocationBlocker.MapInactive
        if (!input.hasLocationPermission) blockers += TrackerMapUserLocationBlocker.MissingPermission
        if (!input.isMapReady) blockers += TrackerMapUserLocationBlocker.MapNotReady
        if (!input.userLocationRequestedThisSession) {
            blockers += TrackerMapUserLocationBlocker.LocationNotRequestedThisSession
        }
        if (!TrackerMapCameraLockPolicy.shouldRenderUserLocation(input.runtimeRunning)) {
            blockers += TrackerMapUserLocationBlocker.RuntimeTrackingActive
        }
        val commonDecision = commonPolicy.decide(
            GeoVaultMapLocationSessionInput(
                isActive = input.isMapActive,
                hasLocationPermission = input.hasLocationPermission,
                isMapReady = input.isMapReady,
                userLocationRequested = input.userLocationRequestedThisSession,
                positionFollowDesired = false,
                headingFollowDesired = false,
            ),
        )
        val allowPuck = blockers.isEmpty() && commonDecision.shouldEnablePuck
        return TrackerMapUserLocationDecision(
            shouldStreamGps = allowPuck,
            shouldEnablePuck = allowPuck,
            blockers = blockers,
        )
    }
}
