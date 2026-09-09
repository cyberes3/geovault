package com.geovault.tracker.policy

import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.LocationFilterPolicy
import com.geovault.tracker.policy.filter.MovementCandidateConfig
import com.geovault.tracker.streaming.StreamingConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * Remote-stream ingress policy. Owns the cross-track de-dupe and ordering
 * concerns that are *outside* the per-stream positioning filter. The
 * heavy lifting (RSS distance, accCap, kinCap, Kalman, anomaly score) is
 * delegated to [TrackPointPolicyEngine] which routes to a per-stream
 * [com.geovault.tracker.policy.filter.LocationFilter] internally.
 */
object RemoteStreamIngressPolicy {
    private val orderingCounter = AtomicLong(0L)

    /**
     * Wall-clock timestamp (same clock as [process]'s `nowMs`) of the most recent successful
     * socket open; 0 = never connected. Deliberately the wall clock rather than
     * `SystemClock.elapsedRealtime()` — every caller already threads `nowMs` through, so reusing
     * it keeps this class free of any direct Android-framework call (relevant for plain-JVM unit
     * tests, which don't mock `SystemClock`).
     */
    private val connectedAtMs = AtomicLong(0L)

    private val profile = LocationFilterConfig.Default.copy(
        policy = LocationFilterPolicy.PassThrough,
        trackingAccuracyThresholdMeters = 200.0,
        maxImpliedSpeedMps = 130.0,
        maxBurstDistanceMeters = 600.0,
        burstWindowSeconds = 15.0,
        maxFutureSkewMs = StreamingConfig.maxFutureSkewMs,
        freshnessTtlMs = StreamingConfig.remoteFreshnessTtlMs,
        normalizeSecondsTimestamps = false,
        movementCandidate = MovementCandidateConfig.Disabled,
        staleAnchorMinAgeMs = Long.MAX_VALUE,
    )

    /**
     * RECONNECT-CATCHUP-BACKLOG: the server replays a backlog on (re)connect, and every backlogged
     * point is, by construction, older than "now" — often older than [StreamingConfig.remoteFreshnessTtlMs]
     * itself for a stream that was down for a while. Without a grace window, the freshness check
     * silently discarded the entire backlog: the map showed "streaming" but never advanced until a
     * genuinely fresh fix arrived. Called by the service the moment a socket finishes opening.
     */
    fun markConnected(nowMs: Long) {
        connectedAtMs.set(nowMs)
    }

    fun process(event: TrackPoint, nowMs: Long): TrackPoint? {
        val trackId = event.trackerId.trim()
        return TrackPointCrossSourceState.withLock {
            val previousByTrack = TrackPointCrossSourceState.previous(event.trackerId)

            val decision = TrackPointPolicyEngine.evaluate(
                event = event,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = null,
                config = effectiveConfig(nowMs),
            )
            val canonical = decision.canonicalEvent ?: run {
                RemoteTrackPointAdmissionDiagnostics.recordRejected(
                    RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING,
                    decision.rejectReason?.name?.lowercase() ?: "filtered",
                    trackId,
                )
                return@withLock null
            }

            if (previousByTrack != null) {
                val duplicateAcrossTrack = canonical.timeMs == previousByTrack.timeMs &&
                    canonical.longitude == previousByTrack.longitude &&
                    canonical.latitude == previousByTrack.latitude
                if (duplicateAcrossTrack) {
                    RemoteTrackPointAdmissionDiagnostics.recordRejected(
                        RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING, "cross_track_duplicate", trackId
                    )
                    return@withLock null
                }
                if (canonical.timeMs < previousByTrack.timeMs) {
                    RemoteTrackPointAdmissionDiagnostics.recordRejected(
                        RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING, "cross_track_out_of_order", trackId
                    )
                    return@withLock null
                }
            }

            val orderedCanonical = canonical.copy(orderingKey = orderingCounter.incrementAndGet())
            TrackPointCrossSourceState.update(event.trackerId, orderedCanonical)
            orderedCanonical
        }
    }

    fun resetTrack(trackId: String) {
        TrackPointPolicyEngine.resetStream(TrackPointSource.REMOTE_STREAM, trackId)
        TrackPointCrossSourceState.resetTrack(trackId)
    }

    fun resetTracks(trackIds: Collection<String>) {
        trackIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach(::resetTrack)
    }

    fun resetRemoteSession() {
        TrackPointCrossSourceState.withLock {
            orderingCounter.set(0L)
            connectedAtMs.set(0L)
            TrackPointPolicyEngine.resetSource(TrackPointSource.REMOTE_STREAM)
            TrackPointCrossSourceState.resetSource(TrackPointSource.REMOTE_STREAM)
        }
    }

    fun resetForTests() {
        orderingCounter.set(0L)
        connectedAtMs.set(0L)
        TrackPointPolicyEngine.resetAll()
        TrackPointCrossSourceState.resetForTests()
    }

    /** Relaxes the freshness TTL to "unbounded" for the grace window right after a (re)connect. */
    private fun effectiveConfig(nowMs: Long): LocationFilterConfig {
        val connectedAt = connectedAtMs.get()
        if (connectedAt <= 0L) return profile
        val ageSinceConnectMs = nowMs - connectedAt
        val withinReconnectGrace = ageSinceConnectMs in 0..StreamingConfig.reconnectFreshnessGraceMs
        return if (withinReconnectGrace) profile.copy(freshnessTtlMs = 0L) else profile
    }
}
