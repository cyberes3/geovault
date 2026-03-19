package com.geovault.tracker

import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrackingFlowRobustnessTest {

    @Test
    fun driveStopWalk_sequenceDowngradesThroughAllModes() {
        val engine = AutoTrackingMotionEngine()
        var nowMs = 0L
        engine.reset(nowMs)

        repeat(12) {
            nowMs += 1_000L
            engine.onAcceptedFix(speedMps = 22f, eventTimeMs = nowMs)
        }
        assertTrue(engine.snapshot().mode == TrackingMotionMode.DRIVING)

        repeat(10) {
            nowMs += 1_000L
            engine.onAcceptedFix(speedMps = 0.4f, eventTimeMs = nowMs)
        }
        assertTrue(engine.snapshot().mode == TrackingMotionMode.WALKING)
    }

    @Test
    fun rejectedFixesAndDecay_canDropDrivingWithoutAcceptedFixes() {
        val engine = AutoTrackingMotionEngine()
        var nowMs = 0L
        engine.reset(nowMs)

        repeat(12) {
            nowMs += 1_000L
            engine.onAcceptedFix(speedMps = 20f, eventTimeMs = nowMs)
        }
        assertTrue(engine.snapshot().mode == TrackingMotionMode.DRIVING)

        repeat(15) {
            nowMs += 2_000L
            engine.onRejectedFix(speedMpsHint = 0f, eventTimeMs = nowMs)
        }

        // No new accepted fixes; decay ticks continue to degrade mode.
        nowMs += 5 * 60_000L
        engine.onTick(nowMs)
        engine.onTick(nowMs)

        assertTrue(engine.snapshot().mode == TrackingMotionMode.WALKING)
    }

    @Test
    fun gpsPauseStillAllowsDecayDrivenDowngrade() {
        val engine = AutoTrackingMotionEngine()
        var nowMs = 0L
        engine.reset(nowMs)

        repeat(12) {
            nowMs += 1_000L
            engine.onAcceptedFix(speedMps = 18f, eventTimeMs = nowMs)
        }
        assertTrue(engine.snapshot().mode == TrackingMotionMode.DRIVING)

        engine.onGpsPaused(nowMs)
        nowMs += 4 * 60_000L
        engine.onTick(nowMs)
        engine.onTick(nowMs)
        assertTrue(engine.snapshot().mode == TrackingMotionMode.WALKING)
    }
}
