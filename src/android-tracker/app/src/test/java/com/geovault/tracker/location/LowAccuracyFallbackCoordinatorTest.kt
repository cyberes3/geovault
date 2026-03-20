package com.geovault.tracker.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowAccuracyFallbackCoordinatorTest {

    @Test
    fun rejectedFix_startsTimerOnlyOnceUntilAccepted() {
        val coordinator = LowAccuracyFallbackCoordinator()

        assertTrue(coordinator.onRejectedFixForLock(fallbackEligible = true))
        assertFalse(coordinator.onRejectedFixForLock(fallbackEligible = true))

        coordinator.onAcceptedFix()
        assertTrue(coordinator.onRejectedFixForLock(fallbackEligible = true))
    }

    @Test
    fun shouldEmitFallback_requiresAwaitingLockEligibilityAndCandidate() {
        val coordinator = LowAccuracyFallbackCoordinator()

        assertFalse(
            coordinator.shouldEmitFallback(
                fallbackEligible = true,
                hasCandidate = true
            )
        )
        coordinator.onRejectedFixForLock(fallbackEligible = true)
        assertFalse(
            coordinator.shouldEmitFallback(
                fallbackEligible = false,
                hasCandidate = true
            )
        )
        assertFalse(
            coordinator.shouldEmitFallback(
                fallbackEligible = true,
                hasCandidate = false
            )
        )
        assertTrue(
            coordinator.shouldEmitFallback(
                fallbackEligible = true,
                hasCandidate = true
            )
        )
    }

    @Test
    fun trackingStopped_clearsAwaitingLockState() {
        val coordinator = LowAccuracyFallbackCoordinator()

        coordinator.onRejectedFixForLock(fallbackEligible = true)
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))

        coordinator.onTrackingStopped()
        assertFalse(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
    }
}
