package com.geovault.tracker.services

import android.location.Location
import androidx.core.location.LocationCompat
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointCrossSourceState
import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.settings.TrackerSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class LocalReanchorEvent(
    val streamKey: String,
    val policyReason: String?,
    val rejectStreak: Long,
    val anchorAgeMs: Long,
)

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

class LocationIngestCoordinator(
    private val locationDao: LocationDao,
    private val onForcedLocalReanchor: (LocalReanchorEvent) -> Unit = {},
) {
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
        filterConfig: LocationFilterConfig = TrackingPolicyProfiles.ingestConfig(
            maxAccuracyMeters = effectiveAccuracyFilterMeters,
            motionMode = motionMode,
            isMockLocation = isMockLocation,
        ),
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
                filterConfig = filterConfig,
                isMockLocation = isMockLocation,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            )
            if (!decision.accepted || decision.canonicalEvent == null) {
                if (decision.emissionDecision == TrackPointEmissionDecision.SNAP_INTERNAL) {
                    return internalSnap(
                        rawLocation = location,
                        previousAcceptedLocation = previousAcceptedLocation,
                        propsJson = propsJson,
                        currentSessionDistanceMeters = totalDistanceMeters,
                        queuedTrackerId = queuedTrackerId,
                        sessionVisibleBoundaryId = sessionVisibleBoundaryId,
                        policyMetrics = decision.metrics,
                    )
                }
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
            seedPolicyFilterFromBypass(
                trackId = trackId,
                canonical = bypassCanonical,
                filterConfig = filterConfig,
            )
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
        filterConfig: LocationFilterConfig,
        isMockLocation: Boolean,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): com.geovault.tracker.policy.TrackPointDecision {
        val event = trackPointEventForPolicy(trackId = trackId, location = location, isMockLocation = isMockLocation, nowMs = nowMs)
        // Pipeline derives "previous" from pipeline-local accepted state.
        // This intentionally avoids anchoring policy to bypass-only points.
        return evaluateWithState(
            trackId = trackId,
            event = event,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            config = filterConfig,
        )
    }

    @Synchronized
    private fun evaluateWithState(
        trackId: String,
        event: TrackPointEvent,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        config: LocationFilterConfig,
    ): com.geovault.tracker.policy.TrackPointDecision {
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
                    nowMs = nowMs
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

    private fun forcedLocalStallReanchorEvent(
        streamKey: String,
        reason: TrackPointRejectReason?,
        policyReason: String?,
        previousByTrack: TrackPointEvent?,
        nowMs: Long
    ): LocalReanchorEvent? {
        if (reason != TrackPointRejectReason.JUMP) return null
        if (isExpectedRecoveryReason(policyReason)) return null
        val previous = previousByTrack ?: return null
        val anchorAgeMs = nowMs - previous.timestampMs
        if (anchorAgeMs < TrackingPolicyProfiles.LOCAL_STALL_REANCHOR_MIN_ANCHOR_AGE_MS) return null
        val nextStreak = (jumpRejectStreakByStream[streamKey]?.get() ?: 0L) + 1L
        if (nextStreak < TrackingPolicyProfiles.LOCAL_STALL_REJECT_STREAK_THRESHOLD) return null
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
        return policyReason == "resume-unconfirmed" ||
            policyReason == "candidate-unconfirmed" ||
            policyReason == "speed-cap-unconfirmed" ||
            policyReason == "speed-cap-exceeded"
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

    private fun seedPolicyFilterFromBypass(
        trackId: String,
        canonical: TrackPointEvent,
        filterConfig: LocationFilterConfig,
    ) {
        TrackPointPolicyEngine.seedAccepted(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            event = canonical,
            config = filterConfig,
        )
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

    private fun internalSnap(
        rawLocation: Location,
        previousAcceptedLocation: Location?,
        propsJson: String?,
        currentSessionDistanceMeters: Float,
        queuedTrackerId: String,
        sessionVisibleBoundaryId: Long,
        policyMetrics: TrackPointDecisionMetrics?,
    ): LocationIngestResult {
        val internalLocation = Location(previousAcceptedLocation ?: rawLocation).apply {
            policyMetrics?.committedLatitude?.let { latitude = it }
            policyMetrics?.committedLongitude?.let { longitude = it }
            time = rawLocation.time
            elapsedRealtimeNanos = rawLocation.elapsedRealtimeNanos
            if (rawLocation.hasAccuracy()) accuracy = rawLocation.accuracy
            if (rawLocation.hasSpeed()) speed = rawLocation.speed
            if (rawLocation.hasBearing()) bearing = rawLocation.bearing
        }
        val visible = locationDao.getCurrentSessionCountForTracker(
            trackerId = queuedTrackerId,
            sessionBoundaryId = sessionVisibleBoundaryId,
        )
        return LocationIngestResult(
            accepted = true,
            rejectReason = null,
            adjustmentReason = TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED,
            pointPersisted = false,
            nextSessionDistanceMeters = currentSessionDistanceMeters,
            lastFilteredLocation = internalLocation,
            queuedPointsVisible = visible,
            lastAccuracyMeters = if (rawLocation.hasAccuracy()) rawLocation.accuracy else null,
            lastTrackedLatitude = previousAcceptedLocation?.latitude,
            lastTrackedLongitude = previousAcceptedLocation?.longitude,
            lastTrackedTimestampMs = previousAcceptedLocation?.time ?: 0L,
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
