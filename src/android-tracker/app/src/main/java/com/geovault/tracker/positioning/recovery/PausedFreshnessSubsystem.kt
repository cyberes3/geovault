package com.geovault.tracker.positioning.recovery
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import androidx.core.location.LocationCompat
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.AutoTrackingMotionState
import com.geovault.tracker.location.PausedFreshnessDecision
import com.geovault.tracker.location.PausedFreshnessDecisionReason
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.location.PausedFreshnessPolicy
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PausedFreshnessSubsystem(private val rt: PositioningRuntime) {
    fun requestStationaryFreshnessProbe(reason: String): Boolean {
        if (!rt.state.isTracking) {
            rt.deps.stationaryFreshnessCoordinator.onStopped(reason = "not_tracking")
            rt.deps.runtimeTelemetry.event(
                "stationary_ping_dropped",
                "reason=$reason notTracking=true gpsState=${rt.state.gpsRuntimeState}"
            )
            return false
        }
        if (rt.state.gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
            rt.state.gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            rt.deps.stationaryFreshnessCoordinator.onResumed(reason = "not_paused")
            rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "stationary_ping_not_paused")
            rt.deps.runtimeTelemetry.event("stationary_ping_skipped", "reason=$reason state=${rt.state.gpsRuntimeState}")
            return true
        }
        rt.deps.runtimeTelemetry.event(
            "stationary_ping_received",
            "reason=$reason state=${rt.state.gpsRuntimeState} lastRaw=${rt.commands.summarizeLocationForTelemetry(rt.state.latestObservedRawLocation)} " +
                "lastAccepted=${rt.commands.summarizeLocationForTelemetry(rt.state.lastFilteredLocation)}"
        )
        if (rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED) {
            rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "provider_unavailable_before_probe")
            rt.deps.stationaryFreshnessCoordinator.schedulePausedPing(
                reason = "provider_unavailable_before_probe",
                providerAvailable = false,
            )
            rt.deps.runtimeTelemetry.event("stationary_ping_deferred", "reason=$reason state=${rt.state.gpsRuntimeState}")
            return true
        }
        rt.recovery.pausedFreshness.markPausedFreshnessProbeStarted(nowMs = System.currentTimeMillis())
        rt.collection.resumeGps(reason = "stationary_ping_resume")
        return true
    }

    suspend fun handlePausedFreshnessProbeFix(
        selectedTrackerId: String,
        probeLocation: Location,
        anchorLocation: Location?,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): Boolean {
        val runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext(settings)
        val decision = PausedFreshnessPolicy.evaluate(
            anchorLocation = anchorLocation,
            candidateLocation = probeLocation,
            stationaryRadiusMeters = rt.deps.stationaryFreshnessCoordinator.radiusMeters
                .takeIf { rt.deps.stationaryFreshnessCoordinator.hasRegion }
                ?: TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            accuracyCeilingMeters = runtimeContext.stationaryAccuracyCeilingMeters,
            freshnessIntervalMs = runtimeContext.stationaryProbeIntervalMs,
            nowMs = nowMs,
            lastFreshnessPointAtMs = rt.deps.stationaryFreshnessCoordinator.lastFreshnessPointAtMs,
        )
        if (!decision.shouldEmit) {
            rt.recovery.pausedFreshness.logPausedFreshnessDecision(eventName = "paused_freshness_skipped", decision = decision, probeLocation = probeLocation)
            when (decision.reason) {
                PausedFreshnessDecisionReason.MOVED -> {
                    rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = decision.reason.telemetryValue)
                    return false
                }
                PausedFreshnessDecisionReason.NO_ANCHOR -> {
                    rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = decision.reason.telemetryValue)
                    rt.collection.pauseGpsInternal(force = true)
                    return true
                }
                PausedFreshnessDecisionReason.POOR_ACCURACY -> {
                    val probeState = rt.deps.stationaryFreshnessCoordinator.recordPoorAccuracyFix(nowMs)
                    if (
                        probeState.poorAccuracyFixes >= TrackingServiceConstants.PAUSED_FRESHNESS_MAX_POOR_ACCURACY_FIXES ||
                        probeState.probeAgeMs >= TrackingServiceConstants.PAUSED_FRESHNESS_PROBE_TIMEOUT_MS
                    ) {
                        rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "poor_accuracy_timeout")
                        if (rt.deps.pointFreshnessTracker.shouldForceLocalRecovery(
                                nowMs = nowMs,
                                intervalSec = rt.contextBuilder.resolvePointFreshnessIntervalSec(motionMode),
                            )
                        ) {
                            rt.deps.runtimeTelemetry.event(
                                "paused_freshness_kept_awake",
                                "reason=poor_accuracy localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L}"
                            )
                        } else {
                            rt.collection.pauseGpsInternal(force = true)
                        }
                    }
                    return true
                }
                PausedFreshnessDecisionReason.TOO_SOON -> {
                    rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "too_soon")
                    rt.collection.pauseGpsInternal(force = true)
                    return true
                }
                PausedFreshnessDecisionReason.EMIT -> return false
            }
        }

        val anchor = anchorLocation ?: run {
            rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "emit_without_anchor")
            rt.collection.pauseGpsInternal(force = true)
            return true
        }
        val freshnessLocation = PausedFreshnessPointFactory.buildAnchoredFreshnessLocation(
            anchorLocation = anchor,
            probeLocation = probeLocation,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
        val persisted = rt.recovery.pausedFreshness.persistPausedFreshnessPoint(
            selectedTrackerId = selectedTrackerId,
            freshnessLocation = freshnessLocation,
            previousAcceptedLocation = anchor,
            settings = settings,
            motionMode = motionMode,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
        if (!persisted) {
            rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "persist_rejected")
            rt.collection.pauseGpsInternal(force = true)
            return true
        }
        rt.deps.stationaryFreshnessCoordinator.markFreshnessPointPersisted(nowMs)
        rt.recovery.pausedFreshness.logPausedFreshnessDecision(eventName = "paused_freshness_emitted", decision = decision, probeLocation = probeLocation)
        rt.deps.runtimeTelemetry.event(
            name = "track_point",
            details = "lat=%.8f lon=%.8f accuracy=%.1f speed=%.2f reason=paused-freshness source=paused_freshness".format(
                freshnessLocation.latitude,
                freshnessLocation.longitude,
                if (freshnessLocation.hasAccuracy()) freshnessLocation.accuracy else -1f,
                if (freshnessLocation.hasSpeed()) freshnessLocation.speed else -1f,
            )
        )
        rt.recovery.pausedFreshness.clearPausedFreshnessProbe(reason = "emitted")
        rt.collection.pauseGpsInternal(force = true)
        rt.deps.runtimeTelemetry.event(
            "paused_freshness_repaused",
            "intervalMs=${rt.contextBuilder.currentPositioningRuntimeContext(settings).stationaryProbeIntervalMs}"
        )
        return true
    }

    fun markPausedFreshnessProbeStarted(nowMs: Long) {
        val anchorAgeMs = rt.state.lastFilteredLocation?.time?.let { nowMs - it }
        rt.deps.stationaryFreshnessCoordinator.startProbe(
            nowMs = nowMs,
            timeoutMs = TrackingServiceConstants.PAUSED_FRESHNESS_PROBE_TIMEOUT_MS,
            details = "state=${rt.state.gpsRuntimeState} consecutiveStationary=${rt.state.consecutiveStationaryPoints} " +
                "anchorAgeMs=${anchorAgeMs ?: -1L}",
        )
    }

    fun clearPausedFreshnessProbe(
        reason: String,
        clearLastFreshnessTimestamp: Boolean = false,
    ) {
        rt.deps.stationaryFreshnessCoordinator.clearProbe(
            reason = reason,
            clearLastFreshnessTimestamp = clearLastFreshnessTimestamp,
        )
    }

    fun logPausedFreshnessDecision(
        eventName: String,
        decision: PausedFreshnessDecision,
        probeLocation: Location,
    ) {
        rt.deps.runtimeTelemetry.event(
            eventName,
            "reason=${decision.reason.telemetryValue} " +
                "distance=${decision.distanceMeters ?: -1f} " +
                "accuracy=${decision.accuracyMeters ?: -1f} " +
                "elapsedSinceLast=${decision.elapsedSinceLastFreshnessMs ?: -1L} " +
                "provider=${probeLocation.provider ?: "unknown"}"
        )
    }

    suspend fun persistPausedFreshnessPoint(
        selectedTrackerId: String,
        freshnessLocation: Location,
        previousAcceptedLocation: Location,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): Boolean {
        val runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext(settings)
        val pipelineOutput = rt.deps.trackerLocationPipeline.processFix(
            input = TrackerLocationPipelineInput(
                trackId = selectedTrackerId,
                location = freshnessLocation,
                settings = settings,
                motionContext = TrackerLocationMotionContext(
                    motionMode = motionMode,
                    filterConfig = runtimeContext.filterConfig,
                    effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
                ),
                previousAcceptedLocation = previousAcceptedLocation,
                sessionVisibleBoundaryId = rt.state.sessionVisibleBoundaryId,
                ingestMode = FixIngestMode.PausedFreshness,
                propsJson = null,
                totalDistanceMeters = rt.state.runtimeSnapshot.sessionTotalDistanceMeters,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                sessionStartTimeMs = rt.state.runtimeSnapshot.sessionStartTimeMs,
                isMockLocation = LocationCompat.isMock(freshnessLocation),
                skipAdaptiveTrackingEffects = true,
                localRecoveryDue = false,
                recoveryConfig = runtimeContext.recoveryConfig,
                recoveryAnchor = rt.state.recoveryAnchorState,
                outlierSuppressorAnchor = rt.state.lastFilteredLocation,
            ),
            onAutoMotionRejected = { result, _, _ ->
                AutoMotionRejectHandling.Rejected(
                    output = AutoTrackingEngineOutput(
                        state = AutoTrackingMotionState(mode = motionMode),
                        modeChanged = false,
                    ),
                    rejectReason = result.rejectReason,
                    policyReason = result.policyMetrics?.reason,
                )
            },
            refreshMotionContext = {
                TrackerLocationMotionContext(
                    motionMode = motionMode,
                    filterConfig = runtimeContext.filterConfig,
                    effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
                )
            },
            buildFreshnessRecoveryLocation = { anchor, _, recoveryNowMs, recoveryNanos ->
                anchor.toLocation(providerPrefix = "paused_freshness").apply {
                    time = recoveryNowMs
                    elapsedRealtimeNanos = recoveryNanos
                }
            },
        )
        val result = pipelineOutput.result
        if (!result.accepted || !result.pointPersisted) {
            rt.deps.runtimeTelemetry.event(
                "paused_freshness_persist_skipped",
                "accepted=${result.accepted} persisted=${result.pointPersisted} " +
                    "reason=${result.rejectReason ?: result.adjustmentReason ?: "none"}"
            )
            return false
        }

        val acceptedLocation = result.lastFilteredLocation ?: freshnessLocation
        rt.deps.pointFreshnessTracker.markLocalPointPersisted(nowMs)
        rt.deps.freshnessRecoveryController.reset()
        rt.contextBuilder.updateRecoveryAnchor(
            location = acceptedLocation,
            source = "paused_freshness",
            motionMode = motionMode,
        )
        rt.state.lastFilteredLocation = acceptedLocation
        rt.state.lastSpeedReferenceLocation = Location(acceptedLocation)
        val finalPropsJson = rt.utilities.buildLocalPointPropsJson(
            location = acceptedLocation,
            distanceMeters = result.nextSessionDistanceMeters,
        )
        rt.projection.applyAccuracyHoldUpdate(
            incomingAccuracyMeters = result.lastAccuracyMeters,
            extraTransform = { snapshot ->
                snapshot.copy(
                    queuedPointsVisible = result.queuedPointsVisible,
                    sessionTotalDistanceMeters = result.nextSessionDistanceMeters,
                    lastTrackedLatitude = result.lastTrackedLatitude,
                    lastTrackedLongitude = result.lastTrackedLongitude,
                    lastTrackedTimestampMs = result.lastTrackedTimestampMs,
                    lastTrackedPropsJson = finalPropsJson,
                )
            },
        )
        rt.utilities.publishTrackPoint(
            trackId = selectedTrackerId,
            location = acceptedLocation,
            propsJson = finalPropsJson,
            quality = rt.utilities.resolveTrackPointQuality(acceptedLocation, finalPropsJson),
        )
        withContext(Dispatchers.Main) {
            rt.projection.syncRuntimeStateStore()
            rt.projection.updateNotificationFromDb(broadcastStats = false)
        }
        rt.serviceScope.launch(Dispatchers.IO) {
            val outcome = rt.upload.pushQueuedLocations(
                scope = QueueUploadScope.LIVE_ONLY,
                updateFailureCounters = false
            )
            if (outcome == SyncFailureClass.NONE) {
                rt.state.consecutivePushFailures = 0
            }
        }
        return true
    }

}
