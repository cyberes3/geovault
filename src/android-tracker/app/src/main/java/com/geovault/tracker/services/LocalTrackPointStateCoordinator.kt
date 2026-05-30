package com.geovault.tracker.services

import android.location.Location
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.LocationFilterReasons
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class LocalReanchorEvent(
    val streamKey: String,
    val policyReason: String?,
    val rejectStreak: Long,
    val anchorAgeMs: Long,
)

class LocalTrackPointStateCoordinator(
    private val onForcedLocalReanchor: (LocalReanchorEvent) -> Unit = {},
) {
    private val lastAcceptedByStream = ConcurrentHashMap<String, TrackPointEvent>()
    private val jumpRejectStreakByStream = ConcurrentHashMap<String, AtomicLong>()

    @Synchronized
    fun resetSession(trackId: String) {
        resetLocalSession(trackId)
    }

    fun eventForLocation(
        trackId: String,
        location: Location,
        isMockLocation: Boolean,
        nowMs: Long,
    ): TrackPointEvent {
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
        return TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = location.longitude,
            lat = location.latitude,
            timestampMs = timestampForPolicyMs,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            gpsSpeedMps = if (location.hasSpeed()) location.speed else null,
            gpsBearingDeg = if (location.hasBearing()) location.bearing else null,
        )
    }

    @Synchronized
    fun evaluate(
        trackId: String,
        event: TrackPointEvent,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        config: LocationFilterConfig,
    ): TrackPointDecision {
        return TrackPointCrossSourceState.withLock {
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
                forcedLocalStallReanchorEvent(
                    streamKey = streamKey,
                    reason = decision.rejectReason,
                    policyReason = decision.metrics?.reason,
                    previousByTrack = effectivePreviousByTrack,
                    nowMs = nowMs,
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
                updateJumpRejectStreak(streamKey, decision.rejectReason)
                return@withLock decision
            }
            val canonical = decision.canonicalEvent
            validateAgainstTrack(effectivePreviousByTrack, canonical)?.let { rejectReason ->
                updateJumpRejectStreak(streamKey, rejectReason)
                return@withLock decision.copy(
                    accepted = false,
                    canonicalEvent = null,
                    rejectReason = rejectReason,
                )
            }
            acceptCanonical(trackId = trackId, streamKey = streamKey, canonical = canonical)
            decision.copy(canonicalEvent = canonical)
        }
    }

    fun validateBypass(trackId: String, canonical: TrackPointEvent): TrackPointRejectReason? {
        return TrackPointCrossSourceState.withLock {
            validateAgainstTrack(TrackPointCrossSourceState.previous(trackId), canonical)
        }
    }

    fun acceptBypass(trackId: String, canonical: TrackPointEvent, config: LocationFilterConfig) {
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

    private fun forcedLocalStallReanchorEvent(
        streamKey: String,
        reason: TrackPointRejectReason?,
        policyReason: String?,
        previousByTrack: TrackPointEvent?,
        nowMs: Long,
    ): LocalReanchorEvent? {
        if (reason != TrackPointRejectReason.JUMP) return null
        if (isExpectedRecoveryReason(policyReason)) return null
        val previous = previousByTrack ?: return null
        val anchorAgeMs = nowMs - previous.timestampMs
        if (anchorAgeMs < PositioningPolicyConfig.LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS) return null
        val nextStreak = (jumpRejectStreakByStream[streamKey]?.get() ?: 0L) + 1L
        if (nextStreak < PositioningPolicyConfig.LOCAL_STALL_REJECT_STREAK_THRESHOLD) return null
        return LocalReanchorEvent(
            streamKey = streamKey,
            policyReason = policyReason,
            rejectStreak = nextStreak,
            anchorAgeMs = anchorAgeMs,
        )
    }

    private fun updateJumpRejectStreak(streamKey: String, reason: TrackPointRejectReason?) {
        if (reason == TrackPointRejectReason.JUMP) {
            jumpRejectStreakByStream.getOrPut(streamKey) { AtomicLong(0L) }.incrementAndGet()
        } else {
            jumpRejectStreakByStream.remove(streamKey)
        }
    }

    private fun isExpectedRecoveryReason(policyReason: String?): Boolean {
        return policyReason == LocationFilterReasons.RESUME_UNCONFIRMED ||
            policyReason == LocationFilterReasons.CANDIDATE_UNCONFIRMED ||
            policyReason == LocationFilterReasons.SPEED_CAP_UNCONFIRMED ||
            policyReason == LocationFilterReasons.SPEED_CAP_EXCEEDED
    }

    private fun validateAgainstTrack(
        previousByTrack: TrackPointEvent?,
        canonical: TrackPointEvent,
    ): TrackPointRejectReason? {
        val previous = previousByTrack ?: return null
        if (
            canonical.timestampMs == previous.timestampMs &&
            canonical.lon == previous.lon &&
            canonical.lat == previous.lat
        ) {
            return TrackPointRejectReason.DUPLICATE
        }
        if (canonical.timestampMs < previous.timestampMs) {
            return TrackPointRejectReason.OUT_OF_ORDER
        }
        return null
    }

    private fun resetLocalSession(trackId: String) {
        val streamKey = localStreamKey(trackId)
        lastAcceptedByStream.remove(streamKey)
        jumpRejectStreakByStream.remove(streamKey)
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        TrackPointCrossSourceState.resetTrack(trackId)
    }

    private fun acceptCanonical(trackId: String, streamKey: String, canonical: TrackPointEvent) {
        lastAcceptedByStream[streamKey] = canonical
        TrackPointCrossSourceState.update(trackId, canonical)
        jumpRejectStreakByStream.remove(streamKey)
    }

    private fun localStreamKey(trackId: String): String {
        return "${TrackPointSource.LOCAL_GPS}:$trackId"
    }
}
