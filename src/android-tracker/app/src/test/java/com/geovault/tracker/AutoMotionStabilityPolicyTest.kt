package com.geovault.tracker

import com.geovault.tracker.policy.TrackPointRejectReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMotionStabilityPolicyTest {

    @Test
    fun shouldSuppressFastLock_transientRejectAfterMotionEvidence() {
        assertTrue(
            AutoMotionStabilityPolicy.shouldSuppressFastLock(
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                nowMs = 20_000L,
                lastMotionEvidenceAtMs = 10_000L,
                lastModeChangedAtMs = 0L,
                windowMs = 15_000L,
            )
        )
    }

    @Test
    fun shouldSuppressFastLock_transientRejectAfterModeChange() {
        assertTrue(
            AutoMotionStabilityPolicy.shouldSuppressFastLock(
                rejectReason = TrackPointRejectReason.STALE,
                nowMs = 20_000L,
                lastMotionEvidenceAtMs = 0L,
                lastModeChangedAtMs = 10_000L,
                windowMs = 15_000L,
            )
        )
    }

    @Test
    fun shouldSuppressFastLock_doesNotSuppressNonTransientRejects() {
        assertFalse(
            AutoMotionStabilityPolicy.shouldSuppressFastLock(
                rejectReason = TrackPointRejectReason.JUMP,
                nowMs = 20_000L,
                lastMotionEvidenceAtMs = 10_000L,
                lastModeChangedAtMs = 10_000L,
                windowMs = 15_000L,
            )
        )
    }

    @Test
    fun shouldSuppressFastLock_expiresOutsideWindow() {
        assertFalse(
            AutoMotionStabilityPolicy.shouldSuppressFastLock(
                rejectReason = TrackPointRejectReason.BAD_ACCURACY,
                nowMs = 30_001L,
                lastMotionEvidenceAtMs = 10_000L,
                lastModeChangedAtMs = 0L,
                windowMs = 15_000L,
            )
        )
    }

    @Test
    fun shouldDebounceLocationRequestReapply_debouncesAutoModeAndElasticity() {
        assertTrue(
            AutoMotionStabilityPolicy.shouldDebounceLocationRequestReapply(
                reason = "auto_mode_accepted_fix",
                nowMs = 15_000L,
                lastAppliedAtMs = 10_000L,
                debounceMs = 10_000L,
            )
        )
        assertTrue(
            AutoMotionStabilityPolicy.shouldDebounceLocationRequestReapply(
                reason = "elasticity_update",
                nowMs = 15_000L,
                lastAppliedAtMs = 10_000L,
                debounceMs = 10_000L,
            )
        )
    }

    @Test
    fun shouldDebounceLocationRequestReapply_doesNotDebounceCriticalReasons() {
        assertFalse(
            AutoMotionStabilityPolicy.shouldDebounceLocationRequestReapply(
                reason = "resume_gps",
                nowMs = 15_000L,
                lastAppliedAtMs = 10_000L,
                debounceMs = 10_000L,
            )
        )
    }
}
