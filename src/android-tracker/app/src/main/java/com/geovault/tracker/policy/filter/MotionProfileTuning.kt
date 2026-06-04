package com.geovault.tracker.policy.filter

/**
 * Complete per-motion-mode tuning for the positioning filter.
 *
 * The values here describe physical plausibility, not UI preferences. The
 * request interval and distance filter still live with the tracking service;
 * this object owns how much trust the filter should place in a single fix.
 * Profiles are presets over generic knobs, not special cases for a particular route.
 */
data class MotionProfileTuning(
    val maxImpliedSpeedMps: Double,
    val maxBurstDistanceMeters: Double,
    val burstWindowSeconds: Double,
    val rollingWindowSeconds: Double,
    val kinematicCap: KinematicCapConfig,
    val movementCandidate: MovementCandidateConfig,
    val speedRecovery: SpeedRecoveryConfig,
    val anchorHealth: AnchorHealthConfig,
    /**
     * Candidate lifetime for twin-fix resume confirmation. Must exceed the
     * profile's typical fix interval so the second fix can arrive before the
     * candidate expires. Walking ~10 s → 45 s; biking ~15 s → 30 s;
     * driving ~30 s → 60 s.
     */
    val resumeConfirmationWindowMs: Long,
    /**
     * Raw anchor-to-fix distance above which a single good fix is sufficient
     * to confirm relocation, bypassing the twin-fix consistency check. At
     * highway speeds the two-fix window expires before a second fix arrives
     * and spatial consistency is impossible between fixes hundreds of metres
     * apart. Set 0 to disable the fast path.
     */
    val resumeConfirmationLargeDisplacementMeters: Double,
) {
    companion object {
        val Walking: MotionProfileTuning = MotionProfileTuning(
            maxImpliedSpeedMps = 4.5,
            maxBurstDistanceMeters = 65.0,
            burstWindowSeconds = 20.0,
            rollingWindowSeconds = 8.0,
            kinematicCap = KinematicCapConfig(
                unconfirmedReportedSpeedLimitMps = 2.5,
                trustedReportedSpeedLimitMps = 4.5,
                stableMotionSpeedLimitMps = 4.5,
                stableMotionAccuracyMeters = 25.0,
            ),
            movementCandidate = MovementCandidateConfig(
                enabled = true,
                suspectDistanceMeters = 55.0,
                suspectAccuracyMeters = 25.0,
                suspectImpliedSpeedMps = 2.8,
                consistencyMeters = 35.0,
                confirmationWindowMs = 45_000L,
                requiredConsistentFixes = 2,
                requiredPromotableFixes = 2,
                promotionAccuracyMeters = 30.0,
            ),
            speedRecovery = SpeedRecoveryConfig.Disabled,
            anchorHealth = AnchorHealthConfig(
                repeatedSnapLimit = 2,
                disagreementDistanceMeters = 45.0,
                suspectAccuracyMeters = 30.0,
            ),
            resumeConfirmationWindowMs = 45_000L,
            resumeConfirmationLargeDisplacementMeters = 300.0,
        )

        val Biking: MotionProfileTuning = MotionProfileTuning(
            maxImpliedSpeedMps = 14.0,
            maxBurstDistanceMeters = 160.0,
            burstWindowSeconds = 14.0,
            rollingWindowSeconds = 6.0,
            kinematicCap = KinematicCapConfig(
                unconfirmedReportedSpeedLimitMps = 8.0,
                trustedReportedSpeedLimitMps = 14.0,
                stableMotionSpeedLimitMps = 14.0,
                stableMotionAccuracyMeters = 35.0,
            ),
            movementCandidate = MovementCandidateConfig(
                enabled = true,
                suspectDistanceMeters = 140.0,
                suspectAccuracyMeters = 35.0,
                suspectImpliedSpeedMps = 10.0,
                consistencyMeters = 60.0,
                confirmationWindowMs = 30_000L,
                requiredConsistentFixes = 2,
                requiredPromotableFixes = 2,
                promotionAccuracyMeters = 40.0,
            ),
            speedRecovery = SpeedRecoveryConfig(
                enabled = true,
                maxRecoverableSpeedMps = 42.0,
                maxAccuracyMeters = 35.0,
                confirmationWindowMs = 45_000L,
                requiredConsistentFixes = 2,
                requiredPromotableFixes = 2,
                minDtSeconds = 2.0,
                maxDtSeconds = 45.0,
                maxSpeedDeltaMps = 12.0,
                maxCourseDeltaDegrees = 35.0,
                minContinuityMeters = 35.0,
                continuitySpeedMultiplier = 1.25,
            ),
            anchorHealth = AnchorHealthConfig(
                repeatedSnapLimit = 3,
                disagreementDistanceMeters = 90.0,
                suspectAccuracyMeters = 40.0,
            ),
            resumeConfirmationWindowMs = 30_000L,
            resumeConfirmationLargeDisplacementMeters = 500.0,
        )

        val Driving: MotionProfileTuning = MotionProfileTuning(
            maxImpliedSpeedMps = 60.0,
            maxBurstDistanceMeters = 300.0,
            burstWindowSeconds = 10.0,
            rollingWindowSeconds = 5.0,
            kinematicCap = KinematicCapConfig.Default,
            movementCandidate = MovementCandidateConfig(
                enabled = true,
                suspectDistanceMeters = 300.0,
                suspectAccuracyMeters = 60.0,
                suspectImpliedSpeedMps = 35.0,
                consistencyMeters = 100.0,
                confirmationWindowMs = 15_000L,
                requiredConsistentFixes = 1,
                requiredPromotableFixes = 1,
                promotionAccuracyMeters = 80.0,
            ),
            speedRecovery = SpeedRecoveryConfig.Disabled,
            anchorHealth = AnchorHealthConfig.Default,
            resumeConfirmationWindowMs = 60_000L,
            resumeConfirmationLargeDisplacementMeters = 800.0,
        )
    }
}

