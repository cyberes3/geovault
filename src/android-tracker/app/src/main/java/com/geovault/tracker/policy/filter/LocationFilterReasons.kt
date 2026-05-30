package com.geovault.tracker.policy.filter

object LocationFilterReasons {
    const val LOW_ACCURACY = "low-accuracy"
    const val FIRST_FIX = "first-fix"
    const val RESUME_UNCONFIRMED = "resume-unconfirmed"
    const val CANDIDATE_UNCONFIRMED = "candidate-unconfirmed"
    const val STALE_RELOCATION_UNCONFIRMED = "stale-relocation-unconfirmed"
    const val STALE_RELOCATION_CONFIRMED = "stale-relocation-confirmed"
    const val PASS_THROUGH = "pass-through"
    const val WITHIN_CAP = "within-cap"
    const val ADJUST_CAP = "adjust-cap"
    const val SPEED_CAP_PASSTHROUGH = "speed-cap-passthrough"
    const val SPEED_CAP = "speed-cap"
    const val SPEED_CAP_RECOVERED = "speed-cap-recovered"
    const val SPEED_CAP_UNCONFIRMED = "speed-cap-unconfirmed"
    const val SPEED_CAP_EXCEEDED = "speed-cap-exceeded"
    const val UNCERTAINTY_SUPPRESSED = "uncertainty-suppressed"
}
