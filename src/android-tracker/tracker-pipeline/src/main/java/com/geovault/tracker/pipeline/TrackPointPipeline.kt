package com.geovault.tracker.pipeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

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
    private const val REMOTE_FRESHNESS_TTL_MS = 30 * 60 * 1000L
    private const val ACCEPT_LOG_SAMPLE_INTERVAL = 250L
    private val logger = Logger.getLogger(TrackPointPipeline::class.java.name)

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
            freshnessTtlMs = REMOTE_FRESHNESS_TTL_MS,
            normalizeSecondsTimestamps = true
        )
    )

    private val lastAcceptedByStream = ConcurrentHashMap<String, TrackPointEvent>()
    private val lastAcceptedByTrack = ConcurrentHashMap<String, TrackPointEvent>()
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
        val previousByStream = lastAcceptedByStream[streamKey]
        val previousByTrack = lastAcceptedByTrack[event.trackId]
        val decision = TrackPointPolicyEngine.evaluate(
            event = event,
            previous = previousByStream,
            nowMs = nowMs,
            config = config
        )
        if (!decision.accepted || decision.canonicalEvent == null) {
            reject(decision.rejectReason)
            logRejection(
                event = event,
                nowMs = nowMs,
                config = config,
                reason = decision.rejectReason,
                previousByStream = previousByStream,
                previousByTrack = previousByTrack,
                canonical = decision.canonicalEvent
            )
            return decision
        }

        val canonical = decision.canonicalEvent.copy(orderingKey = orderingCounter.incrementAndGet())
        if (isDuplicateAgainstTrack(previousByTrack, canonical)) {
            reject(TrackPointRejectReason.DUPLICATE)
            logRejection(
                event = event,
                nowMs = nowMs,
                config = config,
                reason = TrackPointRejectReason.DUPLICATE,
                previousByStream = previousByStream,
                previousByTrack = previousByTrack,
                canonical = canonical
            )
            return TrackPointDecision(
                accepted = false,
                canonicalEvent = null,
                quality = canonical.quality,
                rejectReason = TrackPointRejectReason.DUPLICATE
            )
        }
        if (isOutOfOrderAgainstTrack(previousByTrack, canonical)) {
            reject(TrackPointRejectReason.OUT_OF_ORDER)
            logRejection(
                event = event,
                nowMs = nowMs,
                config = config,
                reason = TrackPointRejectReason.OUT_OF_ORDER,
                previousByStream = previousByStream,
                previousByTrack = previousByTrack,
                canonical = canonical
            )
            return TrackPointDecision(
                accepted = false,
                canonicalEvent = null,
                quality = canonical.quality,
                rejectReason = TrackPointRejectReason.OUT_OF_ORDER
            )
        }

        lastAcceptedByStream[streamKey] = canonical
        lastAcceptedByTrack[event.trackId] = canonical
        acceptedCount.incrementAndGet()
        logAccepted(
            event = event,
            canonical = canonical,
            previousByStream = previousByStream,
            previousByTrack = previousByTrack
        )
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
        lastAcceptedByTrack.clear()
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

    private fun isOutOfOrderAgainstTrack(previousByTrack: TrackPointEvent?, canonical: TrackPointEvent): Boolean {
        val previousTs = previousByTrack?.timestampMs ?: return false
        return canonical.timestampMs < previousTs
    }

    private fun isDuplicateAgainstTrack(previousByTrack: TrackPointEvent?, canonical: TrackPointEvent): Boolean {
        val previous = previousByTrack ?: return false
        return canonical.timestampMs == previous.timestampMs &&
            canonical.lon == previous.lon &&
            canonical.lat == previous.lat
    }

    private fun logAccepted(
        event: TrackPointEvent,
        canonical: TrackPointEvent,
        previousByStream: TrackPointEvent?,
        previousByTrack: TrackPointEvent?
    ) {
        val accepted = acceptedCount.get()
        if (accepted % ACCEPT_LOG_SAMPLE_INTERVAL != 0L &&
            canonical.quality == TrackPointQuality.HIGH_CONFIDENCE
        ) {
            return
        }
        logger.info(
            "Ingress decision=ACCEPT source=${event.source} trackId=${event.trackId} " +
                "rawTs=${event.timestampMs} normalizedTs=${canonical.timestampMs} " +
                "prevStreamTs=${previousByStream?.timestampMs ?: 0L} " +
                "prevTrackTs=${previousByTrack?.timestampMs ?: 0L} quality=${canonical.quality} " +
                "orderingKey=${canonical.orderingKey}"
        )
    }

    private fun logRejection(
        event: TrackPointEvent,
        nowMs: Long,
        config: TrackPointPolicyConfig,
        reason: TrackPointRejectReason?,
        previousByStream: TrackPointEvent?,
        previousByTrack: TrackPointEvent?,
        canonical: TrackPointEvent?
    ) {
        val normalizedTimestampMs = canonical?.timestampMs ?: normalizeTimestampForLogging(
            rawTimestampMs = event.timestampMs,
            nowMs = nowMs,
            config = config
        )
        logger.warning(
            "Ingress decision=REJECT source=${event.source} trackId=${event.trackId} reason=${reason ?: "UNKNOWN"} " +
                "rawTs=${event.timestampMs} normalizedTs=$normalizedTimestampMs " +
                "prevStreamTs=${previousByStream?.timestampMs ?: 0L} " +
                "prevTrackTs=${previousByTrack?.timestampMs ?: 0L} accuracy=${event.accuracyMeters ?: -1f}"
        )
    }

    private fun normalizeTimestampForLogging(
        rawTimestampMs: Long,
        nowMs: Long,
        config: TrackPointPolicyConfig
    ): Long {
        return if (config.normalizeSecondsTimestamps) {
            CanonicalTimeNormalizer.normalizeTimestampMs(rawTimestampMs, nowMs)
        } else if (rawTimestampMs <= 0L) {
            nowMs
        } else {
            rawTimestampMs
        }
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

