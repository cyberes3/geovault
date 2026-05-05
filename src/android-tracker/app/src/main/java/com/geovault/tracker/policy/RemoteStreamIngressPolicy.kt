package com.geovault.tracker.policy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Remote-stream ingress policy for parity-safe stream filtering and ordering.
 */
object RemoteStreamIngressPolicy {
    private const val REMOTE_FRESHNESS_TTL_MS = 30L * 60L * 1000L

    private val lastAcceptedByStream = ConcurrentHashMap<String, TrackPointEvent>()
    private val acceptedHistoryByStream = ConcurrentHashMap<String, ConcurrentLinkedDeque<TrackPointEvent>>()
    private val orderingCounter = AtomicLong(0L)
    private var subscribedTrackIds: Set<String> = emptySet()

    private val profile = TrackPointPolicyConfig(
        maxAccuracyMeters = 200f,
        degradedAccuracyMultiplier = 3f,
        maxFutureSkewMs = 5L * 60L * 1000L,
        maxJumpSpeedMps = 130.0,
        maxBurstDistanceMeters = 600.0,
        burstWindowSeconds = 15.0,
        rollingWindowSize = 5,
        outlierPolicy = TrackPointOutlierPolicy.OFF,
        freshnessTtlMs = REMOTE_FRESHNESS_TTL_MS,
        normalizeSecondsTimestamps = false
    )

    fun process(event: TrackPointEvent, nowMs: Long): TrackPointEvent? {
        return TrackPointCrossSourceState.withLock {
            val streamKey = "${event.source}:${event.trackId}"
            val previousByStream = lastAcceptedByStream[streamKey]
            val previousByTrack = TrackPointCrossSourceState.previous(event.trackId)
            val historyByStream = acceptedHistoryByStream[streamKey]?.toList() ?: emptyList()

            val decision = TrackPointPolicyEngine.evaluate(
                event = event,
                previous = previousByStream,
                history = historyByStream,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = null,
                rawConfig = profile
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
            appendHistory(streamKey = streamKey, event = orderedCanonical, windowSize = profile.rollingWindowSize)
            orderedCanonical
        }
    }

    fun resetTrack(trackId: String) {
        val streamKey = "${TrackPointSource.REMOTE_STREAM}:$trackId"
        lastAcceptedByStream.remove(streamKey)
        acceptedHistoryByStream.remove(streamKey)
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
        acceptedHistoryByStream.clear()
        orderingCounter.set(0L)
        subscribedTrackIds = emptySet()
        TrackPointCrossSourceState.resetForTests()
    }

    private fun appendHistory(streamKey: String, event: TrackPointEvent, windowSize: Int) {
        val history = acceptedHistoryByStream.getOrPut(streamKey) { ConcurrentLinkedDeque() }
        history.addLast(event)
        val maxHistory = windowSize.coerceIn(3, 20)
        while (history.size > maxHistory) {
            history.removeFirst()
        }
    }
}
