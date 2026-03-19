package com.geovault.tracker.location

import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTrackingMotionEngineTest {

    @Test
    fun reset_startsInWalkingWithZeroSpeed() {
        val engine = AutoTrackingMotionEngine()

        val output = engine.reset(nowMs = 10_000L)

        assertEquals(TrackingMotionMode.WALKING, output.state.mode)
        assertEquals(0f, output.state.smoothedSpeedMps)
        assertEquals(10_000L, output.state.lastEvidenceAtMs)
    }

    @Test
    fun acceptedFixes_supportEscalationAndDowngrade() {
        val engine = AutoTrackingMotionEngine()
        var nowMs = 0L
        engine.reset(nowMs)

        repeat(12) {
            nowMs += 1_000L
            engine.onAcceptedFix(speedMps = 21f, eventTimeMs = nowMs)
        }
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        repeat(16) {
            nowMs += 1_000L
            engine.onAcceptedFix(speedMps = 0f, eventTimeMs = nowMs)
        }
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
    }

    @Test
    fun periodicTick_decaysSpeedWithoutNewFixes() {
        val engine = AutoTrackingMotionEngine()
        var nowMs = 0L
        engine.reset(nowMs)

        repeat(12) {
            nowMs += 1_000L
            engine.onAcceptedFix(speedMps = 25f, eventTimeMs = nowMs)
        }
        assertEquals(TrackingMotionMode.DRIVING, engine.snapshot().mode)

        val beforeDecay = engine.snapshot().smoothedSpeedMps
        nowMs += 3 * 60_000L
        engine.onTick(nowMs)
        engine.onTick(nowMs)

        assertTrue(engine.snapshot().smoothedSpeedMps < beforeDecay)
        assertEquals(TrackingMotionMode.WALKING, engine.snapshot().mode)
    }

    @Test
    fun rejectedFixes_haveWeakButNonZeroInfluence() {
        val engine = AutoTrackingMotionEngine()
        var nowMs = 0L
        engine.reset(nowMs)

        repeat(6) {
            nowMs += 1_000L
            engine.onAcceptedFix(speedMps = 6f, eventTimeMs = nowMs)
        }
        val prior = engine.snapshot().smoothedSpeedMps

        engine.onRejectedFix(speedMpsHint = 0f, eventTimeMs = nowMs + 1_000L)

        assertTrue(engine.snapshot().smoothedSpeedMps < prior)
    }
}
