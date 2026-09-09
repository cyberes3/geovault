package com.geovault.tracker.positioning.ingest

import android.location.Location
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointDecision
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.FilterReason
import com.geovault.tracker.policy.filter.LocationFilterReasonPolicy
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class LocalReanchorEvent(
    val streamKey: String,
    val policyReason: String?,
    val rejectStreak: Long,
    val anchorAgeMs: Long,
)

internal data class PositioningDecisionTrace(
    val trackId: String,
    val event: TrackPoint,
    val decision: TrackPointDecision,
    val nowMs: Long,
) {
    fun summary(): String {
        val metrics = decision.metrics
        return "track=$trackId ts=${event.timeMs} lat=${event.latitude} lon=${event.longitude} " +
            "acc=${event.accuracyMeters ?: -1f} accepted=${decision.accepted} " +
            "emission=${decision.emissionDecision} reject=${decision.rejectReason ?: "none"} " +
            "policy=${metrics?.reason ?: "none"} raw=${metrics?.rawDistanceMeters ?: -1.0} " +
            "effective=${metrics?.effectiveDistanceMeters ?: -1.0} dt=${metrics?.elapsedSeconds ?: -1.0} " +
            "speed=${metrics?.impliedSpeedMps ?: -1.0} committedLat=${metrics?.committedLatitude ?: "none"} " +
            "committedLon=${metrics?.committedLongitude ?: "none"} now=$nowMs"
    }
}

internal class PositioningDecisionTraceBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val traces = ArrayDeque<PositioningDecisionTrace>()

    @Synchronized
    fun add(trace: PositioningDecisionTrace) {
        traces.addLast(trace)
        while (traces.size > capacity) traces.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<PositioningDecisionTrace> = traces.toList()

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}

class LocalTrackPointStateCoordinator(
    private val onForcedLocalReanchor: (LocalReanchorEvent) -> Unit = {},
) {
    private val traceBuffer = PositioningDecisionTraceBuffer()
    private val eventFactory = LocalTrackPointFactory()
    private val acceptanceState = LocalTrackAcceptanceState()
    private val reanchorPolicy = LocalTrackReanchorPolicy()
    private val trackValidator = TrackContinuationValidator()

    @Synchronized
    fun resetSession(trackId: String) {
        resetLocalSession(trackId)
    }

    fun eventForLocation(
        trackId: String,
        location: Location,
        isMockLocation: Boolean,
        nowMs: Long,
    ): TrackPoint {
        return eventFactory.fromLocation(
            trackId = trackId,
            location = location,
            isMockLocation = isMockLocation,
            nowMs = nowMs,
        )
    }

    @Synchronized
    fun evaluate(
        trackId: String,
        event: TrackPoint,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        config: LocationFilterConfig,
    ): TrackPointDecision {
        val decision = TrackPointCrossSourceState.withLock {
            val streamKey = localStreamKey(trackId)
            val currentPreviousByTrack = TrackPointCrossSourceState.previous(trackId)
            var decision = TrackPointPolicyEngine.evaluate(
                event = event,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                config = config,
            )
            var effectivePreviousByTrack = currentPreviousByTrack
            val reanchorEvent = if (!decision.accepted) {
                reanchorPolicy.resolve(
                    streamKey = streamKey,
                    reason = decision.rejectReason,
                    policyReason = decision.metrics?.reason,
                    previousByTrack = effectivePreviousByTrack,
                    nowMs = nowMs,
                    currentJumpRejectStreak = acceptanceState.jumpRejectStreak(streamKey),
                )
            } else {
                null
            }
            if (reanchorEvent != null) {
                onForcedLocalReanchor(reanchorEvent)
                resetLocalSession(trackId)
                effectivePreviousByTrack = null
                decision = TrackPointPolicyEngine.evaluate(
                    event = event,
                    nowMs = nowMs,
                    nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                    config = config,
                )
            }
            if (!decision.accepted || decision.canonicalEvent == null) {
                acceptanceState.updateJumpRejectStreak(streamKey, decision.rejectReason)
                return@withLock decision
            }
            val canonical = decision.canonicalEvent
            trackValidator.validate(effectivePreviousByTrack, canonical)?.let { rejectReason ->
                acceptanceState.updateJumpRejectStreak(streamKey, rejectReason)
                return@withLock decision.copy(
                    accepted = false,
                    canonicalEvent = null,
                    rejectReason = rejectReason,
                )
            }
            acceptCanonical(trackId = trackId, streamKey = streamKey, canonical = canonical)
            decision.copy(canonicalEvent = canonical)
        }
        recordDecision(trackId = trackId, event = event, decision = decision, nowMs = nowMs)
        return decision
    }

    internal fun recentDecisionTrace(): List<PositioningDecisionTrace> = traceBuffer.snapshot()

    private fun recordDecision(
        trackId: String,
        event: TrackPoint,
        decision: TrackPointDecision,
        nowMs: Long,
    ) {
        val trace = PositioningDecisionTrace(
            trackId = trackId,
            event = event,
            decision = decision,
            nowMs = nowMs,
        )
        traceBuffer.add(trace)
        if (!decision.accepted || decision.metrics?.reason?.contains("resume", ignoreCase = true) == true) {
            GeoVaultCaptureLog.i(TAG, "positioning_decision_trace ${trace.summary()}")
        }
    }

    fun validateBypass(trackId: String, canonical: TrackPoint): TrackPointRejectReason? {
        return TrackPointCrossSourceState.withLock {
            trackValidator.validate(TrackPointCrossSourceState.previous(trackId), canonical)
        }
    }

    fun acceptBypass(trackId: String, canonical: TrackPoint, config: LocationFilterConfig) {
        TrackPointCrossSourceState.withLock {
            acceptCanonical(trackId = trackId, streamKey = localStreamKey(trackId), canonical = canonical)
        }
        TrackPointPolicyEngine.seedAccepted(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            event = canonical,
            config = config,
        )
    }

    private fun resetLocalSession(trackId: String) {
        val streamKey = localStreamKey(trackId)
        acceptanceState.reset(streamKey)
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        TrackPointCrossSourceState.resetTrack(trackId)
    }

    private fun acceptCanonical(trackId: String, streamKey: String, canonical: TrackPoint) {
        acceptanceState.accept(streamKey, canonical)
        TrackPointCrossSourceState.update(trackId, canonical)
    }

    private fun localStreamKey(trackId: String): String {
        return "${TrackPointSource.LOCAL_GPS}:$trackId"
    }

    companion object {
        private const val TAG = "LocalTrackPointState"
    }
}

