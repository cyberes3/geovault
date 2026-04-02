package com.geovault.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceProcessRestartTest {

    @Test
    fun nullIntentAction_mapsToStopNoRestart_notStartTracking() {
        assertFalse(TrackingService.shouldRestartTrackingAfterProcessDeath())
        val path = TrackingService.resolveStartupCommandPath(action = null)
        assertFalse(TrackingService.requiresForegroundPromotion(path))
    }

    @Test
    fun explicitStartAction_requiresForegroundPromotion() {
        val path = TrackingService.resolveStartupCommandPath(TrackingService.ACTION_START)
        assertTrue(TrackingService.requiresForegroundPromotion(path))
    }
}
