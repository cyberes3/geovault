package com.geovault.tracker.policy

import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.LocationFilterPolicy
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
    private var subscribedTrackIds: Set<String> = emptySet()

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

    fun process(event: TrackPointEvent, nowMs: Long): TrackPointEvent? {
        val trackId = event.trackId.trim()
        return TrackPointCrossSourceState.withLock {
            val previousByTrack = TrackPointCrossSourceState.previous(event.trackId)

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
                val duplicateAcrossTrack = canonical.timestampMs == previousByTrack.timestampMs &&
                    canonical.lon == previousByTrack.lon &&
                    canonical.lat == previousByTrack.lat
                if (duplicateAcrossTrack) {
                    RemoteTrackPointAdmissionDiagnostics.recordRejected(
                        RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING, "cross_track_duplicate", trackId
                    )
                    return@withLock null
                }
                if (canonical.timestampMs < previousByTrack.timestampMs) {
                    RemoteTrackPointAdmissionDiagnostics.recordRejected(
                        RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING, "cross_track_out_of_order", trackId
                    )
                    return@withLock null
                }
            }

            val orderedCanonical = canonical.copy(orderingKey = orderingCounter.incrementAndGet())
            TrackPointCrossSourceState.update(event.trackId, orderedCanonical)
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

    fun updateSubscribedTracks(trackIds: Collection<String>) {
        val normalized = trackIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        TrackPointCrossSourceState.withLock {
            val removed = subscribedTrackIds - normalized
            subscribedTrackIds = normalized
            removed.forEach(::resetTrack)
        }
    }

    fun startSubscriptionSession(trackIds: Collection<String>) {
        val normalized = trackIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        TrackPointCrossSourceState.withLock {
            val removed = subscribedTrackIds - normalized
            subscribedTrackIds = normalized
            (normalized + removed).forEach(::resetTrack)
        }
    }

    fun resetForTests() {
        orderingCounter.set(0L)
        subscribedTrackIds = emptySet()
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
