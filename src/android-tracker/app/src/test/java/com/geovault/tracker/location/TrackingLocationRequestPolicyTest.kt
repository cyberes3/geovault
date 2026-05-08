package com.geovault.tracker.location

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackingLocationRequestPolicyTest {
    @Test
    fun buildNormalRequest_mapsIntervalDistancePriorityAndDoesNotDeferBeyondInterval() {
        val request = TrackingLocationRequestPolicy.buildNormalRequest(
            TrackingLocationRequestInput(
                intervalSec = 20L,
                distanceFilterMeters = 7f,
                priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            )
        )

        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
        assertEquals(20_000L, request.intervalMillis)
        assertEquals(10_000L, request.minUpdateIntervalMillis)
        assertEquals(20_000L, request.maxUpdateDelayMillis)
        assertEquals(7f, request.minUpdateDistanceMeters, 0.001f)
    }

    @Test
    fun buildFastLockRequest_isHighAccuracyAndDoesNotWaitForAccurateLocation() {
        val request = TrackingLocationRequestPolicy.buildFastLockRequest()

        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, request.priority)
        assertEquals(0L, request.intervalMillis)
        assertEquals(0L, request.minUpdateIntervalMillis)
        assertEquals(0f, request.minUpdateDistanceMeters, 0.001f)
        assertFalse(request.isWaitForAccurateLocation)
    }
}
