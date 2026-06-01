package com.geovault.tracker.positioning
import com.geovault.tracker.positioning.PositioningRuntime
import android.content.Intent
import android.os.SystemClock
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.location.TrackingControlPlane
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.positioning.PointEmissionTrouble
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.runtime.PositioningDiagnosticSnapshot
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingStatusAccuracyInput
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RuntimeProjectionSubsystem(private val rt: PositioningRuntime) {
    fun updateNotificationFromDb(broadcastStats: Boolean) {
        rt.serviceScope.launch(Dispatchers.IO) {
            val count = if (rt.state.isTracking) {
                rt.deps.database.locationDao().getCurrentSessionCountForTracker(
                    trackerId = rt.ports.selectedTrackerId(),
                    sessionBoundaryId = rt.state.sessionVisibleBoundaryId
                )
            } else {
                0
            }
            rt.projection.updateRuntimeSnapshot { it.copy(queuedPointsVisible = count) }
            withContext(Dispatchers.Main) {
                rt.projection.syncRuntimeStateStore()
                if (rt.state.startupForegroundPromoted) {
                    rt.deps.notificationPresenter.updateForegroundNotification(rt.state.runtimeSnapshot)
                }
            }
            if (broadcastStats) {
                rt.projection.broadcastSessionStats()
            }
        }
    }

    fun broadcastSessionStats() {
        if (rt.state.isTracking && !rt.state.startupReadyForEvents) return
        rt.ports.service.sendBroadcast(Intent(TrackingServiceConstants.SESSION_STATS_UPDATE).apply { setPackage(rt.ports.service.packageName) })
    }

    fun updateRuntimeSnapshot(
        transform: (TrackingRuntimeSnapshot) -> TrackingRuntimeSnapshot
    ): TrackingRuntimeSnapshot {
        return synchronized(rt.state.runtimeSnapshotLock) {
            rt.state.runtimeSnapshot = transform(rt.state.runtimeSnapshot)
            rt.state.runtimeSnapshot
        }
    }

    fun applyAccuracyHoldUpdate(
        incomingAccuracyMeters: Float?,
        pointEmissionTrouble: PointEmissionTrouble = PointEmissionTrouble.None,
        extraTransform: ((TrackingRuntimeSnapshot) -> TrackingRuntimeSnapshot)? = null,
    ): TrackingRuntimeSnapshot {
        val threshold = rt.contextBuilder.currentPositioningRuntimeContext().effectiveAccuracyThresholdMeters
        val nowElapsedMs = SystemClock.elapsedRealtime()
        return rt.projection.updateRuntimeSnapshot { snapshot ->
            val decision = RuntimeAccuracyHoldPolicy.next(
                previous = snapshot,
                incomingAccuracyMeters = incomingAccuracyMeters,
                effectiveAccuracyThresholdMeters = threshold,
                nowElapsedMs = nowElapsedMs,
                forceCurrentAccuracy = pointEmissionTrouble.active,
            )
            val lastGoodAgeMs = decision.lastGoodAccuracyAtElapsedMs
                .takeIf { it > 0L }
                ?.let { nowElapsedMs - it }
            val accuracyHoldLogKey = buildAccuracyHoldLogKey(
                incomingAccuracyMeters = incomingAccuracyMeters,
                displayedAccuracyMeters = decision.displayedAccuracyMeters,
                held = decision.heldLastGoodAccuracy,
                pointEmissionTrouble = pointEmissionTrouble,
            )
            if (accuracyHoldLogKey != rt.state.lastAccuracyHoldLogKey) {
                rt.state.lastAccuracyHoldLogKey = accuracyHoldLogKey
                rt.deps.runtimeTelemetry.decision(
                    name = "accuracy_hold",
                    details = "raw=${incomingAccuracyMeters ?: -1f} displayed=${decision.displayedAccuracyMeters ?: -1f} " +
                        "threshold=$threshold held=${decision.heldLastGoodAccuracy} " +
                        "forceCurrent=${pointEmissionTrouble.active} " +
                        "troubleReason=${pointEmissionTrouble.reason ?: "none"} " +
                        "accuracyBlocked=${pointEmissionTrouble.accuracyBlocked} " +
                        "lastGood=${decision.lastGoodAccuracyMeters ?: -1f} lastGoodAgeMs=${lastGoodAgeMs ?: -1L} " +
                        "graceMs=${RuntimeAccuracyHoldPolicy.ACCURACY_HOLD_GRACE_MS}"
                )
            }
            rt.projection.logPointEmissionTroubleTransition(
                previous = rt.state.lastLoggedPointEmissionTrouble,
                current = pointEmissionTrouble,
                nowMs = System.currentTimeMillis(),
            )
            rt.state.lastLoggedPointEmissionTrouble = pointEmissionTrouble
            val withAccuracy = snapshot.copy(
                lastAccuracyMeters = decision.displayedAccuracyMeters,
                lastGoodAccuracyMeters = decision.lastGoodAccuracyMeters,
                lastGoodAccuracyAtElapsedMs = decision.lastGoodAccuracyAtElapsedMs,
                currentFixAccuracyMeters = incomingAccuracyMeters,
                activePointEmissionTrouble = pointEmissionTrouble.active,
                activePointEmissionAccuracyTrouble = pointEmissionTrouble.accuracyBlocked,
                pointEmissionTroubleReason = pointEmissionTrouble.reason,
                lastLocalPointPersistedAtMs = rt.deps.pointFreshnessTracker.lastLocalPointPersistedAtMs,
                lastUploadSucceededAtMs = rt.deps.pointFreshnessTracker.lastUploadSucceededAtMs,
            )
            extraTransform?.invoke(withAccuracy) ?: withAccuracy
        }
    }

    fun buildAccuracyHoldLogKey(
        incomingAccuracyMeters: Float?,
        displayedAccuracyMeters: Float?,
        held: Boolean,
        pointEmissionTrouble: PointEmissionTrouble,
    ): String {
        return "raw=${accuracyMetersBucket(incomingAccuracyMeters)}|" +
            "displayed=${accuracyMetersBucket(displayedAccuracyMeters)}|" +
            "held=$held|force=${pointEmissionTrouble.active}|reason=${pointEmissionTrouble.reason ?: "none"}|" +
            "accBlocked=${pointEmissionTrouble.accuracyBlocked}"
    }

    fun accuracyMetersBucket(meters: Float?): Int {
        if (meters == null || !meters.isFinite() || meters < 0f) return -1
        return meters.toInt()
    }

    fun logPointEmissionTroubleTransition(
        previous: PointEmissionTrouble,
        current: PointEmissionTrouble,
        nowMs: Long,
    ) {
        if (previous.active == current.active && previous.reason == current.reason) return
        val (eventName, details) = PositioningDiagnosticEvent.pointEmissionTrouble(
            active = current.active,
            reason = current.reason ?: previous.reason ?: "none",
            accuracyBlocked = current.accuracyBlocked,
            localAgeMs = rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs),
            uploadAgeMs = rt.deps.pointFreshnessTracker.uploadAgeMs(nowMs),
            gpsState = rt.state.gpsRuntimeState,
        )
        rt.deps.runtimeTelemetry.event(
            eventName,
            details
        )
    }

    suspend fun restoreLocalFreshnessFromDatabase(
        trackerId: String,
        sessionStartedAtMs: Long,
    ) {
        rt.state.recoveryAnchorState = rt.deps.recoveryAnchorStore.load(
            trackerId = trackerId,
            sessionBoundaryId = rt.state.sessionVisibleBoundaryId,
        )
        rt.state.recoveryAnchorState?.let { anchor ->
            rt.deps.pointFreshnessTracker.seedLocalPointPersistedAt(anchor.timestampMs)
            rt.deps.runtimeTelemetry.event(
                "recovery_anchor_restored",
                "trackerId=$trackerId source=${anchor.source} persistedAtMs=${anchor.timestampMs} " +
                    "localAgeMs=${sessionStartedAtMs - anchor.timestampMs}"
            )
        }
        rt.deps.stationaryFreshnessCoordinator.restore(
            trackerId = trackerId,
            sessionBoundaryId = rt.state.sessionVisibleBoundaryId,
        )?.let { restored ->
            rt.deps.runtimeTelemetry.event(
                "stationary_region_restored",
                "trackerId=$trackerId enteredAtMs=${restored.enteredAtMs} " +
                    "lastFreshnessPointAtMs=${restored.lastFreshnessPointAtMs}"
            )
        }
        val latestPoint = withContext(Dispatchers.IO) {
            rt.deps.database.locationDao()
                .getRecentChronologicalForTracker(trackerId, limit = 1)
                .lastOrNull()
        } ?: return
        if (latestPoint.time <= 0L) return
        rt.deps.pointFreshnessTracker.seedLocalPointPersistedAt(latestPoint.time)
        rt.deps.runtimeTelemetry.event(
            "freshness_restored_from_db",
            "trackerId=$trackerId persistedAtMs=${latestPoint.time} " +
                "localAgeMs=${sessionStartedAtMs - latestPoint.time} " +
                "sessionBoundaryId=${rt.state.sessionVisibleBoundaryId}"
        )
    }

    fun syncRuntimeStateStore(
        lifecycleStateOverride: TrackingLifecycleState? = null,
        failureReasonOverride: String? = null,
    ) {
        val gpsOk = rt.utilities.isGpsProviderEnabled()
        val settings = rt.deps.settingsRepository.getSettings()
        val positioningContext = rt.contextBuilder.currentPositioningRuntimeContext(settings)
        val effectiveAccuracyThreshold = positioningContext.effectiveAccuracyThresholdMeters
        rt.projection.validateRuntimeInvariant(gpsProviderEnabled = gpsOk)
        val effectiveRunning = rt.lifecycle.isTrackingActiveOrStarting()
        val selectedTrackerId = rt.ports.selectedTrackerId()
        val snapshotForStatus = synchronized(rt.state.runtimeSnapshotLock) { rt.state.runtimeSnapshot }
        val providerDecision = rt.deps.providerHealthController.evaluate(
            nowMs = System.currentTimeMillis(),
            isTracking = rt.state.isTracking,
            expectsActiveFixDelivery = rt.locationRequests.expectsActiveFixDelivery(),
            gpsProviderAvailable = gpsOk,
        )
        val statusProjection = TrackingStatusAccuracyProjector.project(
            TrackingStatusAccuracyInput(
                isRunning = effectiveRunning,
                gpsProviderEnabled = gpsOk,
                gpsState = rt.state.gpsRuntimeState,
                lastAccuracyMeters = snapshotForStatus.lastAccuracyMeters,
                currentFixAccuracyMeters = snapshotForStatus.currentFixAccuracyMeters,
                effectiveAccuracyThresholdMeters = effectiveAccuracyThreshold,
                activeAccuracyBlockedEmission = snapshotForStatus.activePointEmissionTrouble,
            )
        )
        val activeMotionMode = positioningContext.activeMotionMode
        val selectedTrackerName = rt.ports.selectedTrackerName()
        val next = synchronized(rt.state.runtimeSnapshotLock) {
            val recordingRuntime = RecordingRuntimeReducer.fromInputs(
                previous = rt.state.runtimeSnapshot.recordingRuntime,
                sessionActive = rt.state.isTracking,
                startupActive = rt.state.startupInProgress,
                gpsState = rt.state.gpsRuntimeState,
                gpsProviderEnabled = gpsOk,
                selectedTrackerId = selectedTrackerId,
            )
            RuntimeSnapshotProjector.project(
                previous = rt.state.runtimeSnapshot,
                input = RuntimeSnapshotProjectionInput(
                    isRunning = effectiveRunning,
                    recordingRuntime = recordingRuntime,
                    lifecycleState = lifecycleStateOverride ?: rt.state.controlState.lifecycleState,
                    failureReason = failureReasonOverride ?: rt.state.controlState.failureReason,
                    selectedTrackerId = selectedTrackerId,
                    selectedTrackerName = selectedTrackerName,
                    gpsProviderEnabled = gpsOk,
                    autoTrackingEnabled = true,
                    activeMotionMode = activeMotionMode,
                    uiStatus = statusProjection.uiStatus,
                    gpsPaused = recordingRuntime.pausedForMotion,
                    effectiveAccuracyThresholdMeters = effectiveAccuracyThreshold,
                    sessionVisibleBoundaryId = rt.state.sessionVisibleBoundaryId,
                    providerHealthReason = providerDecision.reason.telemetryValue,
                    uploadLastFailureClass = rt.state.uploadLivenessState.lastFailureClass,
                    uploadConsecutiveFailures = rt.state.uploadLivenessState.consecutiveFailures,
                    currentSessionQueuedCount = rt.state.uploadLivenessState.currentSessionQueuedCount,
                    backlogQueuedCount = rt.state.uploadLivenessState.backlogQueuedCount,
                )
            ).also { rt.state.runtimeSnapshot = it }
        }
        TrackingRuntimeStateStore.update { next }
        rt.projection.maybeLogPositioningDiagnosticSnapshot(next)
        if (rt.state.startupForegroundPromoted && rt.state.startupInProgress) {
            rt.serviceScope.launch(Dispatchers.Main) {
                rt.deps.notificationPresenter.updateForegroundNotification(rt.state.runtimeSnapshot)
            }
        }
    }

    fun maybeLogPositioningDiagnosticSnapshot(snapshot: TrackingRuntimeSnapshot) {
        val providerDecision = rt.deps.providerHealthController.evaluate(
            nowMs = System.currentTimeMillis(),
            isTracking = rt.state.isTracking,
            expectsActiveFixDelivery = rt.locationRequests.expectsActiveFixDelivery(),
            gpsProviderAvailable = snapshot.gpsProviderEnabled,
        )
        val diagnosticSnapshot = PositioningDiagnosticSnapshot(
            gpsState = rt.state.gpsRuntimeState,
            motionMode = snapshot.activeMotionMode,
            providerHealth = providerDecision.reason.telemetryValue,
            localAgeMs = rt.deps.pointFreshnessTracker.localPointAgeMs(System.currentTimeMillis()),
            uploadAgeMs = rt.deps.pointFreshnessTracker.uploadAgeMs(System.currentTimeMillis()),
            recoveryProbe = if (snapshot.activePointEmissionTrouble) {
                snapshot.pointEmissionTroubleReason ?: "active"
            } else {
                "inactive"
            },
            stationaryRegion = if (rt.deps.stationaryFreshnessCoordinator.hasRegion) {
                "active:probe=${rt.deps.stationaryFreshnessCoordinator.probeActive}:poorFixes=${rt.deps.stationaryFreshnessCoordinator.poorAccuracyFixes}"
            } else {
                "inactive"
            },
            queueCount = rt.state.uploadLivenessState.currentSessionQueuedCount + rt.state.uploadLivenessState.backlogQueuedCount,
            uploadFailureClass = rt.state.uploadLivenessState.lastFailureClass,
        )
        val key = "gps=${diagnosticSnapshot.gpsState}|mode=${diagnosticSnapshot.motionMode}|" +
            "provider=${diagnosticSnapshot.providerHealth}|recovery=${diagnosticSnapshot.recoveryProbe}|" +
            "stationary=${diagnosticSnapshot.stationaryRegion}|queue=${diagnosticSnapshot.queueCount}|" +
            "uploadFailure=${diagnosticSnapshot.uploadFailureClass}"
        if (key == rt.state.lastPositioningDiagnosticSnapshotKey) return
        rt.state.lastPositioningDiagnosticSnapshotKey = key
        val (eventName, details) = PositioningDiagnosticEvent.snapshot(diagnosticSnapshot)
        rt.deps.runtimeTelemetry.event(eventName, details)
    }

    fun transitionControlState(event: TrackingControlEvent, failureReason: String? = null) {
        rt.state.controlState = TrackingControlPlane.transition(
            current = rt.state.controlState,
            event = event,
            failureReason = failureReason
        )
        rt.projection.syncRuntimeStateStore()
    }

    fun validateRuntimeInvariant(gpsProviderEnabled: Boolean) {
        when {
            !rt.state.isTracking && rt.state.gpsRuntimeState != GpsRuntimeState.INACTIVE -> {
                rt.deps.runtimeTelemetry.event(
                    "runtime_invariant_violation",
                    "state=rt.state.gpsRuntimeState while rt.state.isTracking=false"
                )
            }
            rt.state.isTracking && rt.state.gpsRuntimeState == GpsRuntimeState.INACTIVE -> {
                rt.deps.runtimeTelemetry.event(
                    "runtime_invariant_violation",
                    "state=INACTIVE while rt.state.isTracking=true"
                )
            }
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER && gpsProviderEnabled -> {
                rt.deps.runtimeTelemetry.event(
                    "runtime_invariant_watch",
                    "state=WAITING_FOR_PROVIDER with providerEnabled=true"
                )
            }
        }
    }

}
