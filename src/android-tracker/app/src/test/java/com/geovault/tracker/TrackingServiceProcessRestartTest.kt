package com.geovault.tracker

import com.geovault.tracker.runtime.RuntimeTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test
    fun locationUpdateAction_doesNotRequireForegroundPromotion() {
        val path = TrackingService.resolveStartupCommandPath(TrackingService.ACTION_LOCATION_UPDATE)
        assertFalse(TrackingService.requiresForegroundPromotion(path))
    }

    @Test
    fun runtimeTriggerMapping_mapsProcessRestartAndWatchdogTick() {
        assertEquals(RuntimeTrigger.PROCESS_RESTART, invokeMapRuntimeTrigger("process_restart"))
        assertEquals(RuntimeTrigger.WATCHDOG_TICK, invokeMapRuntimeTrigger("watchdog_tick"))
    }

    private fun invokeMapRuntimeTrigger(trigger: String): RuntimeTrigger {
        val service = TrackingService()
        val method = service.javaClass.getDeclaredMethod("mapRuntimeTrigger", String::class.java)
        method.isAccessible = true
        return method.invoke(service, trigger) as RuntimeTrigger
    }
}
