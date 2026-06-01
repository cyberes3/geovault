package com.geovault.tracker.location

import com.geovault.common.geo.GeoMath
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.services.TrackingMotionMode

enum class LowAccuracyFallbackArmDecision {
    START_TIMER,
    KEEP_TIMER,
    INELIGIBLE,
}

enum class LowAccuracyFallbackEmitDecision {
    EMIT,
    WAIT,
    DISABLED,
    NO_CANDIDATE,
    DUPLICATE_CANDIDATE,
}

enum class LowAccuracyFallbackLoopDecision(val telemetryValue: String) {
    COMMIT_ANCHOR("commit-anchor"),
    WAIT("wait"),
    DISABLED("disabled"),
    PROBE("probe"),
    DUPLICATE_CANDIDATE("duplicate-candidate"),
}

/**
 * Timer-backed fallback state while the filter reports `low-accuracy` rejects.
 *
 * The timer keeps the recovery loop alive; emission remains gated by service
 * freshness and duplicate checks rather than by every rejected sample.
 */
internal class LowAccuracyFallbackCoordinator(
    private val configProvider: () -> PositioningRecoveryConfig = {
        PositioningPresets.forMotionMode(TrackingMotionMode.WALKING).recoveryConfig(maxLocalPointGapMs = 90_000L)
    },
) {
    private data class CandidateFingerprint(
        val latitude: Double,
        val longitude: Double,
        val timestampMs: Long
    )

    private var awaitingLock: Boolean = false
    private var latestCandidate: CandidateFingerprint? = null
    private var lastEmittedCandidate: CandidateFingerprint? = null

    @Synchronized
    fun onRejectedFixForLock(
        fallbackEligible: Boolean,
        candidateLatitude: Double,
        candidateLongitude: Double,
        candidateTimestampMs: Long
    ): LowAccuracyFallbackArmDecision {
        if (!fallbackEligible) return LowAccuracyFallbackArmDecision.INELIGIBLE
        latestCandidate = CandidateFingerprint(
            latitude = candidateLatitude,
            longitude = candidateLongitude,
            timestampMs = candidateTimestampMs
        )
        val shouldStartTimer = !awaitingLock
        awaitingLock = true
        return if (shouldStartTimer) {
            LowAccuracyFallbackArmDecision.START_TIMER
        } else {
            LowAccuracyFallbackArmDecision.KEEP_TIMER
        }
    }

    @Synchronized
    fun onAcceptedFix() {
        awaitingLock = false
        latestCandidate = null
        lastEmittedCandidate = null
    }

    @Synchronized
    fun onTrackingStopped() {
        awaitingLock = false
        latestCandidate = null
        lastEmittedCandidate = null
    }

    @Synchronized
    fun onFallbackTimerStopped() {
        awaitingLock = false
        latestCandidate = null
    }

    @Synchronized
    fun evaluateEmit(fallbackEligible: Boolean, hasCandidate: Boolean): LowAccuracyFallbackEmitDecision {
        if (!fallbackEligible) return LowAccuracyFallbackEmitDecision.DISABLED
        if (!hasCandidate) return LowAccuracyFallbackEmitDecision.NO_CANDIDATE
        if (!awaitingLock) return LowAccuracyFallbackEmitDecision.WAIT
        val latest = latestCandidate ?: return LowAccuracyFallbackEmitDecision.NO_CANDIDATE
        val emitted = lastEmittedCandidate ?: return LowAccuracyFallbackEmitDecision.EMIT
        if (latest.timestampMs - emitted.timestampMs >= configProvider().fallbackDuplicateTimeDeltaMs) {
            return LowAccuracyFallbackEmitDecision.EMIT
        }
        return if (distanceMeters(latest, emitted) >= configProvider().fallbackDuplicateDistanceMeters) {
            LowAccuracyFallbackEmitDecision.EMIT
        } else {
            LowAccuracyFallbackEmitDecision.DUPLICATE_CANDIDATE
        }
    }

    @Synchronized
    fun evaluateLoop(fallbackEligible: Boolean, hasCandidate: Boolean): LowAccuracyFallbackLoopDecision {
        return when (evaluateEmit(fallbackEligible = fallbackEligible, hasCandidate = hasCandidate)) {
            LowAccuracyFallbackEmitDecision.EMIT -> LowAccuracyFallbackLoopDecision.COMMIT_ANCHOR
            LowAccuracyFallbackEmitDecision.WAIT -> LowAccuracyFallbackLoopDecision.WAIT
            LowAccuracyFallbackEmitDecision.DISABLED -> LowAccuracyFallbackLoopDecision.DISABLED
            LowAccuracyFallbackEmitDecision.NO_CANDIDATE -> LowAccuracyFallbackLoopDecision.PROBE
            LowAccuracyFallbackEmitDecision.DUPLICATE_CANDIDATE -> LowAccuracyFallbackLoopDecision.DUPLICATE_CANDIDATE
        }
    }

    @Synchronized
    fun hasPendingCandidate(): Boolean {
        return awaitingLock && latestCandidate != null
    }

    @Synchronized
    fun onFallbackEmitted(
        candidateLatitude: Double,
        candidateLongitude: Double,
        candidateTimestampMs: Long
    ) {
        lastEmittedCandidate = CandidateFingerprint(
            latitude = candidateLatitude,
            longitude = candidateLongitude,
            timestampMs = candidateTimestampMs
        )
    }

    private fun distanceMeters(a: CandidateFingerprint, b: CandidateFingerprint): Float {
        return GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude).toFloat()
    }
}
