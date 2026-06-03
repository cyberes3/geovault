package com.geovault.tracker.positioning.ingest
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import androidx.core.location.LocationCompat
import com.geovault.common.geo.GeoCoordinates
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.positioning.PointEmissionTrouble
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
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
        val activeMotionHint = (observedSpeedMps ?: 0f) > TrackingServiceConstants.MOTION_HINT_FLOOR_MPS
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
                name = "auto_motion_retry",
                details = "mode=$motionMode accepted=${result.accepted} " +
                    "reason=${result.policyMetrics?.reason ?: result.rejectReason ?: "none"}"
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
                val outlierDecision = rt.deps.repeatedOutlierSuppressor.evaluate(
                    candidate = location,
                    anchor = rt.state.lastFilteredLocation,
                    effectiveAccuracyThresholdMeters = pipelineOutput.motionContext.effectiveAccuracyThresholdMeters,
                    nowMs = nowMs,
                )
                val repeatedOutlierSuppressed = outlierDecision.suppress
                if (repeatedOutlierSuppressed) {
                    rt.deps.runtimeTelemetry.event(
                        "repeated_outlier_suppressed",
                        "reason=${outlierDecision.reason} repeats=${outlierDecision.repeatCount} " +
                            "accuracy=${if (location.hasAccuracy()) location.accuracy else -1f} " +
                            "lat=${location.latitude} lon=${location.longitude}"
                    )
                }
                val fastLockSuppressed = repeatedOutlierSuppressed || rt.recovery.fastLock.shouldSuppressFastLockForAutoMotion(
                    rejectReason = result.rejectReason,
                    nowMs = nowMs,
                )
                if (!fastLockSuppressed) {
                    rt.recovery.fastLock.maybeStartFastGpsLockWindow(
                        measuredAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        rejectReason = result.rejectReason
                    )
                }
                if (settings.lowAccuracyFallbackEnabled && !repeatedOutlierSuppressed) {
                    rt.collection.transitionGpsState(GpsRuntimeEvent.FIX_REJECTED, "rejected_for_lock:${result.rejectReason}")
                    rt.state.lowAccuracyFallbackRejectedFixCountThisSession++
                    rt.recovery.fallback.maybeLogFallbackRejectSummary(nowMs)
                    rt.state.lowAccuracyFallbackCandidate = rt.recovery.fallback.selectLowAccuracyFallbackCandidate(
                        rejectedLocation = location,
                        nowMs = nowMs,
                        motionMode = motionMode,
                    )
                    val armDecision = rt.deps.lowAccuracyFallbackCoordinator.onRejectedFixForLock(
                        fallbackEligible = true,
                        candidateLatitude = location.latitude,
                        candidateLongitude = location.longitude,
                        candidateTimestampMs = location.time
                    )
                    if (armDecision == LowAccuracyFallbackArmDecision.START_TIMER) {
                        rt.collection.transitionGpsState(GpsRuntimeEvent.FALLBACK_TIMER_ARMED, "fallback_timer_armed")
                        rt.state.lowAccuracyFallbackArmCountThisSession++
                        rt.state.lowAccuracyFallbackTimerArmedAtMs = nowMs
                        rt.recovery.fallback.ensureLowAccuracyFallbackTimerRunning()
                    }
                }
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
            val stationaryRadius = runtimeContext.stationaryRadiusMeters
            val adjustmentReason = result.adjustmentReason
            // `UNCERTAINTY_SUPPRESSED` is positive evidence the device hasn't
            // moved (filter snapped to anchor because raw displacement was
            // inside the joint accuracy envelope). Other adjustments
            // (`OUTLIER_CAPPED`, etc.) are pessimistic: hold the counter
            // rather than advance it.
            val filterConfirmedStillness = adjustmentReason ==
                TrackPointPolicyEngine.ADJUSTMENT_REASON_UNCERTAINTY_SUPPRESSED
            val filterIntervened = adjustmentReason != null && !filterConfirmedStillness
            val stationaryConfidence = result.policyMetrics?.stationaryConfidence
            val stationaryReferenceLocation = result.lastFilteredLocation ?: location
            val stationaryDecision = TrackingLocationPolicy.stationaryUpdate(
                lastLocation = rt.state.stationaryAnchorLocation,
                location = stationaryReferenceLocation,
                stationaryRadiusMeters = stationaryRadius,
                currentConsecutive = rt.state.consecutiveStationaryPoints,
                significantMotionOnly = settings.significantDataOnly,
                activeMotionHint = activeMotionHint,
                filterIntervened = filterIntervened,
                filterConfirmedStillness = filterConfirmedStillness,
                confidence = stationaryConfidence,
            )
            if (stationaryDecision.reason != "disabled") {
                rt.deps.runtimeTelemetry.event(
                    name = "stationary_update",
                    details = "from=${rt.state.consecutiveStationaryPoints} to=${stationaryDecision.consecutive} " +
                        "shouldPause=${stationaryDecision.shouldPause} reason=${stationaryDecision.reason} " +
                        "accuracy=${if (stationaryReferenceLocation.hasAccuracy()) stationaryReferenceLocation.accuracy else -1f} " +
                        "adjustmentReason=${adjustmentReason ?: "none"} " +
                        "confirmedStillness=$filterConfirmedStillness " +
                        "filterIntervened=$filterIntervened " +
                        "confidence=${stationaryConfidence?.score ?: -1.0} " +
                        "oscillating=${stationaryConfidence?.isOscillating ?: false}"
                )
            }
            rt.state.consecutiveStationaryPoints = stationaryDecision.consecutive
            rt.state.stationaryAnchorLocation = when (rt.state.consecutiveStationaryPoints) {
                0 -> null
                1 -> Location(stationaryReferenceLocation)
                else -> rt.state.stationaryAnchorLocation
            }
            val pauseEligibility = StationaryPauseEligibilityPolicy.evaluate(
                stationaryPolicyWantsPause = stationaryDecision.shouldPause,
                localPointFresh = rt.deps.pointFreshnessTracker.isLocalFresh(
                    nowMs = nowMs,
                    intervalSec = runtimeContext.pointFreshnessIntervalSec,
                ),
                fallbackPending = rt.deps.lowAccuracyFallbackCoordinator.hasPendingCandidate(),
                providerAvailable = rt.utilities.isGpsProviderEnabled(),
            )
            if (stationaryDecision.shouldPause && !pauseEligibility.shouldPause) {
                rt.deps.runtimeTelemetry.event(
                    "stationary_pause_blocked",
                    "reason=${pauseEligibility.reason.telemetryValue} " +
                        "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                        "fallbackPending=${rt.deps.lowAccuracyFallbackCoordinator.hasPendingCandidate()}"
                )
            }
            if (pauseEligibility.shouldPause) {
                rt.collection.enterStationaryRegion(
                    anchorLocation = rt.state.stationaryAnchorLocation ?: stationaryReferenceLocation,
                    nowMs = nowMs,
                    motionMode = motionMode,
                    radiusMeters = stationaryRadius,
                )
                rt.collection.pauseGps()
            }
            rt.deps.autoTrackingMotionCoordinator.clearEvidenceCandidate()
            // Auto-mode classification runs on vetted geometry only:
            // effectiveDistance / dt from the position filter's accepted,
            // RSS-corrected metrics. Falling back to chipset speed here is
            // what previously let phantom multipath bursts thrash modes.
            val vettedSpeedMps = result.policyMetrics?.let { metrics ->
                if (metrics.elapsedSeconds > 0.0) {
                    (metrics.effectiveDistanceMeters / metrics.elapsedSeconds).toFloat()
                        .coerceAtLeast(0f)
                } else {
                    0f
                }
            } ?: 0f
            rt.motion.processAutoTrackingOutput(
                output = rt.deps.autoTrackingMotionEngine.onAcceptedFix(
                    speedMps = vettedSpeedMps,
                    eventTimeMs = nowMs
                ),
                reason = "accepted_fix"
            )
            rt.motion.maybeApplyElasticDistanceFilter(
                observedSpeedMps = observedSpeedMps,
                measuredAccuracyMeters = (result.lastFilteredLocation ?: location)
                    .takeIf { it.hasAccuracy() }
                    ?.accuracy
            )
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
        GeoVaultCaptureLog.i(
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
