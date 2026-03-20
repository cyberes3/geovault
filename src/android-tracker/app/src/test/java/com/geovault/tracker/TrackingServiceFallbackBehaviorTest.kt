package com.geovault.tracker

import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingServiceFallbackBehaviorTest {

    @Test
    fun timeoutMs_clampsAndConvertsToMilliseconds() {
        assertEquals(
            TrackerSettings.MIN_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC * 1000L,
            TrackingService.resolveLowAccuracyFallbackTimeoutMs(0L)
        )
        assertEquals(
            60_000L,
            TrackingService.resolveLowAccuracyFallbackTimeoutMs(60L)
        )
        assertEquals(
            TrackerSettings.MAX_LOW_ACCURACY_FALLBACK_TIMEOUT_SEC * 1000L,
            TrackingService.resolveLowAccuracyFallbackTimeoutMs(999_999L)
        )
    }

    @Test
    fun rejectedFix_thenAcceptedBeforeTimeout_cancelsFallbackEmission() {
        val coordinator = LowAccuracyFallbackCoordinator()

        val shouldArm = coordinator.onRejectedFixForLock(fallbackEligible = true)
        assertTrue(shouldArm)

        coordinator.onAcceptedFix()
        assertFalse(
            coordinator.shouldEmitFallback(
                fallbackEligible = true,
                hasCandidate = true
            )
        )
    }

    @Test
    fun timeoutWhileAwaitingLock_emitsAndStaysArmedForRepeatCycles() {
        val coordinator = LowAccuracyFallbackCoordinator()

        assertTrue(coordinator.onRejectedFixForLock(fallbackEligible = true))
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
        // Service loop should be able to emit again on the next timeout if lock is still missing.
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun disabledFallback_neverArmsOrEmits() {
        val coordinator = LowAccuracyFallbackCoordinator()

        assertFalse(coordinator.onRejectedFixForLock(fallbackEligible = false))
        assertFalse(
            coordinator.shouldEmitFallback(
                fallbackEligible = false,
                hasCandidate = true
            )
        )
    }
}
