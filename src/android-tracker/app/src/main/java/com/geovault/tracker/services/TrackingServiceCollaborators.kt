package com.geovault.tracker.services

import android.location.Location
import androidx.core.location.LocationCompat
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.settings.TrackerSettings
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class LocationIngestResult(
    val accepted: Boolean,
    val rejectReason: TrackPointRejectReason? = null,
    val adjustmentReason: String? = null,
    val trackPointQuality: TrackPointQuality? = null,
    val pointPersisted: Boolean = false,
    val persistedRowId: Long? = null,
    val nextSessionDistanceMeters: Float,
    val lastFilteredLocation: Location?,
    val queuedPointsVisible: Int,
    val lastAccuracyMeters: Float?,
    val lastTrackedLatitude: Double?,
    val lastTrackedLongitude: Double?,
    val lastTrackedTimestampMs: Long,
    val lastTrackedPropsJson: String?,
    val policyMetrics: TrackPointDecisionMetrics? = null,
)

class LocationIngestCoordinator(private val locationDao: LocationDao) {
    private val lastAcceptedByStream = ConcurrentHashMap<String, TrackPointEvent>()
    private val jumpRejectStreakByStream = ConcurrentHashMap<String, AtomicLong>()

    @Synchronized
    fun resetSession(trackId: String) {
        resetLocalSession(trackId)
    }

    fun ingest(
        trackId: String,
        location: Location,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        effectiveAccuracyFilterMeters: Float = settings.accuracyFilterMeters,
        previousAcceptedLocation: Location?,
        sessionVisibleBoundaryId: Long,
        bypassFilters: Boolean,
        propsJson: String?,
        totalDistanceMeters: Float,
        queuedTrackerId: String,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        sessionStartTimeMs: Long = 0L,
        isMockLocation: Boolean = LocationCompat.isMock(location),
        activeMotionHint: Boolean = false,
    ): LocationIngestResult {
        require(queuedTrackerId.isNotBlank()) { "queuedTrackerId must not be blank" }
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        var resolvedQuality: TrackPointQuality? = null
        var policyMetrics: TrackPointDecisionMetrics? = null
        if (!bypassFilters) {
            val decision = evaluatePolicyDecision(
                trackId = trackId,
                location = location,
                previousAcceptedLocation = previousAcceptedLocation,
                maxAccuracyMeters = effectiveAccuracyFilterMeters,
                motionMode = motionMode,
                isMockLocation = isMockLocation,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                activeMotionHint = activeMotionHint,
            )
            if (!decision.accepted || decision.canonicalEvent == null) {
                return ignored(
                    previousAcceptedLocation = previousAcceptedLocation,
                    accuracy = accuracy,
                    propsJson = propsJson,
                    rejectReason = decision.rejectReason,
                    currentSessionDistanceMeters = totalDistanceMeters,
                    policyMetrics = decision.metrics,
                )
            }
            val canonical = decision.canonicalEvent
            policyMetrics = decision.metrics
            resolvedQuality = canonical.quality
            location.latitude = canonical.lat
            location.longitude = canonical.lon
            location.time = canonical.timestampMs
            if (canonical.elapsedRealtimeNanos != null) {
                location.elapsedRealtimeNanos = canonical.elapsedRealtimeNanos
            }
            if (decision.adjustmentReason == TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED) {
                val visible = locationDao.getCurrentSessionCountForTracker(
                    trackerId = queuedTrackerId,
                    sessionBoundaryId = sessionVisibleBoundaryId
                )
                val nextSessionDistanceMeters = computeNextSessionDistanceMeters(
                    currentSessionDistanceMeters = totalDistanceMeters,
                    previousAcceptedLocation = previousAcceptedLocation,
                    acceptedLocation = location
                )
                return LocationIngestResult(
                    accepted = true,
                    rejectReason = null,
                    adjustmentReason = decision.adjustmentReason,
                    trackPointQuality = canonical.quality,
                    pointPersisted = false,
                    persistedRowId = null,
                    nextSessionDistanceMeters = nextSessionDistanceMeters,
                    lastFilteredLocation = Location(location),
                    queuedPointsVisible = visible,
                    lastAccuracyMeters = accuracy,
                    lastTrackedLatitude = location.latitude,
                    lastTrackedLongitude = location.longitude,
                    lastTrackedTimestampMs = location.time,
                    lastTrackedPropsJson = propsJson,
                    policyMetrics = decision.metrics,
                )
            }
        }

        val bypassCanonical = if (bypassFilters) {
            trackPointEventForPolicy(
                trackId = trackId,
                location = location,
                isMockLocation = isMockLocation,
                nowMs = nowMs
            )
        } else {
            null
        }
        if (bypassCanonical != null) {
            val previousByTrack = TrackPointCrossSourceState.withLock {
                TrackPointCrossSourceState.previous(trackId)
            }
            if (isDuplicateAgainstTrack(previousByTrack, bypassCanonical)) {
                return ignored(
                    previousAcceptedLocation = previousAcceptedLocation,
                    accuracy = accuracy,
                    propsJson = propsJson,
                    rejectReason = TrackPointRejectReason.DUPLICATE,
                    currentSessionDistanceMeters = totalDistanceMeters
                )
            }
            if (isOutOfOrderAgainstTrack(previousByTrack, bypassCanonical)) {
                return ignored(
                    previousAcceptedLocation = previousAcceptedLocation,
                    accuracy = accuracy,
                    propsJson = propsJson,
                    rejectReason = TrackPointRejectReason.OUT_OF_ORDER,
                    currentSessionDistanceMeters = totalDistanceMeters
                )
            }
        }

        val nextSessionDistanceMeters = computeNextSessionDistanceMeters(
            currentSessionDistanceMeters = totalDistanceMeters,
            previousAcceptedLocation = previousAcceptedLocation,
            acceptedLocation = location
        )
        val queued = QueuedLocation.fromLocation(
            loc = location,
            trackerId = queuedTrackerId,
            totalDistanceMeters = nextSessionDistanceMeters,
            startTimestampMs = sessionStartTimeMs.takeIf { it > 0L },
        )
        val insertedId = locationDao.insert(queued)
        if (bypassCanonical != null) {
            updateAcceptedStateForLocalStream(trackId = trackId, canonical = bypassCanonical)
        }
        val visible = locationDao.getCurrentSessionCountForTracker(
            trackerId = queuedTrackerId,
            sessionBoundaryId = sessionVisibleBoundaryId
        )
        return LocationIngestResult(
            accepted = true,
            rejectReason = null,
            adjustmentReason = null,
            trackPointQuality = resolvedQuality,
            pointPersisted = true,
            persistedRowId = insertedId,
            nextSessionDistanceMeters = nextSessionDistanceMeters,
            lastFilteredLocation = Location(location),
            queuedPointsVisible = visible,
            lastAccuracyMeters = accuracy,
            lastTrackedLatitude = location.latitude,
            lastTrackedLongitude = location.longitude,
            lastTrackedTimestampMs = location.time,
            lastTrackedPropsJson = propsJson,
            policyMetrics = policyMetrics,
        )
    }

