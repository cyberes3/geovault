package com.geovault.tracker.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingLocationAvailabilityPolicyTest {
    @Test
    fun canStartTracking_requiresPreciseBackgroundNotificationActivityRecognitionAndLocationServices() {
        val ready = TrackingLocationAvailabilityInput(
            hasFineLocationPermission = true,
            hasBackgroundLocationPermission = true,
            hasNotificationPermission = true,
            hasActivityRecognitionPermission = true,
            locationServicesEnabled = true,
        )

        assertTrue(TrackingLocationAvailabilityPolicy.canStartTracking(ready))
        assertFalse(
            TrackingLocationAvailabilityPolicy.canStartTracking(
                ready.copy(hasFineLocationPermission = false)
            )
        )
        assertFalse(
            TrackingLocationAvailabilityPolicy.canStartTracking(
                ready.copy(hasActivityRecognitionPermission = false)
            )
        )
        assertFalse(
            TrackingLocationAvailabilityPolicy.canStartTracking(
                ready.copy(locationServicesEnabled = false)
            )
        )
    }

    @Test
    fun canRequestTrackingLocationUpdates_requiresPreciseLocation() {
        assertTrue(TrackingLocationAvailabilityPolicy.canRequestTrackingLocationUpdates(true))
        assertFalse(TrackingLocationAvailabilityPolicy.canRequestTrackingLocationUpdates(false))
    }
}
