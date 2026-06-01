package com.geovault.tracker.location

import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.TrackPointRejectReason
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
        val evidence = metrics
            ?.takeIf { isEvidenceReason(policyReason) }
            ?.let { evidenceGate.evaluate(metrics = it, eventTimeMs = eventTimeMs) }
        if (evidence != null && metrics != null) {
            lastCapEvidenceAtMs = eventTimeMs
            lastEvidenceWallClockMs = nowMs
            val modeBefore = engine.snapshot().mode
            val output = engine.onMotionEvidence(
                speedMps = evidence.speedMps,
                eventTimeMs = eventTimeMs,
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

        if (isNeutralHoldReason(policyReason)) {
            return AutoMotionRejectHandling.Preserved(
                rejectReason = rejectReason,
                policyReason = policyReason,
                elapsedSinceCapEvidenceMs = elapsedSinceCapEvidence(nowMs),
            )
        }

        if (isTransientReject(rejectReason) && isWithinCapEvidenceWindow(nowMs)) {
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

    private fun isEvidenceReason(reason: String?): Boolean {
        return LocationFilterReasonPolicy.isCapEvidence(reason)
    }

    private fun isNeutralHoldReason(reason: String?): Boolean {
        return LocationFilterReasonPolicy.isSpatialHold(reason)
    }

    private fun isTransientReject(rejectReason: TrackPointRejectReason?): Boolean {
        return rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
            rejectReason == TrackPointRejectReason.STALE
    }

    private fun isWithinCapEvidenceWindow(nowMs: Long): Boolean {
        return lastCapEvidenceAtMs > 0L && nowMs - lastCapEvidenceAtMs <= streakPreserveWindowMs
    }

    private fun elapsedSinceCapEvidence(nowMs: Long): Long {
        return lastCapEvidenceAtMs.takeIf { it > 0L }?.let { nowMs - it } ?: -1L
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
