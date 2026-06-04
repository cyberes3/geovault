package com.geovault.tracker.location

import android.location.Location
import com.geovault.tracker.policy.filter.FilterReason
import com.geovault.tracker.policy.filter.LocationFilterReasonPolicy

enum class FreshnessRecoveryReason(val telemetryValue: String) {
    INACTIVE("inactive"),
    PROBE_STARTED("probe-started"),
    PROBE_WAIT("probe-wait"),
    COMMIT_ANCHOR("commit-anchor"),
    ALREADY_ACCEPTED_NOT_PERSISTED("already-accepted-not-persisted"),
    NO_POLICY_REASON("no-policy-reason"),
    NOT_RECOVERABLE_HOLD("not-recoverable-hold"),
    BAD_ACCURACY("bad-accuracy"),
    MISSING_ACCURACY("missing-accuracy"),
    IMPLAUSIBLE_MOVE("implausible-move"),
    REPEATED_OUTLIER("repeated-outlier"),
    NO_ANCHOR("no-anchor"),
    PROBE_EXPIRED("probe-expired"),
}

sealed class FreshnessRecoveryDecision(
    val reason: FreshnessRecoveryReason,
    val detailsReason: String? = null,
) {
    data object Inactive : FreshnessRecoveryDecision(FreshnessRecoveryReason.INACTIVE)
    class ProbeStarted(detailsReason: String?) :
        FreshnessRecoveryDecision(FreshnessRecoveryReason.PROBE_STARTED, detailsReason)
    class ProbeWait(detailsReason: String?) :
        FreshnessRecoveryDecision(FreshnessRecoveryReason.PROBE_WAIT, detailsReason)
    data object CommitAnchor : FreshnessRecoveryDecision(FreshnessRecoveryReason.COMMIT_ANCHOR)
    class Blocked(reason: FreshnessRecoveryReason, detailsReason: String? = null) :
        FreshnessRecoveryDecision(reason, detailsReason)

    val telemetryValue: String
        get() = detailsReason
            ?.let { "${reason.telemetryValue}:$it" }
            ?: reason.telemetryValue
}

data class FreshnessRecoveryInput(
    val localRecoveryDue: Boolean,
    val accepted: Boolean,
    val pointPersisted: Boolean,
    val filterReason: FilterReason?,
    val accuracyMeters: Float?,
    val effectiveAccuracyThresholdMeters: Float,
    val candidateLocation: Location,
    val anchor: RecoveryAnchorState?,
    val repeatedOutlierSuppressed: Boolean,
    val nowMs: Long,
    val config: PositioningRecoveryConfig,
)

object FreshnessRecoveryPolicy {
    fun evaluate(
        input: FreshnessRecoveryInput,
        probeActive: Boolean,
        probeStartedAtMs: Long,
        promotableProbeFixes: Int,
    ): FreshnessRecoveryDecision {
        if (!input.localRecoveryDue || input.pointPersisted) {
            return FreshnessRecoveryDecision.Inactive
        }
        if (!probeActive) {
            return FreshnessRecoveryDecision.ProbeStarted(input.filterReason?.wireValue)
        }
        if (probeStartedAtMs > 0L && input.nowMs - probeStartedAtMs > input.config.freshnessProbeWindowMs) {
            return FreshnessRecoveryDecision.Blocked(FreshnessRecoveryReason.PROBE_EXPIRED)
        }
        val block = blockingReason(input)
        if (block != null) return block
        return if (promotableProbeFixes + 1 >= input.config.minPromotableProbeFixes) {
            FreshnessRecoveryDecision.CommitAnchor
        } else {
            FreshnessRecoveryDecision.ProbeWait(input.filterReason?.wireValue)
        }
    }

