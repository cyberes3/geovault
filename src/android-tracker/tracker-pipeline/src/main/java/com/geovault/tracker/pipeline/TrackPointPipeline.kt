package com.geovault.tracker.pipeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class IngressStats(
    val accepted: Long,
    val rejected: Long,
    val rejectedInvalidCoordinates: Long,
    val rejectedOutOfOrder: Long,
    val rejectedDuplicate: Long,
    val rejectedBadAccuracy: Long,
    val rejectedTooFarFuture: Long,
    val rejectedJump: Long,
    val rejectedStale: Long
)

internal data class TrackPointSourceProfile(
    val config: TrackPointPolicyConfig
)

object TrackPointPipeline {
    private val localProfile = TrackPointSourceProfile(
        config = TrackPointPolicyConfig(
            maxAccuracyMeters = 200f,
            degradedAccuracyMultiplier = 1f,
            maxFutureSkewMs = 5 * 60 * 1000L,
            maxJumpSpeedMps = 100.0,
            freshnessTtlMs = null,
            normalizeSecondsTimestamps = true
        )
    )
    private val remoteProfile = TrackPointSourceProfile(
        config = TrackPointPolicyConfig(
            maxAccuracyMeters = 200f,
            degradedAccuracyMultiplier = 3f,
            maxFutureSkewMs = 5 * 60 * 1000L,
            maxJumpSpeedMps = 130.0,
            freshnessTtlMs = null,
            normalizeSecondsTimestamps = true
        )
    )

    private val lastAcceptedByStream = ConcurrentHashMap<String, TrackPointEvent>()
    private val acceptedCount = AtomicLong(0L)
    private val rejectedCount = AtomicLong(0L)
    private val rejectedInvalidCoordinates = AtomicLong(0L)
    private val rejectedOutOfOrder = AtomicLong(0L)
    private val rejectedDuplicate = AtomicLong(0L)
    private val rejectedBadAccuracy = AtomicLong(0L)
    private val rejectedTooFarFuture = AtomicLong(0L)
    private val rejectedJump = AtomicLong(0L)
    private val rejectedStale = AtomicLong(0L)
    private val orderingCounter = AtomicLong(0L)

    fun process(event: TrackPointEvent, nowMs: Long = System.currentTimeMillis()): TrackPointDecision {
        val profile = when (event.source) {
            TrackPointSource.LOCAL_GPS -> localProfile
            TrackPointSource.REMOTE_STREAM -> remoteProfile
        }
        return processWithConfig(event = event, config = profile.config, nowMs = nowMs)
    }

    fun processLocalGps(
        event: TrackPointEvent,
        maxAccuracyMeters: Float,
        maxJumpSpeedMps: Double,
        freshnessTtlMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): TrackPointDecision {
        return processWithConfig(
            event = event,
            config = TrackPointPolicyConfig(
                maxAccuracyMeters = maxAccuracyMeters,
                degradedAccuracyMultiplier = 4f,
                maxFutureSkewMs = 5 * 60 * 1000L,
                maxJumpSpeedMps = maxJumpSpeedMps,
                freshnessTtlMs = freshnessTtlMs,
                normalizeSecondsTimestamps = false
            ),
            nowMs = nowMs
        )
    }

    fun processWithConfig(
        event: TrackPointEvent,
        config: TrackPointPolicyConfig,
        nowMs: Long = System.currentTimeMillis()
    ): TrackPointDecision {
        val streamKey = "${event.source}:${event.trackId}"
        val previous = lastAcceptedByStream[streamKey]
        val decision = TrackPointPolicyEngine.evaluate(
            event = event,
            previous = previous,
            nowMs = nowMs,
            config = config
        )
        if (!decision.accepted || decision.canonicalEvent == null) {
            reject(decision.rejectReason)
            return decision
        }

        val canonical = decision.canonicalEvent.copy(orderingKey = orderingCounter.incrementAndGet())
        lastAcceptedByStream[streamKey] = canonical
        acceptedCount.incrementAndGet()
        return decision.copy(canonicalEvent = canonical)
    }

    fun stats(): IngressStats {
        return IngressStats(
            accepted = acceptedCount.get(),
            rejected = rejectedCount.get(),
            rejectedInvalidCoordinates = rejectedInvalidCoordinates.get(),
            rejectedOutOfOrder = rejectedOutOfOrder.get(),
            rejectedDuplicate = rejectedDuplicate.get(),
            rejectedBadAccuracy = rejectedBadAccuracy.get(),
            rejectedTooFarFuture = rejectedTooFarFuture.get(),
            rejectedJump = rejectedJump.get(),
            rejectedStale = rejectedStale.get()
        )
    }

    fun resetForTests() {
        lastAcceptedByStream.clear()
        acceptedCount.set(0L)
        rejectedCount.set(0L)
        rejectedInvalidCoordinates.set(0L)
        rejectedOutOfOrder.set(0L)
        rejectedDuplicate.set(0L)
        rejectedBadAccuracy.set(0L)
        rejectedTooFarFuture.set(0L)
        rejectedJump.set(0L)
        rejectedStale.set(0L)
        orderingCounter.set(0L)
    }

    private fun reject(reason: TrackPointRejectReason?) {
        rejectedCount.incrementAndGet()
        when (reason) {
            TrackPointRejectReason.INVALID_COORDINATES -> rejectedInvalidCoordinates.incrementAndGet()
            TrackPointRejectReason.OUT_OF_ORDER -> rejectedOutOfOrder.incrementAndGet()
            TrackPointRejectReason.DUPLICATE -> rejectedDuplicate.incrementAndGet()
            TrackPointRejectReason.BAD_ACCURACY -> rejectedBadAccuracy.incrementAndGet()
            TrackPointRejectReason.TOO_FAR_FUTURE -> rejectedTooFarFuture.incrementAndGet()
            TrackPointRejectReason.JUMP -> rejectedJump.incrementAndGet()
            TrackPointRejectReason.STALE -> rejectedStale.incrementAndGet()
            null -> Unit
        }
    }
}