    private fun evaluatePolicyDecision(
        trackId: String,
        location: Location,
        previousAcceptedLocation: Location?,
        maxAccuracyMeters: Float,
        motionMode: TrackingMotionMode,
        isMockLocation: Boolean,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        activeMotionHint: Boolean,
    ): com.geovault.tracker.policy.TrackPointDecision {
        val event = trackPointEventForPolicy(trackId = trackId, location = location, isMockLocation = isMockLocation, nowMs = nowMs)
        // Pipeline derives "previous" from pipeline-local accepted state.
        // This intentionally avoids anchoring policy to bypass-only points.
        val config = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = maxAccuracyMeters,
            motionMode = motionMode,
            isMockLocation = isMockLocation
        )
        return evaluateWithState(
            trackId = trackId,
            event = event,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            config = config,
            activeMotionHint = activeMotionHint,
        )
    }

    @Synchronized
    private fun evaluateWithState(
        trackId: String,
        event: TrackPointEvent,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        config: LocationFilterConfig,
        activeMotionHint: Boolean = false,
    ): com.geovault.tracker.policy.TrackPointDecision {
        return TrackPointCrossSourceState.withLock {
            val streamKey = localStreamKey(trackId)
            val currentPreviousByTrack = TrackPointCrossSourceState.previous(trackId)
            var decision = TrackPointPolicyEngine.evaluate(
                event = event,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                config = config,
                activeMotionHint = activeMotionHint,
            )
            var effectivePreviousByTrack = currentPreviousByTrack
            if (!decision.accepted &&
                shouldForceLocalStallReanchor(
                    streamKey = streamKey,
                    reason = decision.rejectReason,
                    previousByTrack = effectivePreviousByTrack,
                    nowMs = nowMs
                )
            ) {
                resetLocalSession(trackId)
                effectivePreviousByTrack = null
                decision = TrackPointPolicyEngine.evaluate(
                    event = event,
                    nowMs = nowMs,
                    nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                    config = config,
                    activeMotionHint = activeMotionHint,
                )
            }
            if (!decision.accepted || decision.canonicalEvent == null) {
                updateJumpRejectStreak(streamKey, decision.rejectReason)
                return@withLock decision
            }
            val canonical = decision.canonicalEvent
            if (isDuplicateAgainstTrack(effectivePreviousByTrack, canonical)) {
                updateJumpRejectStreak(streamKey, TrackPointRejectReason.DUPLICATE)
                return@withLock decision.copy(
                    accepted = false,
                    canonicalEvent = null,
                    rejectReason = TrackPointRejectReason.DUPLICATE
                )
            }
            if (isOutOfOrderAgainstTrack(effectivePreviousByTrack, canonical)) {
                updateJumpRejectStreak(streamKey, TrackPointRejectReason.OUT_OF_ORDER)
                return@withLock decision.copy(
                    accepted = false,
                    canonicalEvent = null,
                    rejectReason = TrackPointRejectReason.OUT_OF_ORDER
                )
            }
            lastAcceptedByStream[streamKey] = canonical
            TrackPointCrossSourceState.update(trackId, canonical)
            jumpRejectStreakByStream.remove(streamKey)
            decision.copy(canonicalEvent = canonical)
        }
    }

    private fun shouldForceLocalStallReanchor(
        streamKey: String,
        reason: TrackPointRejectReason?,
        previousByTrack: TrackPointEvent?,
        nowMs: Long
    ): Boolean {
        if (reason != TrackPointRejectReason.JUMP) return false
        val previous = previousByTrack ?: return false
        val anchorAgeMs = nowMs - previous.timestampMs
        if (anchorAgeMs < TrackingPolicyProfiles.LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS) return false
        val nextStreak = (jumpRejectStreakByStream[streamKey]?.get() ?: 0L) + 1L
        return nextStreak >= TrackingPolicyProfiles.LOCAL_STALL_REJECT_STREAK_THRESHOLD
    }

    private fun updateJumpRejectStreak(streamKey: String, reason: TrackPointRejectReason?) {
        if (reason == TrackPointRejectReason.JUMP) {
            jumpRejectStreakByStream.getOrPut(streamKey) { AtomicLong(0L) }.incrementAndGet()
        } else {
            jumpRejectStreakByStream.remove(streamKey)
        }
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

    private fun resetLocalSession(trackId: String) {
        val streamKey = localStreamKey(trackId)
        lastAcceptedByStream.remove(streamKey)
        jumpRejectStreakByStream.remove(streamKey)
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, trackId)
        TrackPointCrossSourceState.resetTrack(trackId)
    }

    private fun localStreamKey(trackId: String): String {
        return "${TrackPointSource.LOCAL_GPS}:$trackId"
    }

    private fun updateAcceptedStateForLocalStream(
        trackId: String,
        canonical: TrackPointEvent,
    ) {
        TrackPointCrossSourceState.withLock {
            val streamKey = localStreamKey(trackId)
            lastAcceptedByStream[streamKey] = canonical
            TrackPointCrossSourceState.update(trackId, canonical)
            jumpRejectStreakByStream.remove(streamKey)
        }
    }

    private fun trackPointEventForPolicy(
        trackId: String,
        location: Location,
        isMockLocation: Boolean,
        nowMs: Long
    ): TrackPointEvent {
        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(location.time, nowMs)
        val timestampSkewMs = abs(normalizedTimestampMs - nowMs)
        val timestampForPolicyMs = if (
            isMockLocation &&
            timestampSkewMs > TrackingPolicyProfiles.MOCK_TIMESTAMP_SKEW_TOLERANCE_MS
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

    private fun ignored(
        previousAcceptedLocation: Location?,
        accuracy: Float?,
        propsJson: String?,
        rejectReason: TrackPointRejectReason?,
        currentSessionDistanceMeters: Float,
        policyMetrics: TrackPointDecisionMetrics? = null,
    ): LocationIngestResult {
        return LocationIngestResult(
            accepted = false,
            rejectReason = rejectReason,
            adjustmentReason = null,
            pointPersisted = false,
            nextSessionDistanceMeters = currentSessionDistanceMeters,
            lastFilteredLocation = previousAcceptedLocation,
            queuedPointsVisible = 0,
            lastAccuracyMeters = accuracy,
            lastTrackedLatitude = null,
            lastTrackedLongitude = null,
            lastTrackedTimestampMs = 0L,
            lastTrackedPropsJson = propsJson,
            policyMetrics = policyMetrics,
        )
    }

    private fun computeNextSessionDistanceMeters(
        currentSessionDistanceMeters: Float,
        previousAcceptedLocation: Location?,
        acceptedLocation: Location
    ): Float {
        val distanceDeltaMeters = if (previousAcceptedLocation != null) {
            previousAcceptedLocation.distanceTo(acceptedLocation).coerceAtLeast(0f)
        } else {
            0f
        }
        return currentSessionDistanceMeters + distanceDeltaMeters
    }
}
