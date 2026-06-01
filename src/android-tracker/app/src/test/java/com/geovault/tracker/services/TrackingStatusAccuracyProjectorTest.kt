package com.geovault.tracker.services
import com.geovault.tracker.positioning.config.GpsRuntimeState

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingStatusAccuracyProjectorTest {
    @Test
    fun project_lockingUsesCurrentFixForStatusAndDisplayAccuracy() {
        val projection = TrackingStatusAccuracyProjector.project(
            TrackingStatusAccuracyInput(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsState = GpsRuntimeState.RUNNING,
                lastAccuracyMeters = 8f,
                currentFixAccuracyMeters = 85f,
                effectiveAccuracyThresholdMeters = 50f,
                activeAccuracyBlockedEmission = false,
            )
        )

        assertEquals(TrackingUiStatus.LOCKING, projection.uiStatus)
        assertEquals(85f, projection.statusAccuracyMeters)
        assertEquals(85f, projection.displayAccuracyMeters)
    }

    @Test
    fun project_activeUsesCurrentFixForStatusButDisplaysHeldLastGoodAccuracy() {
        val projection = TrackingStatusAccuracyProjector.project(
            TrackingStatusAccuracyInput(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsState = GpsRuntimeState.RUNNING,
                lastAccuracyMeters = 8f,
                currentFixAccuracyMeters = 12f,
                effectiveAccuracyThresholdMeters = 50f,
                activeAccuracyBlockedEmission = false,
            )
        )

        assertEquals(TrackingUiStatus.TRACKING_ACTIVE, projection.uiStatus)
        assertEquals(12f, projection.statusAccuracyMeters)
        assertEquals(8f, projection.displayAccuracyMeters)
    }

    @Test
    fun displayAccuracy_lockingWithoutCurrentFixFallsBackToLastAccuracy() {
        assertEquals(
            8f,
            TrackingStatusAccuracyProjector.displayAccuracy(
                uiStatus = TrackingUiStatus.LOCKING,
                lastAccuracyMeters = 8f,
                currentFixAccuracyMeters = null,
            )
        )
    }
}
