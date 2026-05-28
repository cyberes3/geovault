package com.geovault.tracker.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAccuracyHoldPolicyTest {

    private val threshold = 20f

    @Test
    fun goodFix_surfacesAndRecordsTimestamp() {
        val previous = TrackingRuntimeSnapshot()

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = 8f,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 1_000L,
        )

        assertEquals(8f, result.displayedAccuracyMeters)
        assertEquals(8f, result.lastGoodAccuracyMeters)
        assertEquals(1_000L, result.lastGoodAccuracyAtElapsedMs)
        assertFalse(result.heldLastGoodAccuracy)
    }

    @Test
    fun goodFix_atExactThreshold_isStillGood() {
        val result = RuntimeAccuracyHoldPolicy.next(
            previous = TrackingRuntimeSnapshot(),
            incomingAccuracyMeters = threshold,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 5_000L,
        )

        assertEquals(threshold, result.displayedAccuracyMeters)
        assertEquals(threshold, result.lastGoodAccuracyMeters)
        assertEquals(5_000L, result.lastGoodAccuracyAtElapsedMs)
    }

    @Test
    fun badFix_withinGrace_isSuppressedToLastGood() {
        val previous = TrackingRuntimeSnapshot(
            lastGoodAccuracyMeters = 9f,
            lastGoodAccuracyAtElapsedMs = 10_000L,
        )

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = 75f,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 10_000L + 5_000L,
        )

        assertEquals(9f, result.displayedAccuracyMeters)
        assertEquals(9f, result.lastGoodAccuracyMeters)
        assertEquals(10_000L, result.lastGoodAccuracyAtElapsedMs)
        assertTrue(result.heldLastGoodAccuracy)
    }

    @Test
    fun badFix_withinGrace_forceCurrentAccuracy_surfacesRawValue() {
        val previous = TrackingRuntimeSnapshot(
            lastGoodAccuracyMeters = 9f,
            lastGoodAccuracyAtElapsedMs = 10_000L,
        )

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = 75f,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 10_000L + 5_000L,
            forceCurrentAccuracy = true,
        )

        assertEquals(75f, result.displayedAccuracyMeters)
        assertEquals(9f, result.lastGoodAccuracyMeters)
        assertEquals(10_000L, result.lastGoodAccuracyAtElapsedMs)
        assertTrue(result.heldLastGoodAccuracy)
    }

    @Test
    fun nullAccuracy_forceCurrentAccuracy_surfacesNull() {
        val previous = TrackingRuntimeSnapshot(
            lastGoodAccuracyMeters = 7f,
            lastGoodAccuracyAtElapsedMs = 2_000L,
        )

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = null,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 2_000L + 1_000L,
            forceCurrentAccuracy = true,
        )

        assertNull(result.displayedAccuracyMeters)
        assertEquals(7f, result.lastGoodAccuracyMeters)
    }

    @Test
    fun badFix_atGraceBoundary_stillSuppressed() {
        val previous = TrackingRuntimeSnapshot(
            lastGoodAccuracyMeters = 12f,
            lastGoodAccuracyAtElapsedMs = 100L,
        )

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = 60f,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 100L + RuntimeAccuracyHoldPolicy.ACCURACY_HOLD_GRACE_MS,
        )

        assertEquals(12f, result.displayedAccuracyMeters)
    }

    @Test
    fun badFix_afterGraceExpired_surfacesRawValue() {
        val previous = TrackingRuntimeSnapshot(
            lastGoodAccuracyMeters = 9f,
            lastGoodAccuracyAtElapsedMs = 1_000L,
        )

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = 75f,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 1_000L + RuntimeAccuracyHoldPolicy.ACCURACY_HOLD_GRACE_MS + 1L,
        )

        assertEquals(75f, result.displayedAccuracyMeters)
        assertEquals(9f, result.lastGoodAccuracyMeters)
        assertEquals(1_000L, result.lastGoodAccuracyAtElapsedMs)
        assertFalse(result.heldLastGoodAccuracy)
    }

    @Test
    fun nullAccuracy_withinGrace_isSuppressedToLastGood() {
        val previous = TrackingRuntimeSnapshot(
            lastGoodAccuracyMeters = 7f,
            lastGoodAccuracyAtElapsedMs = 2_000L,
        )

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = null,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 2_000L + 1_000L,
        )

        assertEquals(7f, result.displayedAccuracyMeters)
    }

    @Test
    fun nullAccuracy_afterGraceExpired_surfacesNull() {
        val previous = TrackingRuntimeSnapshot(
            lastGoodAccuracyMeters = 7f,
            lastGoodAccuracyAtElapsedMs = 2_000L,
        )

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = null,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 2_000L + RuntimeAccuracyHoldPolicy.ACCURACY_HOLD_GRACE_MS + 1L,
        )

        assertNull(result.displayedAccuracyMeters)
    }

    @Test
    fun badFix_withNoPriorGoodFix_surfacesRawValue() {
        val result = RuntimeAccuracyHoldPolicy.next(
            previous = TrackingRuntimeSnapshot(),
            incomingAccuracyMeters = 50f,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 100L,
        )

        assertEquals(50f, result.displayedAccuracyMeters)
        assertNull(result.lastGoodAccuracyMeters)
        assertEquals(0L, result.lastGoodAccuracyAtElapsedMs)
        assertFalse(result.heldLastGoodAccuracy)
    }

    @Test
    fun nanAccuracy_isTreatedAsBad() {
        val previous = TrackingRuntimeSnapshot(
            lastGoodAccuracyMeters = 5f,
            lastGoodAccuracyAtElapsedMs = 500L,
        )

        val result = RuntimeAccuracyHoldPolicy.next(
            previous = previous,
            incomingAccuracyMeters = Float.NaN,
            effectiveAccuracyThresholdMeters = threshold,
            nowElapsedMs = 500L + 1_000L,
        )

        assertEquals(5f, result.displayedAccuracyMeters)
    }
}
