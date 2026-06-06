package com.geovault.tracker.location

data class TrackingLocationAvailabilityInput(
    val hasFineLocationPermission: Boolean,
    val hasBackgroundLocationPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val locationServicesEnabled: Boolean,
)

object TrackingLocationAvailabilityPolicy {
    fun canStartTracking(input: TrackingLocationAvailabilityInput): Boolean {
        return input.hasFineLocationPermission &&
            input.hasBackgroundLocationPermission &&
            input.hasNotificationPermission &&
            input.locationServicesEnabled
    }

    fun canRequestTrackingLocationUpdates(hasFineLocationPermission: Boolean): Boolean {
        return hasFineLocationPermission
    }
}
