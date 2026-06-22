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

    /**
     * When in LOCKING with no current fix (e.g. GPS just resumed from a freshness probe),
     * display accuracy must be null rather than the stale pre-pause value. Showing the old
     * track-point accuracy (e.g. 69 ft) while searching is misleading because it implies an
     * active good fix when the device is actually re-acquiring.
     */
    @Test
    fun displayAccuracy_lockingWithoutCurrentFix_returnsNull() {
        assertEquals(
            null,
            TrackingStatusAccuracyProjector.displayAccuracy(
                uiStatus = TrackingUiStatus.LOCKING,
                lastAccuracyMeters = 21f,  // stale pre-pause value
                currentFixAccuracyMeters = null,
            )
        )
    }

    @Test
    fun displayAccuracy_lockingWithCurrentFix_returnsCurrentFix() {
        assertEquals(
            85f,
            TrackingStatusAccuracyProjector.displayAccuracy(
                uiStatus = TrackingUiStatus.LOCKING,
                lastAccuracyMeters = 8f,
                currentFixAccuracyMeters = 85f,
            )
        )
    }
}
