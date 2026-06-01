package com.geovault.tracker.policy.filter

enum class FilterReason(val wireValue: String) {
    LOW_ACCURACY("low-accuracy"),
    FIRST_FIX("first-fix"),
    RESUME_UNCONFIRMED("resume-unconfirmed"),
    CANDIDATE_UNCONFIRMED("candidate-unconfirmed"),
    STALE_RELOCATION_UNCONFIRMED("stale-relocation-unconfirmed"),
    STALE_RELOCATION_CONFIRMED("stale-relocation-confirmed"),
    MOTION_RESUME_CONFIRMED("motion-resume-confirmed"),
    PASS_THROUGH("pass-through"),
    WITHIN_CAP("within-cap"),
    ADJUST_CAP("adjust-cap"),
    CONSERVATIVE_CLIP("conservative-clip"),
    SPEED_CAP_PASSTHROUGH("speed-cap-passthrough"),
    SPEED_CAP("speed-cap"),
    SPEED_CAP_RECOVERED("speed-cap-recovered"),
    SPEED_CAP_UNCONFIRMED("speed-cap-unconfirmed"),
    SPEED_CAP_EXCEEDED("speed-cap-exceeded"),
    UNCERTAINTY_SUPPRESSED("uncertainty-suppressed"),
    IMPLIED_SPEED("implied-speed"),
    OUTLIER_CAPPED("outlier-capped"),
    ;

    companion object {
        private val byWire: Map<String, FilterReason> = entries.associateBy { it.wireValue }

        fun fromWire(wire: String?): FilterReason? {
            if (wire == null) return null
            return byWire[wire]
        }

        val freshnessRecoveryHolds: Set<FilterReason> = setOf(
            CANDIDATE_UNCONFIRMED,
            RESUME_UNCONFIRMED,
            SPEED_CAP_UNCONFIRMED,
            SPEED_CAP_EXCEEDED,
            UNCERTAINTY_SUPPRESSED,
        )
    }
}
