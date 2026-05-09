package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryConfidenceCalculatorTest {

    @Test
    fun bufferTooSmall_returnsNone() {
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 2,
                reportedSpeedMps = 0.0,
            )
        )
        assertEquals(StationaryConfidence.NONE, result)
    }

    @Test
    fun effectiveAboveOneMeter_returnsNone() {
        // Mirrors `tslocationmanager`: once RSS-corrected motion exceeds
        // 1 m, the chipset is reporting real displacement and we must
        // not classify the fix as stationary.
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 6,
                reportedSpeedMps = 0.0,
                effectiveDistanceMeters = 1.6,
            )
        )
        assertEquals(StationaryConfidence.NONE, result)
    }

    @Test
    fun steadyStandstill_isStationary_notOscillating() {
        // Speed<0.5 (+0.4) + speedStable>0.7 (+0.2) + rawClose (+0.15)
        // + lowJerk (+0.10) = 0.85.
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 6,
                reportedSpeedMps = 0.0,
                bearingStability = 1.0,
                speedStability = 1.0,
                jerk = 0.0,
                accuracyMeters = 5.0,
                rawDistanceMeters = 0.0,
            )
        )
        assertEquals(0.85, result.score, 1e-9)
        assertTrue(result.isStationary)
        assertFalse(result.isOscillating)
    }

    @Test
    fun phantomStepWithZeroSpeed_isClassifiedStationary() {
        // 38 m raw step, 30 m accuracy, reported speed 0: the field
        // failure case. Even with bearingStability=0.8 (no noisy
        // bonus), the speed/stability/rawClose/lowJerk bonuses still
        // sum to 0.85 -- comfortably above the 0.6 threshold.
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 4,
                reportedSpeedMps = 0.0,
                bearingStability = 0.8,
                speedStability = 1.0,
                jerk = 0.0,
                accuracyMeters = 30.0,
                rawDistanceMeters = 38.0,
            )
        )
        assertTrue("score=${result.score}", result.isStationary)
    }

    @Test
    fun cleanWalkingFix_isNotStationary() {
        // speed=1.4 -> speedZero false (no +0.4/0.3). speedStability
        // bonus 0.18 + rawClose 0.15 + lowJerk 0.10 = 0.43, below 0.6.
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 8,
                reportedSpeedMps = 1.4,
                bearingStability = 0.9,
                speedStability = 0.9,
                jerk = 0.1,
                accuracyMeters = 5.0,
                rawDistanceMeters = 6.0,
            )
        )
        assertFalse("score=${result.score}", result.isStationary)
    }

    @Test
    fun rubberBandPattern_isOscillating() {
        // bearingNoisy + rawClose + speedZero all fire.
        // 0.4 + 0.2 + 0.135 + 0.15 = 0.885, well above 0.6.
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 8,
                reportedSpeedMps = 0.0,
                bearingStability = 0.1,
                speedStability = 1.0,
                jerk = 0.5,
                accuracyMeters = 50.0,
                rawDistanceMeters = 5.0,
            )
        )
        assertTrue(result.isStationary)
        assertTrue(result.isOscillating)
    }

    @Test
    fun oscillationOverride_marginalScore_forcesStationary() {
        // Score lands in the (0.5, 0.6) marginal band but the
        // bearingNoisy + rawClose + speedZero signature is unambiguous,
        // so the override flips isStationary to true.
        //
        // Bonuses:
        //  - speedZero, speed in [0.5, 1): +0.3
        //  - speedStability=0.0 (no bonus)
        //  - bearingNoisy, stability=0.25: +(1-0.25)*0.15 = 0.1125
        //  - rawClose: +0.15
        //  - lowJerk false (jerk=0.6): no bonus
        // Total: 0.5625 -- below 0.6 threshold but above oscillation
        // override floor of 0.5.
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 6,
                reportedSpeedMps = 0.6,
                bearingStability = 0.25,
                speedStability = 0.0,
                jerk = 0.6,
                accuracyMeters = 10.0,
                rawDistanceMeters = 5.0,
            )
        )
        assertEquals(0.5625, result.score, 1e-9)
        assertTrue(result.isOscillating)
        assertTrue("oscillation override must lift marginal score", result.isStationary)
    }

    private fun input(
        bufferCount: Int,
        reportedSpeedMps: Double = 0.0,
        bearingStability: Double = 1.0,
        speedStability: Double = 1.0,
        jerk: Double = 0.0,
        accuracyMeters: Double = 5.0,
        rawDistanceMeters: Double = 0.0,
        effectiveDistanceMeters: Double = 0.0,
    ) = StationaryConfidenceCalculator.Input(
        reportedSpeedMps = reportedSpeedMps,
        bearingStability = bearingStability,
        speedStability = speedStability,
        jerk = jerk,
        accuracyMeters = accuracyMeters,
        rawDistanceMeters = rawDistanceMeters,
        effectiveDistanceMeters = effectiveDistanceMeters,
        bufferCount = bufferCount,
    )
}
