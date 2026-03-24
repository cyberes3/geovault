package com.geovault.tracker

import android.location.Location
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.pipeline.TrackPointRejectReason
import com.geovault.tracker.settings.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackingServiceFallbackBehaviorTest {
    private fun location(
        lat: Double,
        lon: Double,
        timeMs: Long,
        accuracyMeters: Float? = null
    ): Location {
        return Location("gps").apply {
            latitude = lat
            longitude = lon
            time = timeMs
            if (accuracyMeters != null) {
                accuracy = accuracyMeters
            }
        }
    }

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

    @Test
    fun shouldStartFastGpsLock_startsForBadAccuracyOverThresholdOrMissingAccuracyWhenEnabled() {
        assertTrue(
            TrackingService.shouldStartFastGpsLock(
                fastGpsLockEnabled = true,
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                measuredAccuracyMeters = 80f,
                accuracyFilterMeters = 50f
            )
        )
        assertTrue(
            TrackingService.shouldStartFastGpsLock(
                fastGpsLockEnabled = true,
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                measuredAccuracyMeters = null,
                accuracyFilterMeters = 50f
            )
        )
        assertFalse(
            TrackingService.shouldStartFastGpsLock(
                fastGpsLockEnabled = false,
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                measuredAccuracyMeters = 80f,
                accuracyFilterMeters = 50f
            )
        )
        assertFalse(
            TrackingService.shouldStartFastGpsLock(
                fastGpsLockEnabled = true,
                rejectReason = TrackPointRejectReason.STALE,
                measuredAccuracyMeters = 80f,
                accuracyFilterMeters = 50f
            )
        )
        assertTrue(
            TrackingService.shouldStartFastGpsLock(
                fastGpsLockEnabled = true,
                rejectReason = null,
                measuredAccuracyMeters = null,
                accuracyFilterMeters = 50f
            )
        )
        assertFalse(
            TrackingService.shouldStartFastGpsLock(
                fastGpsLockEnabled = true,
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                measuredAccuracyMeters = 40f,
                accuracyFilterMeters = 50f
            )
        )
    }

    @Test
    fun shouldEmitFallbackForTransition_rejectsImplausibleJump() {
        val previous = location(
            lat = 38.9000,
            lon = -104.8000,
            timeMs = 1_800_000_000_000L,
            accuracyMeters = 10f
        )
        val candidate = location(
            lat = 38.9055,
            lon = -104.7950,
            timeMs = 1_800_000_010_000L,
            accuracyMeters = 180f
        )
        val result = TrackingService.shouldEmitFallbackForTransition(
            previousAcceptedLocation = previous,
            fallbackCandidateLocation = candidate,
            nowMs = candidate.time
        )
        assertFalse(result)
    }

    @Test
    fun shouldEmitFallbackForTransition_acceptsPlausibleNearbyFix() {
        val previous = location(
            lat = 38.9000,
            lon = -104.8000,
            timeMs = 1_800_000_000_000L,
            accuracyMeters = 10f
        )
        val candidate = location(
            lat = 38.9005,
            lon = -104.7997,
            timeMs = 1_800_000_060_000L,
            accuracyMeters = 150f
        )
        val result = TrackingService.shouldEmitFallbackForTransition(
            previousAcceptedLocation = previous,
            fallbackCandidateLocation = candidate,
            nowMs = candidate.time
        )
        assertTrue(result)
    }
}
