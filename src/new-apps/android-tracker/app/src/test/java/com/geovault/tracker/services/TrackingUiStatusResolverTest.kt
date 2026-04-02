package com.geovault.tracker.services

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingUiStatusResolverTest {

    @Test
    fun resolve_notRunning_returnsNotTracking() {
        assertEquals(
            TrackingUiStatus.NOT_TRACKING,
            TrackingUiStatusResolver.resolve(
                isRunning = false,
                gpsProviderEnabled = true,
                gpsPaused = false,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolve_providerDisabled_returnsWaitingForGps() {
        assertEquals(
            TrackingUiStatus.WAITING_FOR_GPS,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = false,
                gpsPaused = false,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }

    @Test
    fun resolve_badFix_returnsLocking() {
        assertEquals(
            TrackingUiStatus.LOCKING,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsPaused = false,
                lastAccuracyMeters = 100f,
                effectiveAccuracyThresholdMeters = 10f
            )
        )
    }
}
