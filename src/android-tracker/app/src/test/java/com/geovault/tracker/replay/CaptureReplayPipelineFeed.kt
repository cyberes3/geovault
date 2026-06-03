package com.geovault.tracker.replay

import android.location.Location
import com.geovault.tracker.db.LocationDao
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.policy.filter.LocationFilterConfig
import com.geovault.tracker.policy.filter.MotionProfileTuning
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.positioning.ingest.TrackerLocationPipeline
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.tracking.TrackingServiceConstants

class CaptureReplayPipelineFeed(
    private val session: CaptureReplaySession,
    private val pipeline: TrackerLocationPipeline,
    private val coordinator: LocationIngestCoordinator,
    val engine: AutoTrackingMotionEngine,
    private val motionCoordinator: AutoTrackingMotionCoordinator,
    private val settings: TrackerSettings = TrackerSettings(accuracyFilterMeters = 50f),
) {
    data class ReplayState(
        var previousAccepted: Location? = null,
        var motionMode: TrackingMotionMode = TrackingMotionMode.WALKING,
        var motionRetryCount: Int = 0,
        var lastOutputAccepted: Boolean = false,
    )

    fun replay(resetWallMs: Long): ReplayState {
        motionCoordinator.reset()
        engine.reset(nowMs = resetWallMs)
        coordinator.resetSession(session.trackId)
        TrackPointPolicyEngine.resetStream(TrackPointSource.LOCAL_GPS, session.trackId)
        val state = ReplayState()
        seedAnchorIfNeeded(state, resetWallMs)
        CaptureReplayDriver.runWithMotionTicks(session, engine) { frame ->
            feedFrame(frame, state)
        }
        return state
    }

    private fun seedAnchorIfNeeded(state: ReplayState, resetWallMs: Long) {
        val seedFrame = session.frames.lastOrNull { it.wallNowMs(session) <= resetWallMs }
            ?: session.frames.firstOrNull()
            ?: return
        val location = CaptureReplayLocations.toLocation(seedFrame)
        val result = coordinator.ingest(
            trackId = session.trackId,
            location = location,
            settings = settings,
            motionMode = state.motionMode,
            previousAcceptedLocation = null,
            sessionVisibleBoundaryId = 0L,
            bypassFilters = true,
            propsJson = null,
            totalDistanceMeters = 0f,
            queuedTrackerId = session.trackId,
            nowMs = resetWallMs,
            nowElapsedRealtimeNanos = 0L,
            isMockLocation = false,
        )
        if (result.accepted) {
            state.previousAccepted = result.lastFilteredLocation
            motionCoordinator.clearEvidenceCandidate()
            engine.onAcceptedFix(
                speedMps = CaptureReplayMetrics.vettedSpeedMps(seedFrame),
                eventTimeMs = resetWallMs,
            )
        }
    }

    private fun feedFrame(frame: CaptureReplayFrame, state: ReplayState) {
        val nowMs = frame.wallNowMs(session)
        val location = CaptureReplayLocations.toLocation(frame)
        var motionContext = motionContext(state.motionMode)
        val output = pipeline.processFix(
            input = TrackerLocationPipelineInput(
                trackId = session.trackId,
                location = location,
                settings = settings,
                motionContext = motionContext,
                previousAcceptedLocation = state.previousAccepted,
                sessionVisibleBoundaryId = 0L,
                ingestMode = FixIngestMode.Live,
                propsJson = null,
                totalDistanceMeters = 0f,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = frame.wallOffsetMs * 1_000_000L,
                sessionStartTimeMs = session.wallBaseMs,
                isMockLocation = false,
                skipAdaptiveTrackingEffects = false,
                localRecoveryDue = false,
                recoveryConfig = PositioningRecoveryConfig(
                    maxLocalPointGapMs = 120_000L,
                    recoverySpeedCapMps = 5f,
                ),
                recoveryAnchor = null,
                outlierSuppressorAnchor = state.previousAccepted,
            ),
            onAutoMotionRejected = { _, rejectedLocation, eventNowMs ->
                motionCoordinator.onRejectedOrHeld(
                    metrics = CaptureReplayMetrics.toDecisionMetrics(frame),
                    rejectReason = CaptureReplayMetrics.toRejectReason(frame),
                    eventTimeMs = rejectedLocation.time,
                    nowMs = eventNowMs,
                )
            },
            refreshMotionContext = {
                state.motionMode = engine.snapshot().mode
                motionContext(state.motionMode)
            },
            buildFreshnessRecoveryLocation = { _, sourceLocation, recoveryNowMs, recoveryNanos ->
                Location(sourceLocation).apply {
                    time = recoveryNowMs
                    elapsedRealtimeNanos = recoveryNanos
                }
            },
        )
        if (output.motionModeChanged) {
            state.motionRetryCount++
            motionContext = output.motionContext
            state.motionMode = motionContext.motionMode
        } else if (!frame.accepted) {
            val handling = motionCoordinator.onRejectedOrHeld(
                metrics = CaptureReplayMetrics.toDecisionMetrics(frame),
                rejectReason = CaptureReplayMetrics.toRejectReason(frame),
                eventTimeMs = frame.gpsTimeMs,
                nowMs = nowMs,
            )
            if (handling is AutoMotionRejectHandling.Evidence && handling.output.modeChanged) {
                state.motionRetryCount++
                motionContext = motionContext(engine.snapshot().mode)
                state.motionMode = motionContext.motionMode
                coordinator.ingest(
                    trackId = session.trackId,
                    location = location,
                    settings = settings,
                    motionMode = motionContext.motionMode,
                    effectiveAccuracyFilterMeters = motionContext.effectiveAccuracyThresholdMeters,
                    previousAcceptedLocation = state.previousAccepted,
                    sessionVisibleBoundaryId = 0L,
                    bypassFilters = false,
                    propsJson = null,
                    totalDistanceMeters = 0f,
                    queuedTrackerId = session.trackId,
                    nowMs = nowMs,
                    nowElapsedRealtimeNanos = frame.wallOffsetMs * 1_000_000L,
                    sessionStartTimeMs = session.wallBaseMs,
                    isMockLocation = false,
                    filterConfig = motionContext.filterConfig,
                )
            }
        }
        val result = output.result
        if (result.accepted) {
            state.previousAccepted = result.lastFilteredLocation
            motionCoordinator.clearEvidenceCandidate()
            val metrics = result.policyMetrics
            val speedMps = if (metrics != null && metrics.elapsedSeconds > 0.0) {
                (metrics.effectiveDistanceMeters / metrics.elapsedSeconds).toFloat()
            } else {
                0f
            }
            engine.onAcceptedFix(speedMps = speedMps, eventTimeMs = nowMs)
        }
        state.lastOutputAccepted = result.accepted
        state.motionMode = engine.snapshot().mode
    }

    private fun motionContext(mode: TrackingMotionMode): TrackerLocationMotionContext {
        val tuning = when (mode) {
            TrackingMotionMode.WALKING -> MotionProfileTuning.Walking
            TrackingMotionMode.BIKING -> MotionProfileTuning.Biking
            TrackingMotionMode.DRIVING -> MotionProfileTuning.Driving
        }
        return TrackerLocationMotionContext(
            motionMode = mode,
            filterConfig = LocationFilterConfig.fromTuning(
                tuning = tuning,
                trackingAccuracyThresholdMeters = 50.0,
                maxFutureSkewMs = 0L,
                freshnessTtlMs = 0L,
                normalizeSecondsTimestamps = false,
            ),
            effectiveAccuracyThresholdMeters = 50f,
        )
    }

    companion object {
        fun create(
            session: CaptureReplaySession,
            dao: LocationDao,
        ): CaptureReplayPipelineFeed {
            val engine = AutoTrackingMotionEngine()
            val motionCoordinator = AutoTrackingMotionCoordinator(
                engine = engine,
                evidenceGate = AutoTrackingMotionEvidenceGate(),
                streakPreserveWindowMs = TrackingServiceConstants.AUTO_MOTION_CAP_EVIDENCE_STREAK_PRESERVE_WINDOW_MS,
            )
            val coordinator = LocationIngestCoordinator(dao)
            val pipeline = TrackerLocationPipeline(
                locationIngestCoordinator = coordinator,
                freshnessRecoveryController = FreshnessRecoveryController(),
                repeatedOutlierSuppressor = RepeatedOutlierSuppressor(),
            )
            return CaptureReplayPipelineFeed(
                session = session,
                pipeline = pipeline,
                coordinator = coordinator,
                engine = engine,
                motionCoordinator = motionCoordinator,
            )
        }
    }
}
