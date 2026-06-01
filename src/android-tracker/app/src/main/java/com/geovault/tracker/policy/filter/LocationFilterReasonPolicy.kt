package com.geovault.tracker.policy.filter

/**
 * Single semantic contract for [LocationFilterReasons] consumed by motion recovery,
 * local reanchor policy, and freshness recovery.
 */
object LocationFilterReasonPolicy {
    private val spatialHoldReasons: Set<String> = setOf(
        LocationFilterReasons.STALE_RELOCATION_UNCONFIRMED,
        LocationFilterReasons.RESUME_UNCONFIRMED,
        LocationFilterReasons.CANDIDATE_UNCONFIRMED,
        LocationFilterReasons.SPEED_CAP_UNCONFIRMED,
    )

    fun isSpatialHold(reason: String?): Boolean {
        return reason != null && reason in spatialHoldReasons
    }

    fun isExpectedRecoveryHold(reason: String?): Boolean {
        return isSpatialHold(reason) || reason == LocationFilterReasons.SPEED_CAP_EXCEEDED
    }

    fun isCapEvidence(reason: String?): Boolean {
        return reason == LocationFilterReasons.SPEED_CAP_EXCEEDED ||
            reason == LocationFilterReasons.SPEED_CAP_UNCONFIRMED
    }

    fun isRecoverableFreshnessHold(reason: String?, holdReasons: Set<String>): Boolean {
        return reason != null && reason in holdReasons
    }

    fun blocksFreshnessAnchorCommit(
        reason: String?,
        holdReasons: Set<String>,
        repeatedOutlierSuppressed: Boolean,
    ): Boolean {
        if (repeatedOutlierSuppressed) return true
        return !isRecoverableFreshnessHold(reason, holdReasons)
    }
}