    private fun blockingReason(input: FreshnessRecoveryInput): FreshnessRecoveryDecision.Blocked? {
        if (input.accepted) {
            if (input.filterReason != FilterReason.UNCERTAINTY_SUPPRESSED) {
                return FreshnessRecoveryDecision.Blocked(FreshnessRecoveryReason.ALREADY_ACCEPTED_NOT_PERSISTED)
            }
        }
        val reason = input.filterReason ?: return FreshnessRecoveryDecision.Blocked(FreshnessRecoveryReason.NO_POLICY_REASON)
        if (
            LocationFilterReasonPolicy.blocksFreshnessAnchorCommit(
                reason = reason,
                holdReasons = input.config.freshnessRecoveryHoldReasons,
                repeatedOutlierSuppressed = input.repeatedOutlierSuppressed,
            )
        ) {
            if (input.repeatedOutlierSuppressed) {
                return FreshnessRecoveryDecision.Blocked(FreshnessRecoveryReason.REPEATED_OUTLIER)
            }
            return FreshnessRecoveryDecision.Blocked(
                FreshnessRecoveryReason.NOT_RECOVERABLE_HOLD,
                reason.wireValue,
            )
        }
        val accuracy = input.accuracyMeters
            ?: return FreshnessRecoveryDecision.Blocked(FreshnessRecoveryReason.MISSING_ACCURACY)
        if (accuracy > input.effectiveAccuracyThresholdMeters ||
            accuracy > input.config.anchoredRecoveryAccuracyCeilingMeters
        ) {
            return FreshnessRecoveryDecision.Blocked(FreshnessRecoveryReason.BAD_ACCURACY)
        }
        val anchor = input.anchor ?: return FreshnessRecoveryDecision.Blocked(FreshnessRecoveryReason.NO_ANCHOR)
        if (!isPlausibleMove(anchor = anchor, candidate = input.candidateLocation, config = input.config)) {
            return FreshnessRecoveryDecision.Blocked(FreshnessRecoveryReason.IMPLAUSIBLE_MOVE)
        }
        return null
    }

    private fun isPlausibleMove(
        anchor: RecoveryAnchorState,
        candidate: Location,
        config: PositioningRecoveryConfig,
    ): Boolean {
        val elapsedSec = ((candidate.time - anchor.timestampMs).coerceAtLeast(0L) / 1_000.0)
            .takeIf { it > 0.0 }
            ?: return true
        val anchorLocation = anchor.toLocation(providerPrefix = "recovery_anchor")
        val distanceMeters = anchorLocation.distanceTo(candidate).toDouble().coerceAtLeast(0.0)
        val accuracyBufferMeters = (anchor.accuracyMeters ?: 0f) +
            (if (candidate.hasAccuracy()) candidate.accuracy else 0f)
        val maxRecoverableDistance = maxOf(
            anchor.radiusMeters.toDouble(),
            config.recoverySpeedCapMps * elapsedSec * 1.25 + accuracyBufferMeters,
        )
        return distanceMeters <= maxRecoverableDistance
    }
}

class FreshnessRecoveryController {
    private var probeActive: Boolean = false
    private var probeStartedAtMs: Long = 0L
    private var promotableProbeFixes: Int = 0
    private var lastLoggedDecision: String? = null

    fun reset() {
        probeActive = false
        probeStartedAtMs = 0L
        promotableProbeFixes = 0
        lastLoggedDecision = null
    }

    fun evaluate(input: FreshnessRecoveryInput): FreshnessRecoveryDecision {
        val decision = FreshnessRecoveryPolicy.evaluate(
            input = input,
            probeActive = probeActive,
            probeStartedAtMs = probeStartedAtMs,
            promotableProbeFixes = promotableProbeFixes,
        )
        when (decision) {
            is FreshnessRecoveryDecision.ProbeStarted -> {
                probeActive = true
                probeStartedAtMs = input.nowMs
                promotableProbeFixes = 0
            }
            is FreshnessRecoveryDecision.ProbeWait -> promotableProbeFixes++
            FreshnessRecoveryDecision.CommitAnchor -> {
                promotableProbeFixes++
            }
            FreshnessRecoveryDecision.Inactive -> reset()
            is FreshnessRecoveryDecision.Blocked -> {
                if (decision.reason == FreshnessRecoveryReason.PROBE_EXPIRED) {
                    // Probe window elapsed without a promotable commit. Reset so the
                    // next overdue cycle can start a fresh probe rather than staying
                    // permanently stuck in the expired state.
                    reset()
                }
                // Otherwise keep the probe alive for later fixes.
            }
        }
        return decision
    }

    fun shouldLog(decision: FreshnessRecoveryDecision): Boolean {
        val key = decision.telemetryValue
        if (key == lastLoggedDecision) return false
        lastLoggedDecision = key
        return true
    }
}
