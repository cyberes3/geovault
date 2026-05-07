package com.geovault.tracker.policy

import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.LocationFilterPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Remote-stream ingress policy. Owns the cross-track de-dupe and ordering
 * concerns that are *outside* the per-stream positioning filter. The
 * heavy lifting (RSS distance, accCap, kinCap, Kalman, anomaly score) is
 * delegated to [TrackPointPolicyEngine] which routes to a per-stream
 * [com.geovault.tracker.policy.filter.LocationFilter] internally.
 */
object RemoteStreamIngressPolicy {
    private const val REMOTE_FRESHNESS_TTL_MS = 30L * 60L * 1000L

    private val lastAcceptedByStream = ConcurrentHashMap<String, TrackPointEvent>()
    private val orderingCounter = AtomicLong(0L)
    private var subscribedTrackIds: Set<String> = emptySet()

    private val profile = LocationFilterConfig.Default.copy(
        policy = LocationFilterPolicy.PassThrough,
        trackingAccuracyThresholdMeters = 200.0,
        maxImpliedSpeedMps = 130.0,
        maxBurstDistanceMeters = 600.0,
        burstWindowSeconds = 15.0,
        maxFutureSkewMs = 5L * 60L * 1000L,
        freshnessTtlMs = REMOTE_FRESHNESS_TTL_MS,
        normalizeSecondsTimestamps = false,
    )

    fun process(event: TrackPointEvent, nowMs: Long): TrackPointEvent? {
        return TrackPointCrossSourceState.withLock {
            val streamKey = streamKey(event)
            val previousByTrack = TrackPointCrossSourceState.previous(event.trackId)

            val decision = TrackPointPolicyEngine.evaluate(
                event = event,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = null,
                config = profile,
            )
            val canonical = decision.canonicalEvent ?: return@withLock null

            if (previousByTrack != null) {
                val duplicateAcrossTrack = canonical.timestampMs == previousByTrack.timestampMs &&
                    canonical.lon == previousByTrack.lon &&
                    canonical.lat == previousByTrack.lat
                if (duplicateAcrossTrack) return@withLock null
                if (canonical.timestampMs < previousByTrack.timestampMs) return@withLock null
            }

            val orderedCanonical = canonical.copy(orderingKey = orderingCounter.incrementAndGet())
            lastAcceptedByStream[streamKey] = orderedCanonical
            TrackPointCrossSourceState.update(event.trackId, orderedCanonical)
            orderedCanonical
        }
    }

    fun resetTrack(trackId: String) {
        val streamKey = "${TrackPointSource.REMOTE_STREAM}:${trackId.trim()}"
        lastAcceptedByStream.remove(streamKey)
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
        lastAcceptedByStream.clear()
        orderingCounter.set(0L)
        subscribedTrackIds = emptySet()
        TrackPointPolicyEngine.resetAll()
        TrackPointCrossSourceState.resetForTests()
    }

    private fun streamKey(event: TrackPointEvent): String =
        "${event.source.name}:${event.trackId.trim()}"
}
