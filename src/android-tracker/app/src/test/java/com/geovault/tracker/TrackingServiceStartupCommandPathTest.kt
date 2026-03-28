package com.geovault.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceStartupCommandPathTest {
    @Test
    fun resolveStartupCommandPath_mapsExplicitActions() {
        assertEquals(
            TrackingService.Companion.StartupCommandPath.StartTracking,
            TrackingService.resolveStartupCommandPath(
                action = TrackingService.ACTION_START
            )
        )
        assertEquals(
            TrackingService.Companion.StartupCommandPath.StopUnknown,
            TrackingService.resolveStartupCommandPath(
                action = TrackingService.ACTION_STOP
            )
        )
        assertEquals(
            TrackingService.Companion.StartupCommandPath.ReshowForeground,
            TrackingService.resolveStartupCommandPath(
                action = TrackingService.ACTION_RESHOW_FOREGROUND
            )
        )
    }

    @Test
    fun resolveStartupCommandPath_mapsNullActionToStopNoRestart() {
        assertEquals(
            TrackingService.Companion.StartupCommandPath.StopNoRestart,
            TrackingService.resolveStartupCommandPath(
                action = null
            )
        )
    }

    @Test
    fun requiresForegroundPromotion_onlyForStartTracking() {
        assertTrue(TrackingService.requiresForegroundPromotion(TrackingService.Companion.StartupCommandPath.StartTracking))
        assertFalse(TrackingService.requiresForegroundPromotion(TrackingService.Companion.StartupCommandPath.StopNoRestart))
        assertFalse(TrackingService.requiresForegroundPromotion(TrackingService.Companion.StartupCommandPath.ReshowForeground))
        assertFalse(TrackingService.requiresForegroundPromotion(TrackingService.Companion.StartupCommandPath.StopUnknown))
    }

    @Test
    fun resolveStartupTrigger_mapsExpectedSources() {
        assertEquals("explicit_start", TrackingService.resolveStartupTrigger(TrackingService.ACTION_START))
        assertEquals("explicit_stop", TrackingService.resolveStartupTrigger(TrackingService.ACTION_STOP))
        assertEquals("reshow_foreground", TrackingService.resolveStartupTrigger(TrackingService.ACTION_RESHOW_FOREGROUND))
        assertEquals("process_restart", TrackingService.resolveStartupTrigger(null))
        assertEquals("unknown_action", TrackingService.resolveStartupTrigger("com.geovault.tracker.ACTION_CUSTOM"))
    }
}
