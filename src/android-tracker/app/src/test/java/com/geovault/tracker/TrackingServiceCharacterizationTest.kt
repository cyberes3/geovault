package com.geovault.tracker

import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.LocationRequestController
import com.geovault.tracker.tracking.TrackingServiceIntents
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackingServiceCharacterizationTest {
    @Test
    fun startupCommandRouting_preservesServiceEntrySemantics() {
        assertTrue(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_START)
            )
        )
        assertFalse(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_LOCATION_UPDATE)
            )
        )
        assertFalse(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.resolveStartupCommandPath(null)
            )
        )
    }

    @Test
    fun expectsActiveFixDelivery_onlyForCollectingStates() {
        assertTrue(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = true,
                gpsRuntimeState = GpsRuntimeState.RUNNING,
            )
        )
        assertFalse(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = true,
                gpsRuntimeState = GpsRuntimeState.PAUSED_FOR_MOTION,
            )
        )
        assertFalse(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = true,
                gpsRuntimeState = GpsRuntimeState.WAITING_FOR_PROVIDER,
            )
        )
        assertFalse(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = false,
                gpsRuntimeState = GpsRuntimeState.RUNNING,
            )
        )
    }
}
