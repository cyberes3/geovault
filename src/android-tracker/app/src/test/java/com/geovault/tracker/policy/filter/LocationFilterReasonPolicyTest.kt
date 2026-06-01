package com.geovault.tracker.policy.filter

import com.geovault.tracker.location.PositioningRecoveryConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterReasonPolicyTest {

    @Test
    fun isExpectedRecoveryHold_includesStaleRelocationUnconfirmed() {
        assertTrue(
            LocationFilterReasonPolicy.isExpectedRecoveryHold(
                LocationFilterReasons.STALE_RELOCATION_UNCONFIRMED,
            ),
        )
    }

    @Test
    fun blocksFreshnessAnchorCommit_whenRepeatedOutlierSuppressed() {
        assertTrue(
            LocationFilterReasonPolicy.blocksFreshnessAnchorCommit(
                reason = LocationFilterReasons.CANDIDATE_UNCONFIRMED,
                holdReasons = PositioningRecoveryConfig.DEFAULT_FRESHNESS_RECOVERY_HOLD_REASONS,
                repeatedOutlierSuppressed = true,
            ),
        )
    }

    @Test
    fun blocksFreshnessAnchorCommit_whenReasonNotRecoverable() {
        assertTrue(
            LocationFilterReasonPolicy.blocksFreshnessAnchorCommit(
                reason = LocationFilterReasons.WITHIN_CAP,
                holdReasons = PositioningRecoveryConfig.DEFAULT_FRESHNESS_RECOVERY_HOLD_REASONS,
                repeatedOutlierSuppressed = false,
            ),
        )
    }

    @Test
    fun blocksFreshnessAnchorCommit_falseForRecoverableHold() {
        assertFalse(
            LocationFilterReasonPolicy.blocksFreshnessAnchorCommit(
                reason = LocationFilterReasons.RESUME_UNCONFIRMED,
                holdReasons = PositioningRecoveryConfig.DEFAULT_FRESHNESS_RECOVERY_HOLD_REASONS,
                repeatedOutlierSuppressed = false,
            ),
        )
    }
}
