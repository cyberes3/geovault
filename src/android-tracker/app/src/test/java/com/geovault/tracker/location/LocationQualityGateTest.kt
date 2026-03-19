package com.geovault.tracker.location

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationQualityGateTest {
    private fun location(
        lat: Double,
        lon: Double,
        time: Long,
        accuracy: Float? = null
    ): Location {
        val location = Location("gps")
        location.latitude = lat
        location.longitude = lon
        location.time = time
        if (accuracy != null) {
            location.accuracy = accuracy
        }
        return location
    }

    @Test
    fun evaluate_rejectsStaleLocation() {
        val currentTime = 100_000L
        val stale = location(1.0, 1.0, time = 1_000L, accuracy = 10f)
        val result = LocationQualityGate.evaluate(
            lastAcceptedLocation = null,
            newLocation = stale,
            nowMs = currentTime,
            config = LocationQualityConfig(
                maxAccuracyMeters = 50f,
                maxJumpSpeedMps = 100.0,
                freshnessTtlMs = 20_000L
            )
        )
        assertFalse(result.accepted)
        assertEquals(LocationRejectionReason.STALE, result.rejectionReason)
    }

    @Test
    fun evaluate_rejectsPoorAccuracy() {
        val fix = location(1.0, 1.0, time = 99_000L, accuracy = 120f)
        val result = LocationQualityGate.evaluate(
            lastAcceptedLocation = null,
            newLocation = fix,
            nowMs = 100_000L,
            config = LocationQualityConfig(
                maxAccuracyMeters = 50f,
                maxJumpSpeedMps = 100.0,
                freshnessTtlMs = 20_000L
            )
        )
        assertFalse(result.accepted)
        assertEquals(LocationRejectionReason.BAD_ACCURACY, result.rejectionReason)
    }

    @Test
    fun evaluate_rejectsUnrealisticJump() {
        val previous = location(0.0, 0.0, time = 100_000L, accuracy = 5f)
        val jumped = location(1.0, 1.0, time = 101_000L, accuracy = 5f)
        val result = LocationQualityGate.evaluate(
            lastAcceptedLocation = previous,
            newLocation = jumped,
            nowMs = 101_500L,
            config = LocationQualityConfig(
                maxAccuracyMeters = 50f,
                maxJumpSpeedMps = 100.0,
                freshnessTtlMs = 20_000L
            )
        )
        assertFalse(result.accepted)
        assertEquals(LocationRejectionReason.JUMP, result.rejectionReason)
    }

    @Test
    fun evaluate_acceptsAndSmoothsValidLocation() {
        val previous = location(10.0, 10.0, time = 100_000L, accuracy = 5f)
        val next = location(20.0, 20.0, time = 130_000L, accuracy = 10f)
        val result = LocationQualityGate.evaluate(
            lastAcceptedLocation = previous,
            newLocation = next,
            nowMs = 130_500L,
            config = LocationQualityConfig(
                maxAccuracyMeters = 50f,
                maxJumpSpeedMps = 100_000.0,
                freshnessTtlMs = 60_000L,
                smoothingAlpha = 0.25f
            )
        )
        assertTrue(result.accepted)
        assertEquals(12.5, result.location.latitude, 0.001)
        assertEquals(12.5, result.location.longitude, 0.001)
    }

    @Test
    fun evaluate_rejectsOutOfOrderTimestamp() {
        val previous = location(10.0, 10.0, time = 200_000L, accuracy = 5f)
        val outOfOrder = location(10.001, 10.001, time = 199_000L, accuracy = 5f)
        val result = LocationQualityGate.evaluate(
            lastAcceptedLocation = previous,
            newLocation = outOfOrder,
            nowMs = 200_100L,
            config = LocationQualityConfig(
                maxAccuracyMeters = 50f,
                maxJumpSpeedMps = 1000.0,
                freshnessTtlMs = 60_000L
            )
        )
        assertFalse(result.accepted)
        assertEquals(LocationRejectionReason.OUT_OF_ORDER, result.rejectionReason)
    }

    @Test
    fun evaluate_rejectsDuplicateFix() {
        val previous = location(10.0, 10.0, time = 200_000L, accuracy = 5f)
        val duplicate = location(10.0, 10.0, time = 200_000L, accuracy = 5f)
        val result = LocationQualityGate.evaluate(
            lastAcceptedLocation = previous,
            newLocation = duplicate,
            nowMs = 200_100L,
            config = LocationQualityConfig(
                maxAccuracyMeters = 50f,
                maxJumpSpeedMps = 1000.0,
                freshnessTtlMs = 60_000L
            )
        )
        assertFalse(result.accepted)
        assertEquals(LocationRejectionReason.DUPLICATE, result.rejectionReason)
    }
}
