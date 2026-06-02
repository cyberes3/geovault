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

    /**
     * Policy reasons that suppress forced local reanchor after a JUMP reject streak.
     * Matches pre-de4fc52b allowlist: stale-relocation is intentionally excluded so catch-up
     * GPS leaps can reanchor when twin-fix confirmation cannot complete.
     */
    fun blocksForcedLocalReanchor(reason: FilterReason?): Boolean {
        return reason == FilterReason.RESUME_UNCONFIRMED ||
            reason == FilterReason.CANDIDATE_UNCONFIRMED ||
            reason == FilterReason.SPEED_CAP_UNCONFIRMED ||
            reason == FilterReason.SPEED_CAP_EXCEEDED
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
