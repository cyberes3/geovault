package com.geovault.tracker.location

import org.junit.Assert.assertEquals
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
        assertEquals(LowAccuracyFallbackArmDecision.START_TIMER, shouldStartTimer)
        assertEquals(
            LowAccuracyFallbackEmitDecision.EMIT,
            coordinator.evaluateEmit(
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
        assertEquals(
            LowAccuracyFallbackEmitDecision.WAIT,
            coordinator.evaluateEmit(
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
        assertEquals(LowAccuracyFallbackEmitDecision.DUPLICATE_CANDIDATE, coordinator.evaluateEmit(true, true))

        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 2500L)
        assertEquals(LowAccuracyFallbackEmitDecision.EMIT, coordinator.evaluateEmit(true, true))
    }

    @Test
    fun secondRejectWhileAwaitingLock_doesNotRequestNewTimer() {
        val coordinator = LowAccuracyFallbackCoordinator()
        assertEquals(LowAccuracyFallbackArmDecision.START_TIMER, coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L))
        assertEquals(LowAccuracyFallbackArmDecision.KEEP_TIMER, coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1200L))
    }

    @Test
    fun ineligibleReject_neverArmsOrEmits() {
        val coordinator = LowAccuracyFallbackCoordinator()
        assertEquals(LowAccuracyFallbackArmDecision.INELIGIBLE, coordinator.onRejectedFixForLock(false, 1.0, 2.0, 1000L))
        assertEquals(LowAccuracyFallbackEmitDecision.DISABLED, coordinator.evaluateEmit(fallbackEligible = false, hasCandidate = true))
    }

    @Test
    fun trackingStopped_clearsStateAndPreventsEmitUntilNewReject() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onTrackingStopped()
        assertEquals(LowAccuracyFallbackEmitDecision.WAIT, coordinator.evaluateEmit(fallbackEligible = true, hasCandidate = true))

        assertEquals(LowAccuracyFallbackArmDecision.START_TIMER, coordinator.onRejectedFixForLock(true, 1.0, 2.0, 2000L))
        assertEquals(LowAccuracyFallbackEmitDecision.EMIT, coordinator.evaluateEmit(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun fallbackTimerStopped_allowsNewRejectToArmTimer() {
        val coordinator = LowAccuracyFallbackCoordinator()
        assertEquals(LowAccuracyFallbackArmDecision.START_TIMER, coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L))

        coordinator.onFallbackTimerStopped()

        assertEquals(LowAccuracyFallbackEmitDecision.WAIT, coordinator.evaluateEmit(fallbackEligible = true, hasCandidate = true))
        assertEquals(LowAccuracyFallbackArmDecision.START_TIMER, coordinator.onRejectedFixForLock(true, 1.0, 2.0, 2000L))
    }

    @Test
    fun fallbackTimerStopped_preservesLastEmittedFingerprint() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onFallbackEmitted(1.0, 2.0, 1000L)

        coordinator.onFallbackTimerStopped()
        assertEquals(LowAccuracyFallbackArmDecision.START_TIMER, coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L))

        assertEquals(LowAccuracyFallbackEmitDecision.DUPLICATE_CANDIDATE, coordinator.evaluateEmit(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun fallbackEmitted_withoutMeaningfulChange_doesNotReemit() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onFallbackEmitted(1.0, 2.0, 1000L)
        coordinator.onRejectedFixForLock(true, 1.000001, 2.000001, 1500L)
        assertEquals(LowAccuracyFallbackEmitDecision.DUPLICATE_CANDIDATE, coordinator.evaluateEmit(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun fallbackEmitted_largeMoveWithinOneSecond_allowsReemit() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)
        coordinator.onFallbackEmitted(1.0, 2.0, 1000L)
        coordinator.onRejectedFixForLock(true, 1.0001, 2.0001, 1500L)
        assertEquals(LowAccuracyFallbackEmitDecision.EMIT, coordinator.evaluateEmit(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun evaluateLoop_namesEmissionAsAnchoredCommit() {
        val coordinator = LowAccuracyFallbackCoordinator()
        coordinator.onRejectedFixForLock(true, 1.0, 2.0, 1000L)

        assertEquals(
            LowAccuracyFallbackLoopDecision.COMMIT_ANCHOR,
            coordinator.evaluateLoop(fallbackEligible = true, hasCandidate = true),
        )
    }
}
