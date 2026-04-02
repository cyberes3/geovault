package com.geovault.tracker.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowAccuracyFallbackCoordinatorTest {

    @Test
    fun rejectedFix_firstCandidate_armsTimerAndAllowsEmit() {
        val coordinator = LowAccuracyFallbackCoordinator()
        val shouldStartTimer = coordinator.onRejectedFixForLock(
            fallbackEligible = true,
            candidateLatitude = 1.0,
            candidateLongitude = 2.0,
            candidateTimestampMs = 1000L
        )
        assertTrue(shouldStartTimer)
        assertTrue(
            coordinator.shouldEmitFallback(
                fallbackEligible = true,
                hasCandidate = true
            )
        )
    }

    @Test
    fun acceptedFix_resetsAwaitingLockAndRejectsEmit() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onAcceptedFix()
        assertFalse(
            coordinator.shouldEmitFallback(
                fallbackEligible = true,
                hasCandidate = true
            )
        )
    }

    @Test
    fun fallbackEmitted_requiresNewSignalBeforeNextEmit() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onFallbackEmitted(1.0, 2.0, 1000L)
        assertFalse(coordinator.shouldEmitFallback(true, true))

        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 2500L)
        assertTrue(coordinator.shouldEmitFallback(true, true))
    }
}
