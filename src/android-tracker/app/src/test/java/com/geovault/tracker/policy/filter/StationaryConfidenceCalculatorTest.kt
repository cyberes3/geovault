package com.geovault.tracker.policy.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryConfidenceCalculatorTest {

    @Test
    fun firstFixInSession_returnsNone() {
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 0,
                reportedSpeedMps = 0.0,
                impliedSpeedMps = 0.0,
            )
        )
        assertEquals(StationaryConfidence.NONE, result)
    }

    @Test
    fun steadyStandstill_isStationary_notOscillating() {
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 6,
                reportedSpeedMps = 0.0,
                impliedSpeedMps = 0.0,
                bearingStability = 1.0,
                speedStability = 1.0,
                jerk = 0.0,
                accuracyMeters = 5.0,
                rawDistanceMeters = 0.0,
            )
        )
        assertTrue("score=${result.score}", result.isStationary)
        assertFalse(result.isOscillating)
    }

    @Test
    fun phantomStepWithZeroSpeed_isClassifiedStationary() {
        // 38 m raw step, 30 m accuracy, reported speed 0: the field
        // failure case. Calculator must score this as stationary so
        // the filter can snap to anchor.
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 4,
                reportedSpeedMps = 0.0,
                impliedSpeedMps = 0.0,
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
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 8,
                reportedSpeedMps = 1.4,  // ~5 km/h
                impliedSpeedMps = 1.4,
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
        val result = StationaryConfidenceCalculator.evaluate(
            input(
                bufferCount = 8,
                reportedSpeedMps = 0.0,
                impliedSpeedMps = 0.0,
                bearingStability = 0.1,
                speedStability = 1.0,
                jerk = 0.5,
                accuracyMeters = 50.0,
                rawDistanceMeters = 5.0,
                headingChangeRateDegPerSec = 90.0,
            )
        )
        assertTrue(result.isStationary)
        assertTrue("oscillation flag must require a heading or jerk spike", result.isOscillating)
    }

    private fun input(
        bufferCount: Int,
        reportedSpeedMps: Double = 0.0,
        impliedSpeedMps: Double = 0.0,
        bearingStability: Double = 1.0,
        speedStability: Double = 1.0,
        jerk: Double = 0.0,
        accuracyMeters: Double = 5.0,
        rawDistanceMeters: Double = 0.0,
        headingChangeRateDegPerSec: Double = 0.0,
    ) = StationaryConfidenceCalculator.Input(
        reportedSpeedMps = reportedSpeedMps,
        impliedSpeedMps = impliedSpeedMps,
        bearingStability = bearingStability,
        speedStability = speedStability,
        jerk = jerk,
        accuracyMeters = accuracyMeters,
        rawDistanceMeters = rawDistanceMeters,
        headingChangeRateDegPerSec = headingChangeRateDegPerSec,
        bufferCount = bufferCount,
    )
}
