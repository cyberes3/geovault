package com.geovault.tracker.positioning.ingest
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import androidx.core.location.LocationCompat
import com.geovault.common.geo.GeoCoordinates
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.logging.GeoVaultPointRecordingLog
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.positioning.PointEmissionTrouble
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class FixIngestSubsystem(private val rt: PositioningRuntime) {
    suspend fun processLocationUpdate(
        location: Location,
        bypassFilters: Boolean = false,
        propsJson: String? = null,
        allowWhenGpsPaused: Boolean = false,
        skipAdaptiveTrackingEffects: Boolean = false,
    ) {
        val runGeneration = rt.state.trackingGeneration
        if (!GeoCoordinates.isValidGeographic(location.latitude, location.longitude)) {
            rt.deps.runtimeTelemetry.event(
                "fix_rejected_invalid_coordinates",
                "lat=${location.latitude} lon=${location.longitude} pace=${rt.state.collectionPace}",
            )
            return
        }
        val nowMs = rt.deps.clock.wallTimeMs()
        val nowElapsedRealtimeNanos = rt.deps.clock.elapsedRealtimeNanos()
        logRawFixForReplay(
            location = location,
            trackId = rt.ports.selectedTrackerId(),
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            bypassFilters = bypassFilters,
            propsJson = propsJson,
            allowWhenGpsPaused = allowWhenGpsPaused,
            skipAdaptiveTrackingEffects = skipAdaptiveTrackingEffects,
        )
        if (
            !TrackingRuntimeOrchestrator.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = rt.state.isTracking,
                    gpsState = rt.state.gpsRuntimeState,
                    allowWhenGpsPaused = allowWhenGpsPaused
                )
            )
        ) {
            return
        }
        val settings = rt.deps.settingsRepository.getSettings()
        var runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext(settings)
        val previousAcceptedLocation = rt.state.lastFilteredLocation?.let { Location(it) }
        if (rt.state.isFastGpsLockWindowActive) {
            rt.recovery.fastLock.recordFastGpsLockSample(
                location = location,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
            )
        }
        val observedSpeedMps = rt.utilities.resolveObservedSpeedMps(location, rt.state.lastSpeedReferenceLocation)
        if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) return
        rt.projection.applyAccuracyHoldUpdate(
            incomingAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        )
        rt.projection.syncRuntimeStateStore()
        val selectedTrackerId = rt.ports.selectedTrackerId()
        if (selectedTrackerId.isEmpty()) return
        var motionMode = runtimeContext.activeMotionMode
        if (
            rt.deps.stationaryFreshnessCoordinator.probeActive &&
            !bypassFilters &&
            !skipAdaptiveTrackingEffects &&
            rt.recovery.pausedFreshness.handlePausedFreshnessProbeFix(
                selectedTrackerId = selectedTrackerId,
                probeLocation = location,
                anchorLocation = previousAcceptedLocation
                    ?: rt.state.recoveryAnchorState?.toLocation(providerPrefix = "paused_recovery_anchor"),
                settings = settings,
                motionMode = motionMode,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            )
        ) {
            return
        }
        // Independent evidence that the user is actually moving. Gated
        // only on the *current* observed chipset speed; an EMA-smoothed
        // term would carry stale velocity from a prior drive across a
        // long gap and permanently block the stationary counter.
        val activeSpeedHint = (observedSpeedMps ?: 0f) > TrackingServiceConstants.ACTIVE_SPEED_FLOOR_MPS
        val pointPropsJson = propsJson
        val pipelineOutput = rt.deps.trackerLocationPipeline.processFix(
            input = TrackerLocationPipelineInput(
                trackId = selectedTrackerId,
                location = location,
                settings = settings,
                motionContext = TrackerLocationMotionContext(
                    motionMode = motionMode,
                    filterConfig = runtimeContext.filterConfig,
                    effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
                ),
                previousAcceptedLocation = previousAcceptedLocation,
                sessionVisibleBoundaryId = rt.state.sessionVisibleBoundaryId,
                ingestMode = when {
                    bypassFilters -> FixIngestMode.FreshnessBypass
                    else -> FixIngestMode.Live
                },
                propsJson = pointPropsJson,
                totalDistanceMeters = rt.state.runtimeSnapshot.sessionTotalDistanceMeters,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                sessionStartTimeMs = rt.state.runtimeSnapshot.sessionStartTimeMs,
                isMockLocation = LocationCompat.isMock(location),
                skipAdaptiveTrackingEffects = skipAdaptiveTrackingEffects,
                localRecoveryDue = rt.deps.pointFreshnessTracker.shouldForceLocalRecovery(
                    nowMs = nowMs,
                    intervalSec = runtimeContext.pointFreshnessIntervalSec,
                ),
                recoveryConfig = runtimeContext.recoveryConfig,
                recoveryAnchor = rt.state.recoveryAnchorState,
                outlierSuppressorAnchor = rt.state.lastFilteredLocation,
            ),
            onAutoMotionRejected = { ingestResult, rejectedLocation, eventNowMs ->
                rt.motion.handleAutoMotionRejectedFix(
                    result = ingestResult,
                    location = rejectedLocation,
                    nowMs = eventNowMs,
                )
            },
            refreshMotionContext = {
                runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext(settings)
                TrackerLocationMotionContext(
                    motionMode = runtimeContext.activeMotionMode,
                    filterConfig = runtimeContext.filterConfig,
                    effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
                )
            },
            buildFreshnessRecoveryLocation = { anchor, sourceLocation, recoveryNowMs, recoveryElapsedNanos ->
                rt.contextBuilder.buildFreshnessRecoveryLocation(
                    anchor = anchor,
                    sourceLocation = sourceLocation,
                    nowMs = recoveryNowMs,
                    nowElapsedRealtimeNanos = recoveryElapsedNanos,
                )
            },
        )
        var result = pipelineOutput.result
        runtimeContext = rt.contextBuilder.currentPositioningRuntimeContext(settings)
        motionMode = pipelineOutput.motionContext.motionMode
        if (pipelineOutput.motionModeChanged) {
            rt.deps.runtimeTelemetry.event(
                name = "auto_motion_seeded",
                details = "mode=$motionMode"
            )
        }
        val freshnessRecoveryDecision = pipelineOutput.freshnessRecoveryDecision
        if (freshnessRecoveryDecision == FreshnessRecoveryDecision.CommitAnchor) {
            rt.deps.runtimeTelemetry.event(
                "freshness_probe_commit",
                "reason=${result.policyMetrics?.reason ?: result.rejectReason ?: "none"} " +
                    "accuracy=${if (location.hasAccuracy()) location.accuracy else -1f} " +
                    "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                    "uploadAgeMs=${rt.deps.pointFreshnessTracker.uploadAgeMs(nowMs) ?: -1L}"
            )
        } else {
            rt.contextBuilder.maybeLogFreshnessProbeDecision(
                decision = freshnessRecoveryDecision,
                result = result,
                nowMs = nowMs,
                motionMode = motionMode,
            )
        }
        val nextSessionDistance = result.nextSessionDistanceMeters
        val pointEmissionTrouble = rt.contextBuilder.resolvePointEmissionTrouble(
            result = result,
            nowMs = nowMs,
            motionMode = motionMode,
            effectiveAccuracyThresholdMeters = runtimeContext.effectiveAccuracyThresholdMeters,
        )
        rt.projection.applyAccuracyHoldUpdate(
            incomingAccuracyMeters = result.lastAccuracyMeters,
            pointEmissionTrouble = pointEmissionTrouble,
            extraTransform = { snapshot ->
                snapshot.copy(
                    sessionTotalDistanceMeters = if (result.accepted) nextSessionDistance else snapshot.sessionTotalDistanceMeters
                )
            },
        )
        result.policyMetrics?.let { metrics ->
            val filterReason = metrics.reason ?: result.rejectReason ?: result.adjustmentReason ?: "none"
            val signature = "decision=${metrics.decision}|reason=$filterReason|accepted=${result.accepted}"
            if (signature != rt.state.lastLocationFilterLogSignature) {
                rt.state.lastLocationFilterLogSignature = signature
                val rawLat = metrics.rawLatitude ?: location.latitude
                val rawLon = metrics.rawLongitude ?: location.longitude
                val committedLat = metrics.committedLatitude?.toString() ?: "none"
                val committedLon = metrics.committedLongitude?.toString() ?: "none"
                rt.deps.runtimeTelemetry.decision(
                    name = "location_filter",
                    details = "raw=${metrics.rawDistanceMeters} effective=${metrics.effectiveDistanceMeters} " +
                        "dt=${metrics.elapsedSeconds} impliedSpeed=${metrics.impliedSpeedMps} " +
                        "accuracy=${metrics.accuracyMeters ?: -1f} rollingAverage=${metrics.rollingAverageStepMeters} " +
                        "capCandidate=${metrics.capCandidateMeters} decision=${metrics.decision} " +
                        "reason=$filterReason " +
                        "rawLat=$rawLat rawLon=$rawLon " +
                        "committedLat=$committedLat committedLon=$committedLon"
                )
            }
        }
        if (!result.accepted && result.policyMetrics == null) {
            val filterReason = result.rejectReason ?: result.adjustmentReason ?: "none"
            val signature = "decision=none|reason=$filterReason|accepted=false"
            if (signature != rt.state.lastLocationFilterLogSignature) {
                rt.state.lastLocationFilterLogSignature = signature
                rt.deps.runtimeTelemetry.decision(
                    name = "location_filter",
                    details = "accepted=false reason=$filterReason " +
                        "accuracy=${result.lastAccuracyMeters ?: -1f} " +
                        "lat=${location.latitude} lon=${location.longitude}"
                )
            }
        }
        if (result.accepted && result.pointPersisted) {
            // Committed lat/lon (post-clip for `OUTLIER_CAPPED`) --
            // matches what lands in the rt.deps.database, not the raw chipset
            // coords. Internal snaps advance runtime state without
            // emitting a duplicate point, so they do not reach this log.
            val committed = result.lastFilteredLocation ?: location
            val reason = result.policyMetrics?.reason ?: result.adjustmentReason ?: "accept"
            val source = if (result.adjustmentReason ==
                TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED) {
                "snap"
            } else if (result.adjustmentReason ==
                TrackPointPolicyEngine.ADJUSTMENT_REASON_OUTLIER_CAPPED) {
                "clip"
            } else {
                "live"
            }
            rt.deps.runtimeTelemetry.event(
                name = "track_point",
                details = "lat=%.8f lon=%.8f accuracy=%.1f speed=%.2f reason=%s source=%s".format(
                    committed.latitude,
                    committed.longitude,
                    if (committed.hasAccuracy()) committed.accuracy else -1f,
                    if (committed.hasSpeed()) committed.speed else -1f,
                    reason,
                    source,
                )
            )
        }
        withContext(Dispatchers.Main) { rt.projection.syncRuntimeStateStore() }
        if (!result.accepted) {
            val rejectedForLock = result.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
                result.rejectReason == TrackPointRejectReason.STALE
            if (rejectedForLock) {
                rt.recovery.handleRejectedFixRecovery(
                    result = result,
                    rejectedLocation = location,
                    settings = settings,
                    motionMode = motionMode,
                    effectiveAccuracyThresholdMeters = pipelineOutput.motionContext.effectiveAccuracyThresholdMeters,
                    nowMs = nowMs,
                )
            }
            if (bypassFilters || skipAdaptiveTrackingEffects) {
                rt.motion.processAutoTrackingOutput(
                    output = rt.deps.autoTrackingMotionEngine.onRejectedFix(eventTimeMs = nowMs),
                    reason = "rejected_fix"
                )
            }
            rt.projection.broadcastSessionStats()
            rt.state.lastSpeedReferenceLocation = Location(location)
            return
        }
        if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) return

        if (!rt.utilities.isWaitingForProviderState()) {
            rt.collection.transitionGpsState(GpsRuntimeEvent.FIX_ACCEPTED, "fix_accepted")
        }
        if (result.pointPersisted) {
            rt.deps.pointFreshnessTracker.markLocalPointPersisted(nowMs)
            rt.deps.lowAccuracyFallbackCoordinator.onAcceptedFix()
            rt.recovery.fallback.cancelLowAccuracyFallbackTimer(clearCandidate = true)
            rt.deps.repeatedOutlierSuppressor.reset()
            rt.deps.freshnessRecoveryController.reset()
            if (rt.state.lastLoggedPointEmissionTrouble.active) {
                rt.projection.logPointEmissionTroubleTransition(
                    previous = rt.state.lastLoggedPointEmissionTrouble,
                    current = PointEmissionTrouble.None,
                    nowMs = nowMs,
                )
                rt.state.lastLoggedPointEmissionTrouble = PointEmissionTrouble.None
            }
            rt.projection.updateRuntimeSnapshot {
                it.copy(
                    lastLocalPointPersistedAtMs = rt.deps.pointFreshnessTracker.lastLocalPointPersistedAtMs,
                    activePointEmissionTrouble = false,
                    activePointEmissionAccuracyTrouble = false,
                    pointEmissionTroubleReason = null,
                )
            }
        } else {
            rt.deps.pointFreshnessTracker.markInternalAccepted(nowMs)
        }
        rt.state.lastFilteredLocation = result.lastFilteredLocation
        val acceptedLocation = result.lastFilteredLocation ?: location
        if (result.pointPersisted) {
            rt.contextBuilder.updateRecoveryAnchor(
                location = acceptedLocation,
                source = "persisted_point",
                motionMode = motionMode,
            )
        }
        val finalPropsJson = pointPropsJson ?: rt.utilities.buildLocalPointPropsJson(
            location = acceptedLocation,
            distanceMeters = nextSessionDistance
        )
        rt.projection.updateRuntimeSnapshot {
            it.copy(
                queuedPointsVisible = result.queuedPointsVisible,
                lastTrackedLatitude = result.lastTrackedLatitude,
                lastTrackedLongitude = result.lastTrackedLongitude,
                lastTrackedTimestampMs = result.lastTrackedTimestampMs,
                lastTrackedPropsJson = finalPropsJson
            )
        }
        GeoVaultCaptureLog.d(
            TrackingServiceConstants.TAG,
            "map_update local_fix_accepted track=$selectedTrackerId persisted=${result.pointPersisted} " +
                "runtimeTs=${result.lastTrackedTimestampMs} lat=${result.lastTrackedLatitude} " +
                "lon=${result.lastTrackedLongitude} acc=${result.lastAccuracyMeters} " +
                "queuedVisible=${result.queuedPointsVisible} adjustment=${result.adjustmentReason ?: "none"}"
        )
        val acceptedQuality = result.trackPointQuality ?: rt.utilities.resolveTrackPointQuality(acceptedLocation, finalPropsJson)
        if (
            rt.state.isFastGpsLockWindowActive &&
            rt.recovery.fastLock.hasRecoveredFastGpsLock(
                quality = acceptedQuality,
                measuredAccuracyMeters = result.lastAccuracyMeters,
                accuracyFilterMeters = runtimeContext.effectiveAccuracyThresholdMeters
            )
        ) {
            rt.recovery.fastLock.stopFastGpsLockWindow(reason = "accepted_fix_lock_recovered")
            rt.deps.lowAccuracyFallbackCoordinator.onAcceptedFix()
            rt.recovery.fallback.cancelLowAccuracyFallbackTimer(clearCandidate = true)
        }
        if (!skipAdaptiveTrackingEffects) {
            rt.motion.handleAcceptedAdaptiveTrackingEffects(
                result = result,
                rawLocation = location,
                runtimeContext = runtimeContext,
                settings = settings,
                motionMode = motionMode,
                activeSpeedHint = activeSpeedHint,
                observedSpeedMps = observedSpeedMps,
                nowMs = nowMs,
            )
        }
        // Exit the stationary region on the first persisted non-snap point after GPS
        // resumed. Guards:
        //  - skipAdaptiveTrackingEffects: excludes paused-freshness and bypass paths
        //  - gpsRuntimeState not paused: handleAcceptedAdaptiveTrackingEffects may have
        //    just re-paused GPS (user still stationary); don't exit a freshly-entered region
        //  - not UNCERTAINTY_SUPPRESSED: snap-to-anchor confirms stillness, not movement
        if (result.pointPersisted &&
            !skipAdaptiveTrackingEffects &&
            rt.deps.stationaryFreshnessCoordinator.hasRegion &&
            rt.state.gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
            rt.state.gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED &&
            result.adjustmentReason != TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED
        ) {
            rt.collection.exitStationaryRegion("confirmed_movement")
        }
        if (result.pointPersisted) {
            GeoVaultCaptureLog.d(
                TrackingServiceConstants.TAG,
                "map_update local_fix_publish_requested track=$selectedTrackerId " +
                    "locationTs=${acceptedLocation.time} lat=${acceptedLocation.latitude} " +
                    "lon=${acceptedLocation.longitude} quality=$acceptedQuality"
            )
            rt.utilities.publishTrackPoint(
                trackId = selectedTrackerId,
                location = acceptedLocation,
                propsJson = finalPropsJson,
                quality = acceptedQuality
            )
        } else {
            GeoVaultCaptureLog.d(
                TrackingServiceConstants.TAG,
                "map_update local_fix_no_bus_event track=$selectedTrackerId " +
                    "runtimeTs=${result.lastTrackedTimestampMs} reason=not_persisted"
            )
        }
        rt.state.lastSpeedReferenceLocation = Location(location)
        withContext(Dispatchers.Main) {
            rt.projection.syncRuntimeStateStore()
            rt.projection.updateNotificationFromDb(broadcastStats = false)
        }
        if (result.pointPersisted) {
            rt.serviceScope.launch(Dispatchers.IO) {
                val outcome = rt.upload.pushQueuedLocations(
                    scope = QueueUploadScope.LIVE_ONLY,
                    updateFailureCounters = false
                )
                if (outcome == SyncFailureClass.NONE) {
                    rt.state.consecutivePushFailures = 0
                }
            }
        }
    }

    suspend fun processLocationUpdateSerialized(
        location: Location,
        bypassFilters: Boolean = false,
        propsJson: String? = null,
        allowWhenGpsPaused: Boolean = false,
        skipAdaptiveTrackingEffects: Boolean = false,
    ) {
        rt.locationUpdateMutex.withLock {
            rt.fixIngest.processLocationUpdate(
                location = location,
                bypassFilters = bypassFilters,
                propsJson = propsJson,
                allowWhenGpsPaused = allowWhenGpsPaused,
                skipAdaptiveTrackingEffects = skipAdaptiveTrackingEffects,
            )
        }
    }

    private fun logRawFixForReplay(
        location: Location,
        trackId: String,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
        bypassFilters: Boolean,
        propsJson: String?,
        allowWhenGpsPaused: Boolean,
        skipAdaptiveTrackingEffects: Boolean,
    ) {
        GeoVaultPointRecordingLog.i(
            TrackingServiceConstants.TAG,
            "positioning_raw_fix " +
                "track=$trackId " +
                "wall=$nowMs " +
                "elapsedNanos=$nowElapsedRealtimeNanos " +
                "time=${location.time} " +
                "lat=${location.latitude} " +
                "lon=${location.longitude} " +
                "acc=${if (location.hasAccuracy()) location.accuracy else -1f} " +
                "speed=${if (location.hasSpeed()) location.speed else "none"} " +
                "bearing=${if (location.hasBearing()) location.bearing else "none"} " +
                "provider=${location.provider ?: "unknown"} " +
                "mock=${LocationCompat.isMock(location)} " +
                "gpsState=${rt.state.gpsRuntimeState} " +
                "trackingGeneration=${rt.state.trackingGeneration} " +
                "allowWhenGpsPaused=$allowWhenGpsPaused " +
                "bypassFilters=$bypassFilters " +
                "skipAdaptiveTrackingEffects=$skipAdaptiveTrackingEffects " +
                "propsKind=${propsKind(propsJson)}",
        )
    }

    private fun propsKind(propsJson: String?): String {
        if (propsJson.isNullOrBlank()) return "none"
        return when {
            propsJson.contains("manual_send") -> "manual_send"
            propsJson.contains("paused_freshness") -> "paused_freshness"
            propsJson.contains("low_accuracy_fallback") -> "low_accuracy_fallback"
            else -> "provided"
        }
    }

}
