package com.geovault.tracker.policy.filter

object LocationFilterReasonPolicy {
    private val spatialHoldReasons: Set<FilterReason> = setOf(
        FilterReason.STALE_RELOCATION_UNCONFIRMED,
        FilterReason.RESUME_UNCONFIRMED,
        FilterReason.CANDIDATE_UNCONFIRMED,
        FilterReason.SPEED_CAP_UNCONFIRMED,
    )

    fun isSpatialHold(reason: FilterReason?): Boolean {
        return reason != null && reason in spatialHoldReasons
    }

    fun isExpectedRecoveryHold(reason: FilterReason?): Boolean {
        return isSpatialHold(reason) || reason == FilterReason.SPEED_CAP_EXCEEDED
    }

    fun isCapEvidence(reason: FilterReason?): Boolean {
        return reason == FilterReason.SPEED_CAP_EXCEEDED ||
            reason == FilterReason.SPEED_CAP_UNCONFIRMED
    }

    fun isRecoverableFreshnessHold(reason: FilterReason?, holdReasons: Set<FilterReason>): Boolean {
        return reason != null && reason in holdReasons
    }

    fun blocksFreshnessAnchorCommit(
        reason: FilterReason?,
        holdReasons: Set<FilterReason>,
        repeatedOutlierSuppressed: Boolean,
    ): Boolean {
        if (repeatedOutlierSuppressed) return true
        return !isRecoverableFreshnessHold(reason, holdReasons)
    }
}
