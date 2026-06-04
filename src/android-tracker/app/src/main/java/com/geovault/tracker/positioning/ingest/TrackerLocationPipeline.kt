package com.geovault.tracker.positioning.ingest

import android.location.Location
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.FreshnessRecoveryInput
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.filter.FilterReason
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings

enum class FixIngestMode {
    Live,
    PausedFreshness,
    FreshnessBypass,
}

data class TrackerLocationMotionContext(
    val motionMode: TrackingMotionMode,
    val filterConfig: LocationFilterConfig,
    val effectiveAccuracyThresholdMeters: Float,
)

data class TrackerLocationPipelineInput(
    val trackId: String,
    val location: Location,
    val settings: TrackerSettings,
    val motionContext: TrackerLocationMotionContext,
    val previousAcceptedLocation: Location?,
    val sessionVisibleBoundaryId: Long,
    val ingestMode: FixIngestMode,
    val propsJson: String?,
    val totalDistanceMeters: Float,
    val nowMs: Long,
    val nowElapsedRealtimeNanos: Long,
    val sessionStartTimeMs: Long,
    val isMockLocation: Boolean,
    val skipAdaptiveTrackingEffects: Boolean,
    val localRecoveryDue: Boolean,
    val recoveryConfig: PositioningRecoveryConfig,
    val recoveryAnchor: RecoveryAnchorState?,
    val outlierSuppressorAnchor: Location?,
) {
    val bypassFilters: Boolean
        get() = ingestMode == FixIngestMode.PausedFreshness || ingestMode == FixIngestMode.FreshnessBypass
}

data class TrackerLocationPipelineOutput(
    val result: LocationIngestResult,
    val motionContext: TrackerLocationMotionContext,
    val motionModeChanged: Boolean,
    val autoMotionHandling: AutoMotionRejectHandling?,
    val repeatedOutlierSuppressed: Boolean,
    val freshnessRecoveryDecision: FreshnessRecoveryDecision,
)

/**
 * Ordered fix-ingest pipeline: primary ingest, auto-motion retry, freshness recovery
 * (with repeatedOutlierSuppressed=false, matching monolithic TrackingService), then optional
 * bypass ingest. Outlier suppression runs on the reject path in [FixIngestSubsystem] after
 * freshness, not here.
 */
