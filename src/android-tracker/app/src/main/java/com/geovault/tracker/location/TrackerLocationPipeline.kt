package com.geovault.tracker.location

import android.location.Location
import com.geovault.tracker.policy.TrackPointRejectReason
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
 * Ordered fix-ingest pipeline: primary ingest, auto-motion retry, outlier pre-check,
 * then freshness recovery bypass ingest.
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

        val repeatedOutlierSuppressed = if (input.ingestMode == FixIngestMode.Live) {
            evaluateRepeatedOutlierSuppressed(result = result, input = input)
        } else {
            false
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
                    repeatedOutlierSuppressed = repeatedOutlierSuppressed,
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
            }
        }

        return TrackerLocationPipelineOutput(
            result = result,
            motionContext = motionContext,
            motionModeChanged = motionModeChanged,
            autoMotionHandling = autoMotionHandling,
            repeatedOutlierSuppressed = repeatedOutlierSuppressed,
            freshnessRecoveryDecision = freshnessRecoveryDecision,
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

    private fun evaluateRepeatedOutlierSuppressed(
        result: LocationIngestResult,
        input: TrackerLocationPipelineInput,
    ): Boolean {
        if (result.accepted) return false
        val rejectedForLock = result.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
            result.rejectReason == TrackPointRejectReason.STALE
        if (!rejectedForLock) return false
        return repeatedOutlierSuppressor.evaluate(
            candidate = input.location,
            anchor = input.outlierSuppressorAnchor,
            effectiveAccuracyThresholdMeters = input.motionContext.effectiveAccuracyThresholdMeters,
            nowMs = input.nowMs,
        ).suppress
    }
}
