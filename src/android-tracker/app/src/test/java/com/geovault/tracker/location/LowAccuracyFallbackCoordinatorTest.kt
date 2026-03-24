package com.geovault.tracker.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowAccuracyFallbackCoordinatorTest {
    @Test
    fun rejectedFix_startsTimerOnlyOnceUntilAccepted() {
        val coordinator = LowAccuracyFallbackCoordinator()
        assertTrue(
            coordinator.onRejectedFixForLock(
                fallbackEligible = true,
                candidateLatitude = 38.9,
                candidateLongitude = -104.8,
                candidateTimestampMs = 1_800_000_000_000L
            )
        )
        assertFalse(
            coordinator.onRejectedFixForLock(
                fallbackEligible = true,
                candidateLatitude = 38.9,
                candidateLongitude = -104.8,
                candidateTimestampMs = 1_800_000_000_000L
            )
        )

        coordinator.onAcceptedFix()
        assertTrue(
            coordinator.onRejectedFixForLock(
                fallbackEligible = true,
                candidateLatitude = 38.9001,
                candidateLongitude = -104.7999,
                candidateTimestampMs = 1_800_000_030_000L
            )
        )
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
        coordinator.onRejectedFixForLock(
            fallbackEligible = true,
            candidateLatitude = 38.9,
            candidateLongitude = -104.8,
            candidateTimestampMs = 1_800_000_000_000L
        )
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

        coordinator.onRejectedFixForLock(
            fallbackEligible = true,
            candidateLatitude = 38.9,
            candidateLongitude = -104.8,
            candidateTimestampMs = 1_800_000_000_000L
        )
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))

        coordinator.onTrackingStopped()
        assertFalse(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
    }

    @Test
    fun shouldEmitFallback_doesNotRepeatSameCandidate() {
        val coordinator = LowAccuracyFallbackCoordinator()

        coordinator.onRejectedFixForLock(
            fallbackEligible = true,
            candidateLatitude = 38.9,
            candidateLongitude = -104.8,
            candidateTimestampMs = 1_800_000_000_000L
        )
        assertTrue(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
        coordinator.onFallbackEmitted(
            candidateLatitude = 38.9,
            candidateLongitude = -104.8,
            candidateTimestampMs = 1_800_000_000_000L
        )
        assertFalse(coordinator.shouldEmitFallback(fallbackEligible = true, hasCandidate = true))
    }
}
