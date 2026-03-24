package com.geovault.tracker.pipeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.math.abs

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
    private const val MOCK_TIMESTAMP_SKEW_TOLERANCE_MS = 5 * 60 * 1000L
    private const val LOCAL_REAL_MAX_JUMP_SPEED_MPS = 60.0
    private const val LOCAL_MOCK_MAX_JUMP_SPEED_MPS = 10_000.0
    private const val LOCAL_REAL_MAX_BURST_DISTANCE_METERS = 300.0
    private const val LOCAL_REAL_BURST_WINDOW_SECONDS = 10.0
    private const val LOCAL_MOCK_MAX_BURST_DISTANCE_METERS = 20_000.0
    private const val LOCAL_MOCK_BURST_WINDOW_SECONDS = 120.0
    private const val ACCEPT_LOG_SAMPLE_INTERVAL = 250L
    private const val LOCAL_STALL_REJECT_STREAK_THRESHOLD = 6L
    private const val LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS = 3 * 60 * 1000L
    private val logger = Logger.getLogger(TrackPointPipeline::class.java.name)

    private val localProfile = TrackPointSourceProfile(
        config = TrackPointPolicyConfig(
            maxAccuracyMeters = 200f,
            degradedAccuracyMultiplier = 1f,
            maxFutureSkewMs = 5 * 60 * 1000L,
            maxJumpSpeedMps = LOCAL_REAL_MAX_JUMP_SPEED_MPS,
            maxBurstDistanceMeters = LOCAL_REAL_MAX_BURST_DISTANCE_METERS,
            burstWindowSeconds = LOCAL_REAL_BURST_WINDOW_SECONDS,
            rollingWindowSize = 5,
            outlierPolicy = TrackPointOutlierPolicy.STRICT,
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
            maxBurstDistanceMeters = 600.0,
            burstWindowSeconds = 15.0,
            rollingWindowSize = 5,
            outlierPolicy = TrackPointOutlierPolicy.OFF,
            freshnessTtlMs = REMOTE_FRESHNESS_TTL_MS,
            normalizeSecondsTimestamps = true
        )
    )

    private val lastAcceptedByStream = ConcurrentHashMap<String, TrackPointEvent>()
    private val acceptedHistoryByStream = ConcurrentHashMap<String, ConcurrentLinkedDeque<TrackPointEvent>>()
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
    private val localJumpRejectStreakByStream = ConcurrentHashMap<String, AtomicLong>()

    fun process(event: TrackPointEvent, nowMs: Long = System.currentTimeMillis()): TrackPointDecision {
        val profile = when (event.source) {
            TrackPointSource.LOCAL_GPS -> localProfile
            TrackPointSource.REMOTE_STREAM -> remoteProfile
        }
        return processWithConfig(
            event = event,
            config = profile.config,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = null
        )
    }

    fun processLocalGps(
        event: TrackPointEvent,
        maxAccuracyMeters: Float,
        freshnessTtlMs: Long,
        isMockLocation: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        nowElapsedRealtimeNanos: Long? = null
    ): TrackPointDecision {
        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(event.timestampMs, nowMs)
        val timestampSkewMs = abs(normalizedTimestampMs - nowMs)
        val timestampForPolicyMs = if (isMockLocation && timestampSkewMs > MOCK_TIMESTAMP_SKEW_TOLERANCE_MS) {
            nowMs
        } else {
            normalizedTimestampMs
        }
        val maxJumpSpeedMps = if (isMockLocation) LOCAL_MOCK_MAX_JUMP_SPEED_MPS else LOCAL_REAL_MAX_JUMP_SPEED_MPS
        val maxBurstDistanceMeters = if (isMockLocation) {
            LOCAL_MOCK_MAX_BURST_DISTANCE_METERS
        } else {
            LOCAL_REAL_MAX_BURST_DISTANCE_METERS
        }
        val burstWindowSeconds = if (isMockLocation) {
            LOCAL_MOCK_BURST_WINDOW_SECONDS
        } else {
            LOCAL_REAL_BURST_WINDOW_SECONDS
        }
        return processWithConfig(
            event = event.copy(timestampMs = timestampForPolicyMs),
            config = TrackPointPolicyConfig(
                maxAccuracyMeters = maxAccuracyMeters,
                degradedAccuracyMultiplier = 1f,
                allowDegradedAccuracy = false,
                requireAccuracyForAcceptance = true,
                maxFutureSkewMs = 5 * 60 * 1000L,
                maxJumpSpeedMps = maxJumpSpeedMps,
                maxBurstDistanceMeters = maxBurstDistanceMeters,
                burstWindowSeconds = burstWindowSeconds,
                rollingWindowSize = 5,
                outlierPolicy = if (isMockLocation) TrackPointOutlierPolicy.OFF else TrackPointOutlierPolicy.ADJUST,
                freshnessTtlMs = freshnessTtlMs,
                normalizeSecondsTimestamps = false
            ),
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
    }

    fun processWithConfig(
        event: TrackPointEvent,
        config: TrackPointPolicyConfig,
        nowMs: Long = System.currentTimeMillis(),
        nowElapsedRealtimeNanos: Long? = null
    ): TrackPointDecision {
        val sanitizedConfig = TrackPointPolicyCoercion.sanitize(config)
        val streamKey = "${event.source}:${event.trackId}"
        var previousByStream = lastAcceptedByStream[streamKey]
        var historyByStream = acceptedHistoryByStream[streamKey]?.toList() ?: emptyList()
        var previousByTrack = lastAcceptedByTrack[event.trackId]
        var decision = TrackPointPolicyEngine.evaluate(
            event = event,
            previous = previousByStream,
            history = historyByStream,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            rawConfig = sanitizedConfig
        )
        if (!decision.accepted &&
            shouldForceLocalStallReanchor(
                event = event,
                reason = decision.rejectReason,
                previousByTrack = previousByTrack,
                nowMs = nowMs,
                streamKey = streamKey
            )
        ) {
            logger.warning(
                "Ingress stall recovery: resetting local session anchor " +
                    "trackId=${event.trackId} reason=${decision.rejectReason} " +
                    "anchorAgeMs=${nowMs - (previousByTrack?.timestampMs ?: 0L)}"
            )
            resetLocalSession(event.trackId)
            previousByStream = null
            historyByStream = emptyList()
            previousByTrack = null
            decision = TrackPointPolicyEngine.evaluate(
                event = event,
                previous = null,
                history = emptyList(),
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                rawConfig = sanitizedConfig
            )
        }
        if (!decision.accepted || decision.canonicalEvent == null) {
            reject(decision.rejectReason)
            updateLocalRejectStreak(
                streamKey = streamKey,
                source = event.source,
                reason = decision.rejectReason
            )
            logRejection(
                event = event,
                nowMs = nowMs,
                config = sanitizedConfig,
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
                config = sanitizedConfig,
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
                config = sanitizedConfig,
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
        appendHistory(streamKey = streamKey, event = canonical, windowSize = sanitizedConfig.rollingWindowSize)
        lastAcceptedByTrack[event.trackId] = canonical
        clearLocalRejectStreak(streamKey, event.source)
        acceptedCount.incrementAndGet()
        logAccepted(
            event = event,
            canonical = canonical,
            decision = decision,
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

    fun resetLocalSession(trackId: String) {
        val streamKey = "${TrackPointSource.LOCAL_GPS}:$trackId"
        lastAcceptedByStream.remove(streamKey)
        acceptedHistoryByStream.remove(streamKey)
        lastAcceptedByTrack.remove(trackId)
        localJumpRejectStreakByStream.remove(streamKey)
    }

    fun resetForTests() {
        lastAcceptedByStream.clear()
        acceptedHistoryByStream.clear()
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
        localJumpRejectStreakByStream.clear()
    }

    private fun shouldForceLocalStallReanchor(
        event: TrackPointEvent,
        reason: TrackPointRejectReason?,
        previousByTrack: TrackPointEvent?,
        nowMs: Long,
        streamKey: String
    ): Boolean {
        if (event.source != TrackPointSource.LOCAL_GPS) return false
        if (reason != TrackPointRejectReason.JUMP) return false
        val previous = previousByTrack ?: return false
        val anchorAgeMs = nowMs - previous.timestampMs
        if (anchorAgeMs < LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS) return false
        val nextStreak = (localJumpRejectStreakByStream[streamKey]?.get() ?: 0L) + 1L
        return nextStreak >= LOCAL_STALL_REJECT_STREAK_THRESHOLD
    }

    private fun updateLocalRejectStreak(
        streamKey: String,
        source: TrackPointSource,
        reason: TrackPointRejectReason?
    ) {
        if (source != TrackPointSource.LOCAL_GPS) return
        if (reason == TrackPointRejectReason.JUMP) {
            localJumpRejectStreakByStream.getOrPut(streamKey) { AtomicLong(0L) }.incrementAndGet()
            return
        }
        localJumpRejectStreakByStream.remove(streamKey)
    }

    private fun clearLocalRejectStreak(streamKey: String, source: TrackPointSource) {
        if (source != TrackPointSource.LOCAL_GPS) return
        localJumpRejectStreakByStream.remove(streamKey)
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

    private fun appendHistory(streamKey: String, event: TrackPointEvent, windowSize: Int) {
        val history = acceptedHistoryByStream.getOrPut(streamKey) { ConcurrentLinkedDeque() }
        history.addLast(event)
        val maxHistory = windowSize.coerceIn(3, 20)
        while (history.size > maxHistory) {
            history.removeFirst()
        }
    }

    private fun logAccepted(
        event: TrackPointEvent,
        canonical: TrackPointEvent,
        decision: TrackPointDecision,
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
                "orderingKey=${canonical.orderingKey} adjusted=${decision.adjusted} " +
                "adjustReason=${decision.adjustmentReason ?: "none"}"
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

