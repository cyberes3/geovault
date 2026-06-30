package com.geovault.tracker

import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.LocationRequestController
import com.geovault.tracker.tracking.TrackingServiceIntents
import org.junit.Assert.assertEquals
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
    fun startupCommandRouting_mapsAllKnownActions() {
        assertEquals(
            TrackingServiceIntents.StartupCommandPath.StartTracking,
            TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_START),
        )
        assertEquals(
            TrackingServiceIntents.StartupCommandPath.StopUnknown,
            TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_STOP),
        )
        assertEquals(
            TrackingServiceIntents.StartupCommandPath.ReshowForeground,
            TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_RESHOW_FOREGROUND),
        )
        assertEquals(
            TrackingServiceIntents.StartupCommandPath.ManualSendPoint,
            TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_SEND_MANUAL_POINT),
        )
        assertEquals(
            TrackingServiceIntents.StartupCommandPath.LocationUpdate,
            TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_LOCATION_UPDATE),
        )
        assertEquals(
            TrackingServiceIntents.StartupCommandPath.StationaryPingDue,
            TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_STATIONARY_PING_DUE),
        )
        assertEquals(
            TrackingServiceIntents.StartupCommandPath.StopNoRestart,
            TrackingServiceIntents.resolveStartupCommandPath(null),
        )
    }

    @Test
    fun foregroundPromotion_withExplicitFlag_onlyForLocationUpdate() {
        assertTrue(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.StartupCommandPath.LocationUpdate,
                foregroundStartRequired = true,
            ),
        )
        assertFalse(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.StartupCommandPath.ManualSendPoint,
                foregroundStartRequired = true,
            ),
        )
        assertFalse(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.StartupCommandPath.StationaryPingDue,
                foregroundStartRequired = true,
            ),
        )
    }

    @Test
    fun stationaryPingDue_neverRequiresForegroundPromotion() {
        // The wake-guaranteed alarm only fires while a tracking session (and its foreground
        // notification) is already alive; it must never itself trigger a foreground promotion.
        assertFalse(
            TrackingServiceIntents.requiresForegroundPromotion(
                TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_STATIONARY_PING_DUE)
            )
        )
        assertEquals(
            "stationary_ping_alarm",
            TrackingServiceIntents.resolveStartupTrigger(TrackingServiceIntents.ACTION_STATIONARY_PING_DUE),
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
        assertTrue(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = true,
                gpsRuntimeState = GpsRuntimeState.FALLBACK_PENDING,
            ),
        )
        assertFalse(
            LocationRequestController.expectsActiveFixDelivery(
                isTracking = false,
                gpsRuntimeState = GpsRuntimeState.RUNNING,
            )
        )
    }
}
