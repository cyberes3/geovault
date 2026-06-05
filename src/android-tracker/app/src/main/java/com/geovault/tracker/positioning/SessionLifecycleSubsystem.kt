package com.geovault.tracker.positioning
import com.geovault.tracker.positioning.PositioningRuntime
import android.app.Service
import android.location.Location
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.R
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SessionLifecycleSubsystem(private val rt: PositioningRuntime) {
    fun requestStartTracking(path: TrackingServiceIntents.StartupCommandPath, trigger: String): Boolean {
        synchronized(rt.state.startupStateLock) {
            if (rt.state.isTracking) {
                GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Ignoring start request; tracking already active")
                return true
            }
            if (rt.state.startupInProgress) {
                GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Ignoring start request; startup already in progress")
                return true
            }
            rt.lifecycle.setStartupInProgress(true)
            rt.state.startupReadyForEvents = false
        }
        rt.projection.transitionControlState(TrackingControlEvent.StartRequested)
        val selectedTrackerId = rt.ports.selectedTrackerId()
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(selectedTrackerId)) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: invalid selected tracker id")
            rt.lifecycle.setStartupInProgress(false)
            rt.foreground.failStartup(
                message = rt.ports.service.getString(R.string.no_tracker_selected_go_to_settings),
                path = path,
                trigger = trigger,
                reason = "invalid_selected_tracker"
            )
            return false
        }
        if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(rt.ports.service)) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: required tracking permissions missing")
            rt.lifecycle.setStartupInProgress(false)
            rt.foreground.failStartup(
                message = rt.ports.service.getString(R.string.location_permissions_required),
                path = path,
                trigger = trigger,
                reason = "permissions_missing"
            )
            return false
        }
        if (!rt.utilities.isGpsProviderEnabled()) {
            GeoVaultCaptureLog.w(TrackingServiceConstants.TAG, "Start blocked: GPS provider disabled")
            rt.lifecycle.setStartupInProgress(false)
            rt.foreground.failStartup(
                message = rt.ports.service.getString(R.string.gps_provider_required),
                path = path,
                trigger = trigger,
                reason = "gps_provider_disabled"
            )
            return false
        }
        rt.serviceScope.launch {
            try {
                rt.lifecycle.performStartTracking(trigger = trigger)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Start failed during startup pipeline", t)
                rt.foreground.failStartup(
                    message = rt.ports.service.getString(R.string.unable_to_start_location_updates),
                    path = path,
                    trigger = trigger,
                    reason = "startup_pipeline_exception"
                )
            } finally {
                rt.lifecycle.setStartupInProgress(false)
            }
        }
        return true
    }

    suspend fun performStartTracking(trigger: String) {
        TrackPointBus.pauseLocalDelivery()
        rt.state.trackingGeneration++
        val runGeneration = rt.state.trackingGeneration
        val selectedTrackerId = rt.ports.selectedTrackerId()
        rt.state.sessionVisibleBoundaryId = withContext(Dispatchers.IO) {
            rt.deps.database.locationDao().getMaxId()
        }
        rt.state.sessionBoundaryForBacklogId = rt.state.sessionVisibleBoundaryId
        val sessionStartedAtMs = rt.deps.clock.wallTimeMs()
        initializeTrackingSession(
            trigger = trigger,
            selectedTrackerId = selectedTrackerId,
            sessionStartedAtMs = sessionStartedAtMs,
        )
        rt.deps.activityHintSource?.start(
            context = rt.ports.service,
            trackId = selectedTrackerId,
            trackingGeneration = rt.state.trackingGeneration,
        )
        startRuntimeBackgroundJobs(runGeneration)

        try {
            rt.lifecycle.startLocationUpdates()
            rt.recovery.fastLock.maybeStartFastGpsLockWindow(measuredAccuracyMeters = null)
            rt.state.startupReadyForEvents = true
            rt.serviceScope.launch(Dispatchers.IO) {
                rt.upload.pushQueuedLocations(scope = QueueUploadScope.ALL, updateFailureCounters = false)
            }
            rt.projection.updateNotificationFromDb(broadcastStats = true)
            GeoVaultCaptureLog.i(TrackingServiceConstants.TAG, "Tracking session started boundary=${rt.state.sessionVisibleBoundaryId}")
        } catch (e: SecurityException) {
            GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Location updates security failure", e)
            rt.foreground.failActiveTrackingAndStop(rt.ports.service.getString(R.string.unable_to_start_location_updates))
        } finally {
            TrackPointBus.resumeLocalDelivery()
        }
    }

    suspend fun startReplaySession(trigger: String, startWallMs: Long) {
        TrackPointBus.pauseLocalDelivery()
        rt.state.trackingGeneration++
        val selectedTrackerId = rt.ports.selectedTrackerId()
        rt.state.sessionVisibleBoundaryId = withContext(Dispatchers.IO) {
            rt.deps.database.locationDao().getMaxId()
        }
        rt.state.sessionBoundaryForBacklogId = rt.state.sessionVisibleBoundaryId
        initializeTrackingSession(
            trigger = trigger,
            selectedTrackerId = selectedTrackerId,
            sessionStartedAtMs = startWallMs,
        )
        rt.deps.activityHintSource?.start(
            context = rt.ports.service,
            trackId = selectedTrackerId,
            trackingGeneration = rt.state.trackingGeneration,
        )
        TrackPointBus.resumeLocalDelivery()
    }

    private suspend fun initializeTrackingSession(
        trigger: String,
        selectedTrackerId: String,
        sessionStartedAtMs: Long,
    ) {
        SessionResetCoordinator(rt).applyForStart(
            selectedTrackerId = selectedTrackerId,
            sessionStartedAtMs = sessionStartedAtMs,
        )
        if (selectedTrackerId.isNotEmpty()) {
            rt.projection.restoreLocalFreshnessFromDatabase(
                trackerId = selectedTrackerId,
                sessionStartedAtMs = sessionStartedAtMs,
            )
        }
        rt.state.isTracking = true
        rt.collection.transitionGpsState(GpsRuntimeEvent.TRACKING_STARTED, "perform_start_tracking")
        rt.projection.transitionControlState(TrackingControlEvent.StartSucceeded)
        rt.projection.updateRuntimeSnapshot {
            rt.deps.sessionCoordinator.transitionToRunning(
                previous = it,
                nowMs = sessionStartedAtMs,
                sessionVisibleBoundaryId = rt.state.sessionVisibleBoundaryId
            )
        }
        rt.deps.settingsRepository.setWasTrackingBeforeExit(true)
        TrackingRecoveryCoordinator.markTrackingStarted(rt.ports.service.applicationContext)
        rt.deps.runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.TRACKING_STARTED,
            reason = "start_tracking",
            trigger = TrackingServiceIntents.mapRuntimeTrigger(trigger)
        )
        rt.projection.syncRuntimeStateStore()
    }

    private fun startRuntimeBackgroundJobs(runGeneration: Int) {
        rt.motion.startAutoModeTickIfNeeded()
        rt.recovery.jobs.startRecoveryHeartbeat()
        rt.collection.ensureGpsProviderReceiverRegistered()
        rt.upload.startRetryJob(runGeneration)
        rt.upload.startBacklogUploader(rt.state.sessionBoundaryForBacklogId, runGeneration)
        rt.upload.startPreflightMonitor(runGeneration)
        rt.projection.syncRuntimeStateStore()
    }

    fun stopTracking(reason: String, failureReason: String? = null) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Stopping tracking reason=$reason wasRunning=${rt.state.isTracking}")
        rt.projection.transitionControlState(TrackingControlEvent.StopRequested, failureReason = failureReason)
        rt.lifecycle.transitionToStoppedState(failureReason = failureReason)
        rt.deps.settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(rt.ports.service.applicationContext, reason = reason)
        rt.projection.transitionControlState(TrackingControlEvent.StopCompleted)
        rt.lifecycle.cleanupServiceResources(reason = reason)
        TrackPointBus.resumeLocalDelivery()
        rt.lifecycle.stopServiceInstance(reason = reason)
    }

    fun transitionToStoppedState(failureReason: String?) {
        rt.state.trackingGeneration++
        rt.state.isTracking = false
        rt.lifecycle.setStartupInProgress(false)
        rt.state.startupReadyForEvents = false
        rt.collection.transitionGpsState(GpsRuntimeEvent.TRACKING_STOPPED, "transition_to_stopped_state")
        SessionResetCoordinator(rt).applyForStop()
        rt.projection.updateRuntimeSnapshot {
            rt.deps.sessionCoordinator.transitionToStopped(
                previous = it,
                failureReason = failureReason
            )
        }
        rt.projection.syncRuntimeStateStore(
            lifecycleStateOverride = TrackingLifecycleState.STOPPED,
            failureReasonOverride = failureReason,
        )
    }

    fun cleanupServiceResources(reason: String) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Cleaning rt.ports.service resources reason=$reason")
        rt.recovery.jobs.stopRecoveryHeartbeat()
        rt.upload.stopRetryJob()
        rt.upload.stopPreflightMonitor()
        rt.upload.stopBacklogUploader()
        rt.collection.unregisterGpsProviderReceiverIfNeeded()
        rt.recovery.fallback.cancelLowAccuracyFallbackTimer(clearCandidate = false)
        rt.deps.significantMotionBridge?.cancel()
        rt.deps.activityHintSource?.stop()
        rt.lifecycle.stopLocationUpdates()
        TrackPointBus.resumeLocalDelivery()
        if (rt.state.startupForegroundPromoted) {
            rt.ports.service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            rt.state.startupForegroundPromoted = false
        }
    }

    fun stopServiceInstance(reason: String) {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "Stopping rt.ports.service instance reason=$reason")
        rt.ports.service.stopSelf()
    }

    fun startLocationUpdates() {
        rt.deps.locationSessionCoordinator.stopSession()
        rt.state.lastAppliedLocationRequestKey = null
        rt.state.lastFixDeliveryAtMs = 0L
        val applied = rt.locationRequests.applyCurrentLocationRequest(reason = "start_or_resume")
        if (!applied) throw SecurityException("Unable to apply location request")
        rt.locationRequests.startFixDeliveryWatchdog()
    }

    fun stopLocationUpdates() {
        rt.state.locationRequestReapplyRetryJob?.cancel()
        rt.state.locationRequestReapplyRetryJob = null
        rt.state.fixDeliveryWatchdogJob?.cancel()
        rt.state.fixDeliveryWatchdogJob = null
        rt.state.lastAppliedLocationRequestKey = null
        rt.state.lastLocationRequestAppliedAtMs = 0L
        rt.state.lastFixDeliveryAtMs = 0L
        rt.deps.locationSessionCoordinator.stopSession()
    }

    fun setStartupInProgress(value: Boolean) {
        rt.state.startupInProgress = value
    }

    fun isTrackingActiveOrStarting(): Boolean {
        return rt.state.isTracking || rt.state.startupInProgress
    }

}