class TrackerLocationPipeline(
    private val locationIngestCoordinator: LocationIngestCoordinator,
    private val freshnessRecoveryController: FreshnessRecoveryController,
    private val repeatedOutlierSuppressor: RepeatedOutlierSuppressor,
) {
    fun processFix(
        input: TrackerLocationPipelineInput,
        onAutoMotionRejected: (LocationIngestResult, Location, Long) -> AutoMotionRejectHandling,
        refreshMotionContext: () -> TrackerLocationMotionContext,
        buildFreshnessRecoveryLocation: (
            RecoveryAnchorState,
            Location,
            Long,
            Long,
        ) -> Location,
    ): TrackerLocationPipelineOutput {
        if (input.ingestMode == FixIngestMode.PausedFreshness) {
            val result = ingest(input, input.motionContext)
            return TrackerLocationPipelineOutput(
                result = result,
                motionContext = input.motionContext,
                motionModeChanged = false,
                autoMotionHandling = null,
                repeatedOutlierSuppressed = false,
                freshnessRecoveryDecision = FreshnessRecoveryDecision.Inactive,
            )
        }

        var motionContext = input.motionContext
        var result = ingest(input, motionContext)
        var autoMotionHandling: AutoMotionRejectHandling? = null
        var motionModeChanged = false

        val runLiveRecovery = input.ingestMode == FixIngestMode.Live &&
            !input.skipAdaptiveTrackingEffects

        if (!result.accepted && runLiveRecovery) {
            autoMotionHandling = onAutoMotionRejected(result, input.location, input.nowMs)
            if (autoMotionHandling is AutoMotionRejectHandling.Evidence && autoMotionHandling.output.modeChanged) {
                motionContext = refreshMotionContext()
                motionModeChanged = true
                result = ingest(input, motionContext)
            }
        }

        val freshnessRecoveryDecision = if (input.ingestMode == FixIngestMode.Live) {
            freshnessRecoveryController.evaluate(
                FreshnessRecoveryInput(
                    localRecoveryDue = input.localRecoveryDue,
                    accepted = result.accepted,
                    pointPersisted = result.pointPersisted,
                    filterReason = FilterReason.fromWire(result.policyMetrics?.reason),
                    accuracyMeters = result.lastAccuracyMeters,
                    effectiveAccuracyThresholdMeters = motionContext.effectiveAccuracyThresholdMeters,
                    candidateLocation = input.location,
                    anchor = input.recoveryAnchor,
                    repeatedOutlierSuppressed = false,
                    nowMs = input.nowMs,
                    config = input.recoveryConfig,
                ),
            )
        } else {
            FreshnessRecoveryDecision.Inactive
        }

        if (freshnessRecoveryDecision == FreshnessRecoveryDecision.CommitAnchor) {
            val anchor = input.recoveryAnchor
            if (anchor != null) {
                val recoveryLocation = buildFreshnessRecoveryLocation(
                    anchor,
                    input.location,
                    input.nowMs,
                    input.nowElapsedRealtimeNanos,
                )
                result = locationIngestCoordinator.ingest(
                    trackId = input.trackId,
                    location = recoveryLocation,
                    settings = input.settings,
                    motionMode = motionContext.motionMode,
                    effectiveAccuracyFilterMeters = motionContext.effectiveAccuracyThresholdMeters,
                    previousAcceptedLocation = input.previousAcceptedLocation,
                    sessionVisibleBoundaryId = input.sessionVisibleBoundaryId,
                    bypassFilters = true,
                    propsJson = input.propsJson,
                    totalDistanceMeters = input.totalDistanceMeters,
                    queuedTrackerId = input.trackId,
                    nowMs = input.nowMs,
                    nowElapsedRealtimeNanos = input.nowElapsedRealtimeNanos,
                    sessionStartTimeMs = input.sessionStartTimeMs,
                    isMockLocation = input.isMockLocation,
                    filterConfig = motionContext.filterConfig,
                )
                if (result.pointPersisted) {
                    // The bypass committed the freshness-anchor point at the old parking
                    // location. The ingest already seeded the filter there, but the next
                    // live fix on the highway would appear as an implausible jump from
                    // that stale anchor. Re-seed the filter at the actual current GPS
                    // position so the filter evaluates subsequent fixes from where the
                    // device actually is now.
                    TrackPointPolicyEngine.seedAccepted(
                        source = TrackPointSource.LOCAL_GPS,
                        trackId = input.trackId,
                        event = buildCurrentPositionSeedEvent(input),
                        config = motionContext.filterConfig,
                    )
                }
            }
        }

        return TrackerLocationPipelineOutput(
            result = result,
            motionContext = motionContext,
            motionModeChanged = motionModeChanged,
            autoMotionHandling = autoMotionHandling,
            repeatedOutlierSuppressed = false,
            freshnessRecoveryDecision = freshnessRecoveryDecision,
        )
    }

    fun evaluateRepeatedOutlierSuppressedOnReject(
        result: LocationIngestResult,
        candidate: Location,
        anchor: Location?,
        effectiveAccuracyThresholdMeters: Float,
        nowMs: Long,
    ): Boolean {
        if (result.accepted) return false
        val rejectedForLock = result.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
            result.rejectReason == TrackPointRejectReason.STALE
        if (!rejectedForLock) return false
        return repeatedOutlierSuppressor.evaluate(
            candidate = candidate,
            anchor = anchor,
            effectiveAccuracyThresholdMeters = effectiveAccuracyThresholdMeters,
            nowMs = nowMs,
        ).suppress
    }

    private fun buildCurrentPositionSeedEvent(input: TrackerLocationPipelineInput): TrackPointEvent {
        val loc = input.location
        return TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = input.trackId,
            lon = loc.longitude,
            lat = loc.latitude,
            timestampMs = input.nowMs,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
            elapsedRealtimeNanos = loc.elapsedRealtimeNanos.takeIf { it > 0L }
                ?: input.nowElapsedRealtimeNanos,
            gpsSpeedMps = if (loc.hasSpeed()) loc.speed else null,
            gpsBearingDeg = if (loc.hasBearing()) loc.bearing else null,
        )
    }

    private fun ingest(
        input: TrackerLocationPipelineInput,
        motionContext: TrackerLocationMotionContext,
    ): LocationIngestResult {
        return locationIngestCoordinator.ingest(
            trackId = input.trackId,
            location = input.location,
            settings = input.settings,
            motionMode = motionContext.motionMode,
            effectiveAccuracyFilterMeters = motionContext.effectiveAccuracyThresholdMeters,
            previousAcceptedLocation = input.previousAcceptedLocation,
            sessionVisibleBoundaryId = input.sessionVisibleBoundaryId,
            bypassFilters = input.bypassFilters,
            propsJson = input.propsJson,
            totalDistanceMeters = input.totalDistanceMeters,
            queuedTrackerId = input.trackId,
            nowMs = input.nowMs,
            nowElapsedRealtimeNanos = input.nowElapsedRealtimeNanos,
            sessionStartTimeMs = input.sessionStartTimeMs,
            isMockLocation = input.isMockLocation,
            filterConfig = motionContext.filterConfig,
        )
    }

}
