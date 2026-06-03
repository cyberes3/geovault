package com.geovault.tracker.services

import android.location.Location
import androidx.core.location.LocationCompat
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.policy.TrackPointDecisionMetrics
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import com.geovault.tracker.settings.TrackerSettings

data class LocationIngestResult(
    val accepted: Boolean,
    val emissionDecision: TrackPointEmissionDecision = if (accepted) {
        TrackPointEmissionDecision.COMMIT
    } else {
        TrackPointEmissionDecision.REJECT
    },
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
    private val positioningEngine = PositioningEngine(onForcedLocalReanchor)

    @Synchronized
    fun resetSession(trackId: String) {
        positioningEngine.resetSession(trackId)
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
        filterConfig: LocationFilterConfig = PositioningPolicyConfig.ingestConfig(
            maxAccuracyMeters = effectiveAccuracyFilterMeters,
            motionMode = motionMode,
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
                    emissionDecision = decision.emissionDecision,
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
            positioningEngine.eventForLocation(
                trackId = trackId,
                location = location,
                isMockLocation = isMockLocation,
                nowMs = nowMs
            )
        } else {
            null
        }
        if (bypassCanonical != null) {
            when (positioningEngine.validateBypass(trackId = trackId, canonical = bypassCanonical)) {
                TrackPointRejectReason.DUPLICATE -> {
                    return ignored(
                        previousAcceptedLocation = previousAcceptedLocation,
                        accuracy = accuracy,
                        propsJson = propsJson,
                        rejectReason = TrackPointRejectReason.DUPLICATE,
                        currentSessionDistanceMeters = totalDistanceMeters
                    )
                }
                TrackPointRejectReason.OUT_OF_ORDER -> {
                    return ignored(
                        previousAcceptedLocation = previousAcceptedLocation,
                        accuracy = accuracy,
                        propsJson = propsJson,
                        rejectReason = TrackPointRejectReason.OUT_OF_ORDER,
                        currentSessionDistanceMeters = totalDistanceMeters
                    )
                }
                else -> Unit
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
            positioningEngine.acceptBypass(
                trackId = trackId,
                canonical = bypassCanonical,
                config = filterConfig,
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
        filterConfig: LocationFilterConfig,
        isMockLocation: Boolean,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): com.geovault.tracker.policy.TrackPointDecision {
        val event = positioningEngine.eventForLocation(
            trackId = trackId,
            location = location,
            isMockLocation = isMockLocation,
            nowMs = nowMs,
        )
        // Pipeline derives "previous" from pipeline-local accepted state.
        // This intentionally avoids anchoring policy to bypass-only points.
        return positioningEngine.evaluate(
            trackId = trackId,
            event = event,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            config = filterConfig,
        )
    }

    private fun ignored(
        previousAcceptedLocation: Location?,
        accuracy: Float?,
        propsJson: String?,
        rejectReason: TrackPointRejectReason?,
        emissionDecision: TrackPointEmissionDecision = TrackPointEmissionDecision.REJECT,
        currentSessionDistanceMeters: Float,
        policyMetrics: TrackPointDecisionMetrics? = null,
    ): LocationIngestResult {
        return LocationIngestResult(
            accepted = false,
            emissionDecision = emissionDecision,
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
            emissionDecision = TrackPointEmissionDecision.SNAP_INTERNAL,
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
