package com.geovault.tracker.location

import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrackingMotionEngineTest {
    @Test
    fun acceptedFix_highSpeed_promotesMode() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 1_000L)
        val output = engine.onAcceptedFix(speedMps = 11f, eventTimeMs = 2_000L)
        assertTrue(output.state.mode == TrackingMotionMode.BIKING || output.state.mode == TrackingMotionMode.DRIVING)
    }

    @Test
    fun pauseResume_keepsMode_and_updatesPauseFlag() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 1_000L)
        val paused = engine.onGpsPaused(nowMs = 2_000L)
        assertTrue(paused.state.isGpsPaused)
        val resumed = engine.onGpsResumed(nowMs = 3_000L)
        assertFalse(resumed.state.isGpsPaused)
        assertEquals(paused.state.mode, resumed.state.mode)
    }

    @Test
    fun periodicTick_decays_speed() {
        val engine = AutoTrackingMotionEngine()
        engine.reset(nowMs = 0L)
        engine.onAcceptedFix(speedMps = 8f, eventTimeMs = 1_000L)
        val before = engine.snapshot().smoothedSpeedMps
        engine.onTick(nowMs = 200_000L)
        val after = engine.snapshot().smoothedSpeedMps
        assertTrue(after <= before)
    }
}
