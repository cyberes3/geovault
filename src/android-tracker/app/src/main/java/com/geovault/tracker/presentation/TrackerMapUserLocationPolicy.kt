package com.geovault.tracker.presentation

import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionDecision
import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionInput
import com.geovault.common.maps.ui.location.GeoVaultMapLocationSessionPolicy

data class TrackerMapUserLocationInput(
    val isMapActive: Boolean,
    val hasLocationPermission: Boolean,
    val isMapReady: Boolean,
    val userLocationRequestedThisSession: Boolean,
    val displayedTrackerId: String = "",
    val locallyRecordedTrackerId: String = "",
)

enum class TrackerMapUserLocationBlocker {
    MapInactive,
    MissingPermission,
    MapNotReady,
    LocationNotRequestedThisSession,
    OwnRecordedTrackerOnScreen,
}

data class TrackerMapUserLocationDecision(
    val shouldStreamGps: Boolean,
    val shouldEnablePuck: Boolean,
    val blockers: Set<TrackerMapUserLocationBlocker>,
)

/**
 * Tracker overlay on [GeoVaultMapLocationSessionPolicy].
 *
 * Streaming follows the common background-stream rule. The puck is hidden only when the
 * displayed tracker is the one being recorded, so the MapLibre chevron does not sit on top of
 * the tracker marker.
 */
class TrackerMapUserLocationPolicy(
    private val commonPolicy: GeoVaultMapLocationSessionPolicy = GeoVaultMapLocationSessionPolicy(),
) {
    fun evaluate(
        input: TrackerMapUserLocationInput,
        commonDecision: GeoVaultMapLocationSessionDecision? = null,
    ): TrackerMapUserLocationDecision {
        val displayedId = input.displayedTrackerId.trim()
        val recordedId = input.locallyRecordedTrackerId.trim()
        val ownRecordedTrackerOnScreen = recordedId.isNotEmpty() && displayedId == recordedId
        val blockers = linkedSetOf<TrackerMapUserLocationBlocker>()
        if (!input.isMapActive) blockers += TrackerMapUserLocationBlocker.MapInactive
        if (!input.hasLocationPermission) blockers += TrackerMapUserLocationBlocker.MissingPermission
        if (!input.isMapReady) blockers += TrackerMapUserLocationBlocker.MapNotReady
        if (!input.userLocationRequestedThisSession) {
            blockers += TrackerMapUserLocationBlocker.LocationNotRequestedThisSession
        }
        if (ownRecordedTrackerOnScreen) {
            blockers += TrackerMapUserLocationBlocker.OwnRecordedTrackerOnScreen
        }
        val resolvedCommon = commonDecision ?: commonPolicy.decide(
            GeoVaultMapLocationSessionInput(
                isActive = input.isMapActive,
                hasLocationPermission = input.hasLocationPermission,
                isMapReady = input.isMapReady,
                userLocationRequested = input.userLocationRequestedThisSession,
                positionFollowDesired = false,
                headingFollowDesired = false,
            ),
        )
        return TrackerMapUserLocationDecision(
            shouldStreamGps = resolvedCommon.shouldStreamGps && !ownRecordedTrackerOnScreen,
            shouldEnablePuck = resolvedCommon.shouldEnablePuck && !ownRecordedTrackerOnScreen,
            blockers = blockers,
        )
    }
}
