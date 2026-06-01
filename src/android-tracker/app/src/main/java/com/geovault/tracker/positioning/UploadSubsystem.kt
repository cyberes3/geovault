package com.geovault.tracker.positioning

import com.geovault.tracker.positioning.PositioningRuntime
import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.AutoTrackingMotionState
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.LowAccuracyFallbackLoopDecision
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.PausedFreshnessDecision
import com.geovault.tracker.location.PausedFreshnessDecisionReason
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.location.PausedFreshnessPolicy
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.location.TrackingControlPlane
import com.geovault.tracker.location.TrackingControlState
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingLocationRequestInput
import com.geovault.tracker.location.TrackingLocationRequestPolicy
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.location.TrackingSyncPolicy
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import com.geovault.tracker.positioning.config.PositioningPresetValues
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.positioning.ingest.TrackerLocationPipeline
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.runtime.PositioningDiagnosticSnapshot
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingStatusAccuracyInput
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.services.UploadLivenessState
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

internal class UploadSubsystem(private val rt: PositioningRuntime) {
    fun startRetryJob(runGeneration: Int) {
        rt.state.retryJob?.cancel()
        rt.state.retryJob = rt.serviceScope.launch(Dispatchers.IO) {
            while (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                val baseDelay = TrackingSyncPolicy.nextRetryDelayMs(
                    consecutiveFailures = rt.state.consecutivePushFailures,
                    failureClass = rt.state.lastSyncFailureClass
                )
                val jitter = Random.nextLong(-TrackingServiceConstants.RETRY_JITTER_MS, TrackingServiceConstants.RETRY_JITTER_MS + 1)
                delay((baseDelay + jitter).coerceAtLeast(5_000L))
                if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) break
                val trackerId = SelectedTrackerPrefs.selectedTrackerId(rt.ports.service)
                val count = rt.deps.database.locationDao().getCurrentSessionCountForTracker(
                    trackerId = trackerId,
                    sessionBoundaryId = rt.state.sessionBoundaryForBacklogId
                )
                if (count > 0) {
                    rt.upload.pushQueuedLocations(scope = QueueUploadScope.LIVE_ONLY)
                }
            }
        }
    }

    fun stopRetryJob() {
        rt.state.retryJob?.cancel()
        rt.state.retryJob = null
    }

    fun startBacklogUploader(sessionBoundaryId: Long, runGeneration: Int) {
        rt.state.backlogUploaderJob?.cancel()
        rt.state.backlogUploaderJob = rt.serviceScope.launch(Dispatchers.IO) {
            while (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                val trackerId = SelectedTrackerPrefs.selectedTrackerId(rt.ports.service)
                val backlogCount = rt.deps.database.locationDao().getBacklogCountForTracker(
                    trackerId = trackerId,
                    sessionBoundaryId = sessionBoundaryId
                )
                if (backlogCount > 0) {
                    rt.upload.pushQueuedLocations(scope = QueueUploadScope.BACKLOG_ONLY)
                    delay(5_000L)
                } else {
                    delay(30_000L)
                }
            }
        }
    }

    fun stopBacklogUploader() {
        rt.state.backlogUploaderJob?.cancel()
        rt.state.backlogUploaderJob = null
    }

    fun startPreflightMonitor(runGeneration: Int) {
        rt.state.preflightJob?.cancel()
        rt.state.preflightJob = rt.serviceScope.launch(Dispatchers.IO) {
            while (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                delay(20_000L)
                if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) break
                if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(rt.ports.service)) {
                    withContext(Dispatchers.Main) {
                        rt.foreground.failActiveTrackingAndStop(rt.ports.service.getString(R.string.location_permissions_required))
                    }
                    return@launch
                }
                if (!rt.utilities.isGpsProviderEnabled()) {
                    withContext(Dispatchers.Main) {
                        rt.collection.enterWaitingForGpsProvider(reason = "preflight_monitor")
                    }
                    continue
                }
                if (
                    rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
                    rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                ) {
                    withContext(Dispatchers.Main) {
                        rt.collection.resumeFromGpsProviderWait(reason = "preflight_monitor")
                    }
                }
            }
        }
    }

    fun stopPreflightMonitor() {
        rt.state.preflightJob?.cancel()
        rt.state.preflightJob = null
    }

    suspend fun pushQueuedLocations(
        scope: QueueUploadScope,
        updateFailureCounters: Boolean = true
    ): SyncFailureClass {
        if (!rt.state.isTracking) return SyncFailureClass.NONE
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(rt.ports.service)
        trimQueuedLocationsRetention(trackerId)
        updateUploadQueueCounts(trackerId)
        if (!NetworkStatusMonitor.hasUsableNetwork(rt.ports.service)) {
            val result = QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.NO_NETWORK)
            rt.upload.applyQueueUploadResult(
                result = result,
                scope = scope,
                settings = rt.deps.settingsRepository.getSettings(),
                updateFailureCounters = updateFailureCounters,
            )
            if (scope != QueueUploadScope.BACKLOG_ONLY) {
                rt.state.lastSyncFailureClass = SyncFailureClass.TRANSIENT
            }
            withContext(Dispatchers.Main) {
                rt.projection.updateNotificationFromDb(broadcastStats = true)
            }
            return result.failureClass
        }
        if (!TrackingServiceIntents.hasValidSelectedTrackerId(trackerId)) {
            rt.deps.runtimeTelemetry.event("queue_skip_invalid_tracker", "scope=$scope")
            val result = QueueUploadOutcomePolicy.skipped(QueueUploadSkipReason.INVALID_TRACKER)
            val trackerError = if (trackerId.isBlank()) {
                rt.ports.service.getString(R.string.no_tracker_selected_go_to_settings)
            } else {
                rt.ports.service.getString(R.string.tracker_validation_failed_go_to_settings)
            }
            rt.upload.applyQueueUploadResult(
                result = result,
                scope = scope,
                settings = rt.deps.settingsRepository.getSettings(),
                updateFailureCounters = updateFailureCounters,
            )
            withContext(Dispatchers.Main) {
                rt.ports.service.sendBroadcast(
                    Intent(TrackingServiceIntents.ACTION_TRACKING_ERROR).apply {
                        setPackage(rt.ports.service.packageName)
                        putExtra(TrackingServiceIntents.EXTRA_TRACKING_ERROR_MESSAGE, trackerError)
                    }
                )
                rt.projection.updateNotificationFromDb(broadcastStats = true)
            }
            return result.failureClass
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(rt.ports.service)
        val settings = rt.deps.settingsRepository.getSettings()
        val result = rt.deps.queueUploadEngine.push(
            scope = scope,
            trackerId = trackerId,
            serverUrl = serverUrl,
            config = QueueUploadConfig(
                sessionBoundaryId = rt.state.sessionBoundaryForBacklogId,
                sessionVisibleBoundaryId = rt.state.sessionVisibleBoundaryId,
                maxBatchesPerPush = TrackingServiceConstants.MAX_BATCHES_PER_PUSH,
                useExtendedParams = settings.sendExtendedData,
                sessionStartTimeMs = rt.state.runtimeSnapshot.sessionStartTimeMs,
                batteryLevel = rt.utilities.readBatteryLevel(),
                isCharging = rt.utilities.isCharging(),
                deviceIdentifier = rt.utilities.getDeviceIdentifier()
            ),
            onBatchUploaded = { visibleSentCount ->
                val sentDelta = visibleSentCount.coerceAtLeast(0)
                if (sentDelta > 0) {
                    rt.projection.updateRuntimeSnapshot {
                        it.copy(
                            pointsSentThisSession = it.pointsSentThisSession + sentDelta,
                            lastPointSentAtMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
        )
        rt.upload.applyQueueUploadResult(
            result = result,
            scope = scope,
            settings = settings,
            updateFailureCounters = updateFailureCounters,
        )
        trimQueuedLocationsRetention(trackerId)
        updateUploadQueueCounts(trackerId)
        withContext(Dispatchers.Main) {
            rt.projection.updateNotificationFromDb(broadcastStats = true)
        }
        return result.failureClass
    }

    fun applyQueueUploadResult(
        result: QueueUploadResult,
        scope: QueueUploadScope,
        settings: TrackerSettings,
        updateFailureCounters: Boolean,
    ) {
        logQueueUploadResult(result = result, scope = scope)
        if (scope == QueueUploadScope.BACKLOG_ONLY) return
        val shouldUpdateCounters = updateFailureCounters && result.failureClass != SyncFailureClass.SKIPPED
        rt.state.lastSyncFailureClass = result.failureClass
        rt.state.uploadLivenessState = rt.state.uploadLivenessState.onUploadResult(
            result = result,
            nowMs = System.currentTimeMillis(),
            updateFailureCounters = shouldUpdateCounters,
        )
        if (result.rowsDeleted > 0) {
            val uploadedAtMs = System.currentTimeMillis()
            rt.deps.pointFreshnessTracker.markUploadSucceeded(uploadedAtMs)
            rt.projection.updateRuntimeSnapshot {
                it.copy(
                    lastPointSentAtMs = if (result.visibleRowsSent > 0) uploadedAtMs else it.lastPointSentAtMs,
                    lastUploadSucceededAtMs = rt.deps.pointFreshnessTracker.lastUploadSucceededAtMs,
                )
            }
        }
        if (!shouldUpdateCounters) return
        if (result.failureClass == SyncFailureClass.NONE) {
            rt.state.consecutivePushFailures = 0
            return
        }
        rt.state.consecutivePushFailures++
        val nowMs = System.currentTimeMillis()
        val motionMode = rt.contextBuilder.resolveActiveMotionMode()
        if (
            rt.deps.pointFreshnessTracker.isLocalFresh(
                nowMs = nowMs,
                intervalSec = rt.contextBuilder.resolvePointFreshnessIntervalSec(motionMode),
            )
        ) {
            rt.deps.runtimeTelemetry.event(
                "upload_failed_local_fresh",
                "failureClass=${result.failureClass} " +
                    "reason=${result.failureReason?.telemetryValue ?: result.skippedReason?.telemetryValue ?: "none"} " +
                    "consecutiveFailures=${rt.state.consecutivePushFailures} " +
                    "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                    "uploadAgeMs=${rt.deps.pointFreshnessTracker.uploadAgeMs(nowMs) ?: -1L}"
            )
        }
    }

    fun logQueueUploadResult(result: QueueUploadResult, scope: QueueUploadScope) {
        val (eventName, details) = PositioningDiagnosticEvent.queueUploadResult(result = result, scope = scope)
        rt.deps.runtimeTelemetry.event(eventName, details)
    }

    fun trimQueuedLocationsRetention(trackerId: String) {
        if (trackerId.isBlank()) return
        val cutoff = System.currentTimeMillis() - TrackingServiceConstants.MAX_QUEUE_AGE_MS
        val deletedByAge = rt.deps.database.locationDao().deleteOlderThanForTracker(trackerId, cutoff)
        val count = rt.deps.database.locationDao().getCountForTracker(trackerId)
        val deletedBySize = if (count > TrackingServiceConstants.MAX_QUEUE_SIZE) {
            rt.deps.database.locationDao().deleteOldestCountForTracker(trackerId, count - TrackingServiceConstants.MAX_QUEUE_SIZE)
        } else {
            0
        }
        if (deletedByAge > 0 || deletedBySize > 0) {
            rt.deps.runtimeTelemetry.event(
                name = "queue_retention_trim",
                details = "trackerId=$trackerId deletedByAge=$deletedByAge deletedBySize=$deletedBySize maxSize=$TrackingServiceConstants.MAX_QUEUE_SIZE maxAgeMs=$TrackingServiceConstants.MAX_QUEUE_AGE_MS"
            )
        }
    }

    fun updateUploadQueueCounts(trackerId: String) {
        if (trackerId.isBlank()) return
        rt.state.uploadLivenessState = rt.state.uploadLivenessState.withQueueCounts(
            currentSessionQueuedCount = rt.deps.database.locationDao().getCurrentSessionCountForTracker(
                trackerId = trackerId,
                sessionBoundaryId = rt.state.sessionBoundaryForBacklogId,
            ),
            backlogQueuedCount = rt.deps.database.locationDao().getBacklogCountForTracker(
                trackerId = trackerId,
                sessionBoundaryId = rt.state.sessionBoundaryForBacklogId,
            ),
        )
    }

    fun getAuthenticatedHttpClient(): OkHttpClient {
        if (rt.deps.httpClient == null) {
            rt.deps.httpClient = RetrofitClient.getAuthenticatedOkHttpClient(rt.ports.service.applicationContext).newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
        return rt.deps.httpClient!!
    }

}
