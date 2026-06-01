package com.geovault.tracker.policy.filter

/**
 * Ordered regular-fix pipeline: stale relocation confirmation runs before
 * implied-speed cap handling, then movement-candidate hold, then policy commit.
 */
internal class RegularFixEvaluator(
    private val config: LocationFilterConfig,
    private val relocationRecoveryGate: RelocationRecoveryGate,
    private val speedCapRecoveryGate: SpeedCapRecoveryGate,
    private val movementCandidateGate: MovementCandidateGate,
    private val anchorHealthTracker: AnchorHealthTracker,
) {
    fun evaluate(
        input: LocationInput,
        previous: LocationInput,
        metrics: LocationMetrics,
        capCandidate: Double,
        relocationGateEnabled: Boolean,
        smoothDecisionDistance: (LocationMetrics, LocationInput) -> Double,
        resolveSpeedSpike: (LocationInput, LocationInput, LocationMetrics) -> LocationFilterResult,
        commitAccept: (LocationInput, FilterReason, LocationMetrics) -> LocationFilterResult,
        commitClip: (LocationInput, LocationInput, Double, FilterReason, LocationMetrics) -> LocationFilterResult,
        resolveConservative: (
            LocationInput,
            LocationInput,
            Double,
            LocationMetrics,
            Double,
        ) -> LocationFilterResult,
    ): LocationFilterResult {
        if (relocationGateEnabled) {
            when (relocationRecoveryGate.evaluate(input = input, previousAnchor = previous, config = config)) {
                RelocationRecoveryGate.Decision.ContinueRegular -> Unit
                RelocationRecoveryGate.Decision.Hold ->
                    return LocationFilterResult.hold(
                        reason = FilterReason.STALE_RELOCATION_UNCONFIRMED,
                        metrics = metrics,
                    )
                RelocationRecoveryGate.Decision.Confirmed -> return evaluate(
                    input = input,
                    previous = previous,
                    metrics = metrics,
                    capCandidate = capCandidate,
                    relocationGateEnabled = false,
                    smoothDecisionDistance = smoothDecisionDistance,
                    resolveSpeedSpike = resolveSpeedSpike,
                    commitAccept = commitAccept,
                    commitClip = commitClip,
                    resolveConservative = resolveConservative,
                )
            }
        }

        if (metrics.dtSeconds > 0.0 && metrics.impliedSpeedMps > config.maxImpliedSpeedMps) {
            return resolveSpeedSpike(input, previous, metrics)
        }
        speedCapRecoveryGate.reset()

        if (
            movementCandidateGate.assess(
                input = input,
                previousAnchor = previous,
                metrics = metrics,
                anchorSuspect = anchorHealthTracker.suspect,
            ) == MovementCandidateGate.Decision.Hold
        ) {
            return LocationFilterResult.hold(reason = FilterReason.CANDIDATE_UNCONFIRMED, metrics = metrics)
        }

        val decisionDistance = smoothDecisionDistance(metrics, input)

        return when (config.policy) {
            LocationFilterPolicy.PassThrough ->
                commitAccept(input, FilterReason.PASS_THROUGH, metrics)

            LocationFilterPolicy.Adjust ->
                if (decisionDistance <= capCandidate) {
                    commitAccept(input, FilterReason.WITHIN_CAP, metrics)
                } else {
                    commitClip(input, previous, capCandidate, FilterReason.ADJUST_CAP, metrics)
                }

            LocationFilterPolicy.Conservative -> resolveConservative(
                input,
                previous,
                capCandidate,
                metrics,
                decisionDistance,
            )
        }
    }
}