data class KinematicCapConfig(
    val unconfirmedReportedSpeedLimitMps: Double,
    val trustedReportedSpeedLimitMps: Double,
    val stableMotionSpeedLimitMps: Double,
    val stableMotionAccuracyMeters: Double,
) {
    companion object {
        val Default: KinematicCapConfig = KinematicCapConfig(
            unconfirmedReportedSpeedLimitMps = 60.0,
            trustedReportedSpeedLimitMps = 60.0,
            stableMotionSpeedLimitMps = 60.0,
            stableMotionAccuracyMeters = 50.0,
        )
    }
}

/**
 * Per-band fix-counter convention used by both [MovementCandidateConfig]
 * and [SpeedRecoveryConfig]:
 *
 * - [requiredConsistentFixes] counts coherent fixes (continuity, course,
 *   speed-delta sane) before the gate is willing to promote.
 * - [requiredPromotableFixes] counts how many of those must also satisfy
 *   the tighter promotion-accuracy threshold.
 *
 * The two should agree per band unless you have a deliberate reason to
 * require more coherent fixes than promotable ones. Biking unified both
 * to 2 in the auto-mode driving promotion pass.
 */
data class MovementCandidateConfig(
    val enabled: Boolean,
    val suspectDistanceMeters: Double,
    val suspectAccuracyMeters: Double,
    val suspectImpliedSpeedMps: Double,
    val consistencyMeters: Double,
    val confirmationWindowMs: Long,
    val requiredConsistentFixes: Int,
    val requiredPromotableFixes: Int,
    val promotionAccuracyMeters: Double,
) {
    companion object {
        val Disabled: MovementCandidateConfig = MovementCandidateConfig(
            enabled = false,
            suspectDistanceMeters = Double.MAX_VALUE,
            suspectAccuracyMeters = Double.MAX_VALUE,
            suspectImpliedSpeedMps = Double.MAX_VALUE,
            consistencyMeters = Double.MAX_VALUE,
            confirmationWindowMs = 0L,
            requiredConsistentFixes = 1,
            requiredPromotableFixes = 1,
            promotionAccuracyMeters = Double.MAX_VALUE,
        )
    }
}

data class SpeedRecoveryConfig(
    val enabled: Boolean,
    val maxRecoverableSpeedMps: Double,
    val maxAccuracyMeters: Double,
    val confirmationWindowMs: Long,
    val requiredConsistentFixes: Int,
    val requiredPromotableFixes: Int,
    val minDtSeconds: Double,
    val maxDtSeconds: Double,
    val maxSpeedDeltaMps: Double,
    val maxCourseDeltaDegrees: Double,
    val minContinuityMeters: Double,
    val continuitySpeedMultiplier: Double,
) {
    companion object {
        val Disabled: SpeedRecoveryConfig = SpeedRecoveryConfig(
            enabled = false,
            maxRecoverableSpeedMps = Double.MAX_VALUE,
            maxAccuracyMeters = 0.0,
            confirmationWindowMs = 0L,
            requiredConsistentFixes = 1,
            requiredPromotableFixes = 1,
            minDtSeconds = 0.0,
            maxDtSeconds = Double.MAX_VALUE,
            maxSpeedDeltaMps = Double.MAX_VALUE,
            maxCourseDeltaDegrees = 180.0,
            minContinuityMeters = Double.MAX_VALUE,
            continuitySpeedMultiplier = 1.0,
        )
    }
}

data class AnchorHealthConfig(
    val repeatedSnapLimit: Int,
    val disagreementDistanceMeters: Double,
    val suspectAccuracyMeters: Double,
) {
    companion object {
        val Default: AnchorHealthConfig = AnchorHealthConfig(
            repeatedSnapLimit = 4,
            disagreementDistanceMeters = 140.0,
            suspectAccuracyMeters = 60.0,
        )
    }
}