private class LocalTrackPointFactory {
    fun fromLocation(
        trackId: String,
        location: Location,
        isMockLocation: Boolean,
        nowMs: Long,
    ): TrackPoint {
        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(location.time, nowMs)
        val timestampSkewMs = abs(normalizedTimestampMs - nowMs)
        val timestampForPolicyMs = if (
            isMockLocation &&
            timestampSkewMs > PositioningPolicyConfig.MOCK_TIMESTAMP_SKEW_TOLERANCE_MS
        ) {
            nowMs
        } else {
            normalizedTimestampMs
        }
        return TrackPoint(
            provenance = TrackPointSource.LOCAL_GPS,
            trackerId = trackId,
            longitude = location.longitude,
            latitude = location.latitude,
            timeMs = timestampForPolicyMs,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            gpsSpeedMps = if (location.hasSpeed()) location.speed else null,
            gpsBearingDeg = if (location.hasBearing()) location.bearing else null,
        )
    }
}

private class LocalTrackAcceptanceState {
    private val jumpRejectStreakByStream = ConcurrentHashMap<String, AtomicLong>()

    fun reset(streamKey: String) {
        jumpRejectStreakByStream.remove(streamKey)
    }

    fun accept(streamKey: String, canonical: TrackPoint) {
        jumpRejectStreakByStream.remove(streamKey)
    }

    fun jumpRejectStreak(streamKey: String): Long {
        return jumpRejectStreakByStream[streamKey]?.get() ?: 0L
    }

    fun updateJumpRejectStreak(streamKey: String, reason: TrackPointRejectReason?) {
        if (reason == TrackPointRejectReason.JUMP) {
            jumpRejectStreakByStream.getOrPut(streamKey) { AtomicLong(0L) }.incrementAndGet()
        } else {
            jumpRejectStreakByStream.remove(streamKey)
        }
    }
}

private class LocalTrackReanchorPolicy {
    fun resolve(
        streamKey: String,
        reason: TrackPointRejectReason?,
        policyReason: String?,
        previousByTrack: TrackPoint?,
        nowMs: Long,
        currentJumpRejectStreak: Long,
    ): LocalReanchorEvent? {
        if (reason != TrackPointRejectReason.JUMP) return null
        if (isExpectedRecoveryReason(policyReason)) return null
        val previous = previousByTrack ?: return null
        val anchorAgeMs = CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = previous.timeMs,
            eventElapsedRealtimeNanos = previous.elapsedRealtimeNanos,
        )
        if (anchorAgeMs < PositioningPolicyConfig.LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS) return null
        val nextStreak = currentJumpRejectStreak + 1L
        if (nextStreak < PositioningPolicyConfig.LOCAL_STALL_REJECT_STREAK_THRESHOLD) return null
        return LocalReanchorEvent(
            streamKey = streamKey,
            policyReason = policyReason,
            rejectStreak = nextStreak,
            anchorAgeMs = anchorAgeMs,
        )
    }

    private fun isExpectedRecoveryReason(policyReason: String?): Boolean {
        return LocationFilterReasonPolicy.blocksForcedLocalReanchor(FilterReason.fromWire(policyReason))
    }
}

private class TrackContinuationValidator {
    fun validate(
        previousByTrack: TrackPoint?,
        canonical: TrackPoint,
    ): TrackPointRejectReason? {
        val previous = previousByTrack ?: return null
        if (
            canonical.timeMs == previous.timeMs &&
            canonical.longitude == previous.longitude &&
            canonical.latitude == previous.latitude
        ) {
            return TrackPointRejectReason.DUPLICATE
        }
        if (canonical.timeMs < previous.timeMs) {
            return TrackPointRejectReason.OUT_OF_ORDER
        }
        return null
    }
}
