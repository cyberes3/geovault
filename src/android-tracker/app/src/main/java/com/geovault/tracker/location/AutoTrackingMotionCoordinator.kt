package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.filter.FilterReason
import com.geovault.tracker.policy.filter.LocationFilterReasonPolicy
import com.geovault.tracker.services.TrackingMotionMode

class AutoTrackingMotionCoordinator(
    private val engine: AutoTrackingMotionEngine,
    private val evidenceGate: AutoTrackingMotionEvidenceGate,
    private val streakPreserveWindowMs: Long,
) {
    var lastCapEvidenceAtMs: Long = 0L
        private set

    var lastEvidenceWallClockMs: Long = 0L
        private set

    fun reset() {
        evidenceGate.reset()
        lastCapEvidenceAtMs = 0L
        lastEvidenceWallClockMs = 0L
    }

    fun clearEvidenceCandidate() {
        evidenceGate.reset()
    }

    fun onRejectedOrHeld(
        metrics: TrackPointDecisionMetrics?,
        rejectReason: TrackPointRejectReason?,
        eventTimeMs: Long,
        nowMs: Long,
    ): AutoMotionRejectHandling {
        val policyReason = metrics?.reason
        if (metrics != null && isEvidenceReason(policyReason)) {
            val evidence = evidenceGate.evaluate(metrics = metrics, eventTimeMs = eventTimeMs)
            if (evidence != null) {
                return promote(evidence = evidence, metrics = metrics, eventTimeMs = eventTimeMs, nowMs = nowMs)
            }
            // Gate has stored its first observation but the HANDSHAKE is not yet complete.
            // Preserve gate state and engine streak so the next cap-evidence fix can
            // finish the handshake. Do not reset the gate or call onRejectedFix here.
            return AutoMotionRejectHandling.Preserved(
                rejectReason = rejectReason,
                policyReason = policyReason,
                elapsedSinceCapEvidenceMs = elapsedSinceCapEvidence(nowMs),
            )
        }

        if (metrics != null && isStaleRelocationEvidenceReason(policyReason)) {
            val evidence = evidenceGate.evaluateStaleRelocation(metrics = metrics, eventTimeMs = eventTimeMs)
            if (evidence != null) {
                return promote(evidence = evidence, metrics = metrics, eventTimeMs = eventTimeMs, nowMs = nowMs)
            }
            // Same rationale as the cap-evidence branch above: a candidate-unconfirmed/
            // stale-relocation-unconfirmed fix that didn't complete a HANDSHAKE still
            // seeded (or preserved) one -- don't reset the gate or the engine streak.
            return AutoMotionRejectHandling.Preserved(
                rejectReason = rejectReason,
                policyReason = policyReason,
                elapsedSinceCapEvidenceMs = elapsedSinceCapEvidence(nowMs),
            )
        }

        if (isNeutralHoldReason(policyReason)) {
            return AutoMotionRejectHandling.Preserved(
                rejectReason = rejectReason,
                policyReason = policyReason,
                elapsedSinceCapEvidenceMs = elapsedSinceCapEvidence(nowMs),
            )
        }

        // A transient BAD_ACCURACY/STALE reject must not blow away an in-progress
        // cap-evidence HANDSHAKE seed. `isWithinCapEvidenceWindow` only covers the
        // window *after* the first evidence has already fired in this streak; before
        // that (lastEvidenceWallClockMs == 0L) it always returns false, so without
        // the `hasPendingObservation` check every interleaved low-accuracy fix reset
        // the gate's lone seed before a second, qualifying cap-evidence fix could
        // arrive to complete the handshake -- permanently blocking promotion in GPS
        // conditions where such rejects recur faster than the handshake can close.
        if (isTransientReject(rejectReason) &&
            (isWithinCapEvidenceWindow(nowMs) || evidenceGate.hasPendingObservation)
        ) {
            return AutoMotionRejectHandling.Preserved(
                rejectReason = rejectReason,
                policyReason = policyReason,
                elapsedSinceCapEvidenceMs = elapsedSinceCapEvidence(nowMs),
            )
        }

        evidenceGate.reset()
        return AutoMotionRejectHandling.Rejected(
            output = engine.onRejectedFix(eventTimeMs = nowMs),
            rejectReason = rejectReason,
            policyReason = policyReason,
        )
    }

    private fun promote(
        evidence: AutoTrackingMotionEvidence,
        metrics: TrackPointDecisionMetrics,
        eventTimeMs: Long,
        nowMs: Long,
    ): AutoMotionRejectHandling.Evidence {
        lastCapEvidenceAtMs = eventTimeMs
        lastEvidenceWallClockMs = nowMs
        val modeBefore = engine.snapshot().mode
        val output = engine.onMotionEvidence(
            speedMps = evidence.speedMps,
            eventTimeMs = nowMs,
            confidence = evidence.confidence,
        )
        return AutoMotionRejectHandling.Evidence(
            modeBefore = modeBefore,
            output = output,
            evidence = evidence,
            policyReason = metrics.reason,
            accuracyMeters = metrics.accuracyMeters,
            elapsedSeconds = metrics.elapsedSeconds,
        )
    }

    private fun isEvidenceReason(reason: String?): Boolean {
        return LocationFilterReasonPolicy.isCapEvidence(FilterReason.fromWire(reason))
    }

    private fun isStaleRelocationEvidenceReason(reason: String?): Boolean {
        return LocationFilterReasonPolicy.isStaleRelocationEvidence(FilterReason.fromWire(reason))
    }

    private fun isNeutralHoldReason(reason: String?): Boolean {
        return LocationFilterReasonPolicy.isSpatialHold(FilterReason.fromWire(reason))
    }

    private fun isTransientReject(rejectReason: TrackPointRejectReason?): Boolean {
        return rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
            rejectReason == TrackPointRejectReason.STALE
    }

    private fun isWithinCapEvidenceWindow(nowMs: Long): Boolean {
        return lastEvidenceWallClockMs > 0L && nowMs - lastEvidenceWallClockMs <= streakPreserveWindowMs
    }

    private fun elapsedSinceCapEvidence(nowMs: Long): Long {
        return lastEvidenceWallClockMs.takeIf { it > 0L }?.let { nowMs - it } ?: -1L
    }
}

sealed interface AutoMotionRejectHandling {
    data class Evidence(
        val modeBefore: TrackingMotionMode,
        val output: AutoTrackingEngineOutput,
        val evidence: AutoTrackingMotionEvidence,
        val policyReason: String?,
        val accuracyMeters: Float?,
        val elapsedSeconds: Double,
    ) : AutoMotionRejectHandling

    data class Preserved(
        val rejectReason: TrackPointRejectReason?,
        val policyReason: String?,
        val elapsedSinceCapEvidenceMs: Long,
    ) : AutoMotionRejectHandling

    data class Rejected(
        val output: AutoTrackingEngineOutput,
        val rejectReason: TrackPointRejectReason?,
        val policyReason: String?,
    ) : AutoMotionRejectHandling
}
