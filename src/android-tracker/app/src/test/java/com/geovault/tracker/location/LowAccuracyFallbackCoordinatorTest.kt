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

    @Test
    fun secondRejectWhileAwaitingLock_doesNotRequestNewTimer() {
        val coordinator = LowAccuracyFallbackCoordinator()
        assertTrue(coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L))
        assertFalse(coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1200L))
    }

    @Test
    fun ineligibleReject_neverArmsOrEmits() {
        val coordinator = LowAccuracyFallbackCoordinator()
        assertFalse(coordinator.onRejectedFixForLock(false, 1.0, 2.0, 1000L))
        assertFalse(coordinator.shouldEmitFallback(fallbackEligible = false, hasCandidate = true))
    }

    @Test
    fun trackingStopped_clearsStateAndPreventsEmitUntilNewReject() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onTrackingStopped()
        assertFalse(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))

        assertTrue(coordinator.onRejectedFixForLock(true, 1.0, 2.0, 2000L))
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun fallbackTimerStopped_allowsNewRejectToArmTimer() {
        val coordinator = LowAccuracyFallbackCoordinator()
        assertTrue(coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L))

        coordinator.onFallbackTimerStopped()

        assertFalse(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
        assertTrue(coordinator.onRejectedFixForLock(true, 1.0, 2.0, 2000L))
    }

    @Test
    fun fallbackEmitted_withoutMeaningfulChange_doesNotReemit() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onFallbackEmitted(1.0, 2.0, 1000L)
        coordinator.onRejectedFixForLock(true, 1.000001, 2.000001, 1500L)
        assertFalse(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun fallbackEmitted_largeMoveWithinOneSecond_allowsReemit() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onFallbackEmitted(1.0, 2.0, 1000L)
        coordinator.onRejectedFixForLock(true, 1.0001, 2.0001, 1500L)
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
    }
}
