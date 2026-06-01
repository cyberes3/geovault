package com.geovault.tracker

import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.tracking.TrackingServiceIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceProcessRestartTest {
    @Test
    fun nullIntentAction_mapsToStopNoRestart_notStartTracking() {
        assertFalse(TrackingServiceIntents.shouldRestartTrackingAfterProcessDeath())
        val path = TrackingServiceIntents.resolveStartupCommandPath(action = null)
        assertFalse(TrackingServiceIntents.requiresForegroundPromotion(path))
    }

    @Test
    fun explicitStartAction_requiresForegroundPromotion() {
        val path = TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_START)
        assertTrue(TrackingServiceIntents.requiresForegroundPromotion(path))
    }

    @Test
    fun locationUpdateAction_doesNotRequireForegroundPromotion() {
        val path = TrackingServiceIntents.resolveStartupCommandPath(TrackingServiceIntents.ACTION_LOCATION_UPDATE)
        assertFalse(TrackingServiceIntents.requiresForegroundPromotion(path))
    }

    @Test
    fun runtimeTriggerMapping_mapsProcessRestartAndWatchdogTick() {
        assertEquals(RuntimeTrigger.PROCESS_RESTART, TrackingServiceIntents.mapRuntimeTrigger("process_restart"))
        assertEquals(RuntimeTrigger.WATCHDOG_TICK, TrackingServiceIntents.mapRuntimeTrigger("watchdog_tick"))
    }
}
