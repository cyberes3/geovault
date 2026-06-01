package com.geovault.tracker.positioning

import com.geovault.tracker.services.TrackingUiStatus
import com.geovault.tracker.services.TrackingUiStatusResolver
import org.junit.Assert.assertEquals
import org.junit.Test

/** UI status strings derived from runtime snapshot fields projected by {@code RuntimeProjectionSubsystem}. */
class PositioningStatusProjectionCharacterizationTest {

    @Test
    fun statusResolver_mapsPausedMotionWhileRunning() {
        assertEquals(
            TrackingUiStatus.PAUSED_FOR_MOTION,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsPaused = true,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 20f,
            ),
        )
    }

    @Test
    fun statusResolver_mapsGoodFixWhileRunning() {
        assertEquals(
            TrackingUiStatus.TRACKING_ACTIVE,
            TrackingUiStatusResolver.resolve(
                isRunning = true,
                gpsProviderEnabled = true,
                gpsPaused = false,
                lastAccuracyMeters = 5f,
                effectiveAccuracyThresholdMeters = 20f,
            ),
        )
    }
}
