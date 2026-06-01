package com.geovault.tracker.positioning

import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.os.VibrationEffect
import android.os.VibratorManager
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
import android.provider.Settings
import com.geovault.common.logging.GeoVaultCaptureLog
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionState
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.location.LowAccuracyFallbackLoopDecision
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.PausedFreshnessDecision
import com.geovault.tracker.location.PausedFreshnessDecisionReason
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.location.PausedFreshnessPolicy
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.positioning.ingest.TrackerLocationPipeline
import com.geovault.tracker.positioning.ingest.FixIngestMode
import com.geovault.tracker.positioning.ingest.TrackerLocationPipelineInput
import com.geovault.tracker.positioning.ingest.TrackerLocationMotionContext
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
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
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.runtime.PositioningDiagnosticSnapshot
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPresetValues
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.positioning.config.PositioningPolicyConfig
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingStatusAccuracyInput
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.UploadLivenessState
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal class PositioningRuntime(
    internal val service: com.geovault.tracker.tracking.TrackingService,
) {
    val state = PositioningSessionState()

    internal val serviceJob = SupervisorJob()
    internal val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    internal val ingestScope = CoroutineScope(serviceJob + Dispatchers.Default)
    internal lateinit var deps: PositioningDependencies

    internal val database get() = deps.database
    internal val settingsRepository get() = deps.settingsRepository
    internal val sessionCoordinator get() = deps.sessionCoordinator
    internal val locationIngestCoordinator get() = deps.locationIngestCoordinator
    internal val trackerLocationPipeline get() = deps.trackerLocationPipeline
    internal val notificationPresenter get() = deps.notificationPresenter
    internal val runtimeEventPublisher get() = deps.runtimeEventPublisher
    internal val queueUploadEngine get() = deps.queueUploadEngine
    internal val locationSessionCoordinator get() = deps.locationSessionCoordinator
    internal val runtimeTelemetry get() = deps.runtimeTelemetry
    internal val recoveryAnchorStore get() = deps.recoveryAnchorStore
    internal val stationaryPingController get() = deps.stationaryPingController
    internal val stationaryFreshnessCoordinator get() = deps.stationaryFreshnessCoordinator
    internal var sparseTrackingObserverJob: Job?
        get() = state.sparseTrackingObserverJob
        set(value) { state.sparseTrackingObserverJob = value }
    internal var httpClient: OkHttpClient?
        get() = deps.httpClient
        set(value) { deps.httpClient = value }
    internal val locationUpdateMutex = Mutex()
    internal val localTrackPointOrderingCounter get() = state.localTrackPointOrderingCounter

    internal var isTracking
        get() = state.isTracking
        set(value) { state.isTracking = value }
    internal var startupInProgress
        get() = state.startupInProgress
        set(value) { state.startupInProgress = value }
    internal var startupReadyForEvents
        get() = state.startupReadyForEvents
        set(value) { state.startupReadyForEvents = value }
    internal var controlState: com.geovault.tracker.location.TrackingControlState
        get() = state.controlState
        set(value) { state.controlState = value }
    internal var startupForegroundPromoted
        get() = state.startupForegroundPromoted
        set(value) { state.startupForegroundPromoted = value }
    internal var sessionVisibleBoundaryId
        get() = state.sessionVisibleBoundaryId
        set(value) { state.sessionVisibleBoundaryId = value }
    internal var sessionBoundaryForBacklogId
        get() = state.sessionBoundaryForBacklogId
        set(value) { state.sessionBoundaryForBacklogId = value }
    internal var lastFilteredLocation
        get() = state.lastFilteredLocation
        set(value) { state.lastFilteredLocation = value }
    internal var latestObservedRawLocation
        get() = state.latestObservedRawLocation
        set(value) { state.latestObservedRawLocation = value }
    internal var lowAccuracyFallbackCandidate
        get() = state.lowAccuracyFallbackCandidate
        set(value) { state.lowAccuracyFallbackCandidate = value }
    internal var lowAccuracyFallbackTimerArmedAtMs
        get() = state.lowAccuracyFallbackTimerArmedAtMs
        set(value) { state.lowAccuracyFallbackTimerArmedAtMs = value }
    internal var lowAccuracyFallbackEmitCountThisSession
        get() = state.lowAccuracyFallbackEmitCountThisSession
        set(value) { state.lowAccuracyFallbackEmitCountThisSession = value }
    internal var lowAccuracyFallbackArmCountThisSession
        get() = state.lowAccuracyFallbackArmCountThisSession
        set(value) { state.lowAccuracyFallbackArmCountThisSession = value }
    internal var lowAccuracyFallbackCancelCountThisSession
        get() = state.lowAccuracyFallbackCancelCountThisSession
        set(value) { state.lowAccuracyFallbackCancelCountThisSession = value }
    internal var lowAccuracyFallbackRejectedFixCountThisSession
        get() = state.lowAccuracyFallbackRejectedFixCountThisSession
        set(value) { state.lowAccuracyFallbackRejectedFixCountThisSession = value }
    internal var lowAccuracyFallbackLastRejectSummaryAtMs
        get() = state.lowAccuracyFallbackLastRejectSummaryAtMs
        set(value) { state.lowAccuracyFallbackLastRejectSummaryAtMs = value }
    internal val lowAccuracyFallbackCoordinator get() = deps.lowAccuracyFallbackCoordinator
    internal var lowAccuracyFallbackJob: Job?
        get() = state.lowAccuracyFallbackJob
        set(value) { state.lowAccuracyFallbackJob = value }
    internal val repeatedOutlierSuppressor get() = deps.repeatedOutlierSuppressor
    internal val freshnessRecoveryController get() = deps.freshnessRecoveryController
    internal val providerHealthController get() = deps.providerHealthController
    internal val pointFreshnessTracker get() = deps.pointFreshnessTracker
    internal val autoTrackingMotionEngine get() = deps.autoTrackingMotionEngine
    internal val autoTrackingMotionCoordinator get() = deps.autoTrackingMotionCoordinator
    internal var significantMotionBridge: SignificantMotionResumeBridge?
        get() = deps.significantMotionBridge
        set(value) { deps.significantMotionBridge = value }
    internal val pushDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var lastLowAccuracyFallbackWaitReason
        get() = state.lastLowAccuracyFallbackWaitReason
        set(value) { state.lastLowAccuracyFallbackWaitReason = value }
    internal var lastLoggedPointEmissionTrouble: PointEmissionTrouble
        get() = state.lastLoggedPointEmissionTrouble
        set(value) { state.lastLoggedPointEmissionTrouble = value }
    internal var lastAccuracyHoldLogKey
        get() = state.lastAccuracyHoldLogKey
        set(value) { state.lastAccuracyHoldLogKey = value }
    internal var lastLocationFilterLogSignature
        get() = state.lastLocationFilterLogSignature
        set(value) { state.lastLocationFilterLogSignature = value }
    internal var lastPositioningDiagnosticSnapshotKey
        get() = state.lastPositioningDiagnosticSnapshotKey
        set(value) { state.lastPositioningDiagnosticSnapshotKey = value }
    internal var lastAutoModeChangedAtMs
        get() = state.lastAutoModeChangedAtMs
        set(value) { state.lastAutoModeChangedAtMs = value }
    internal var autoModeTickJob
        get() = state.autoModeTickJob
        set(value) { state.autoModeTickJob = value }
    internal var locationRequestReapplyRetryJob
        get() = state.locationRequestReapplyRetryJob
        set(value) { state.locationRequestReapplyRetryJob = value }
    internal var lastAppliedLocationRequestKey: LocationRequestKey?
        get() = state.lastAppliedLocationRequestKey
        set(value) { state.lastAppliedLocationRequestKey = value }
    internal var lastLocationRequestAppliedAtMs
        get() = state.lastLocationRequestAppliedAtMs
        set(value) { state.lastLocationRequestAppliedAtMs = value }
    internal var lastFixDeliveryAtMs
        get() = state.lastFixDeliveryAtMs
        set(value) { state.lastFixDeliveryAtMs = value }
    internal var fixDeliveryWatchdogJob
        get() = state.fixDeliveryWatchdogJob
        set(value) { state.fixDeliveryWatchdogJob = value }
    internal var elasticDistanceOverrideMeters
        get() = state.elasticDistanceOverrideMeters
        set(value) { state.elasticDistanceOverrideMeters = value }
    internal var elasticitySpeedBucket
        get() = state.elasticitySpeedBucket
        set(value) { state.elasticitySpeedBucket = value }
    internal var lastSpeedReferenceLocation
        get() = state.lastSpeedReferenceLocation
        set(value) { state.lastSpeedReferenceLocation = value }
    internal var isFastGpsLockWindowActive
        get() = state.isFastGpsLockWindowActive
        set(value) { state.isFastGpsLockWindowActive = value }
    internal var isFastGpsLockPriming
        get() = state.isFastGpsLockPriming
        set(value) { state.isFastGpsLockPriming = value }
    internal var fastGpsLockWindowJob
        get() = state.fastGpsLockWindowJob
        set(value) { state.fastGpsLockWindowJob = value }
    internal var fastGpsLockSampleCount
        get() = state.fastGpsLockSampleCount
        set(value) { state.fastGpsLockSampleCount = value }
    internal var fastGpsLockPreferredSample
        get() = state.fastGpsLockPreferredSample
        set(value) { state.fastGpsLockPreferredSample = value }
    internal var fastGpsLockBestAccuracySample
        get() = state.fastGpsLockBestAccuracySample
        set(value) { state.fastGpsLockBestAccuracySample = value }
    internal var fastGpsLockFreshestSample
        get() = state.fastGpsLockFreshestSample
        set(value) { state.fastGpsLockFreshestSample = value }
    internal var fastGpsLockNewestSample
        get() = state.fastGpsLockNewestSample
        set(value) { state.fastGpsLockNewestSample = value }
    internal var fastGpsLockStartCountThisSession
        get() = state.fastGpsLockStartCountThisSession
        set(value) { state.fastGpsLockStartCountThisSession = value }
    internal var fastGpsLockStopCountThisSession
        get() = state.fastGpsLockStopCountThisSession
        set(value) { state.fastGpsLockStopCountThisSession = value }
    internal var fastGpsLockTimeoutCountThisSession
        get() = state.fastGpsLockTimeoutCountThisSession
        set(value) { state.fastGpsLockTimeoutCountThisSession = value }
    internal var fastGpsLockLastSummaryAtMs
        get() = state.fastGpsLockLastSummaryAtMs
        set(value) { state.fastGpsLockLastSummaryAtMs = value }
    internal var sigMotionSensorStartTime
        get() = state.sigMotionSensorStartTime
        set(value) { state.sigMotionSensorStartTime = value }
    internal var watchdogJob
        get() = state.watchdogJob
        set(value) { state.watchdogJob = value }
    internal var consecutiveStationaryPoints
        get() = state.consecutiveStationaryPoints
        set(value) { state.consecutiveStationaryPoints = value }
    internal var stationaryAnchorLocation
        get() = state.stationaryAnchorLocation
        set(value) { state.stationaryAnchorLocation = value }
    internal var consecutivePushFailures
        get() = state.consecutivePushFailures
        set(value) { state.consecutivePushFailures = value }
    internal var lastSyncFailureClass: com.geovault.tracker.location.SyncFailureClass
        get() = state.lastSyncFailureClass
        set(value) { state.lastSyncFailureClass = value }
    internal var gpsRuntimeState
        get() = state.gpsRuntimeState
        set(value) { state.gpsRuntimeState = value }
    internal var trackingGeneration
        get() = state.trackingGeneration
        set(value) { state.trackingGeneration = value }
    internal var runtimeSnapshot: com.geovault.tracker.services.TrackingRuntimeSnapshot
        get() = state.runtimeSnapshot
        set(value) { state.runtimeSnapshot = value }
    internal var recoveryAnchorState: com.geovault.tracker.location.RecoveryAnchorState?
        get() = state.recoveryAnchorState
        set(value) { state.recoveryAnchorState = value }
    internal var uploadLivenessState: com.geovault.tracker.services.UploadLivenessState
        get() = state.uploadLivenessState
        set(value) { state.uploadLivenessState = value }
    internal var recoveryHeartbeatJob
        get() = state.recoveryHeartbeatJob
        set(value) { state.recoveryHeartbeatJob = value }
    internal var retryJob
        get() = state.retryJob
        set(value) { state.retryJob = value }
    internal var backlogUploaderJob
        get() = state.backlogUploaderJob
        set(value) { state.backlogUploaderJob = value }
    internal var preflightJob
        get() = state.preflightJob
        set(value) { state.preflightJob = value }
    internal var gpsProviderReceiverRegistered
        get() = state.gpsProviderReceiverRegistered
        set(value) { state.gpsProviderReceiverRegistered = value }
    internal val runtimeSnapshotLock get() = state.runtimeSnapshotLock
    internal val startupStateLock get() = state.startupStateLock

    internal val locationListener: LocationListener = LocationListener { location ->
        if (!isTracking) return@LocationListener
        if (!isGpsProviderEnabled()) {
            enterWaitingForGpsProvider(reason = "location_callback")
            return@LocationListener
        }
        if (isWaitingForProviderState()) {
            resumeFromGpsProviderWait(reason = "location_callback")
            if (isWaitingForProviderState()) {
                return@LocationListener
            }
        }
        val locationSnapshot = Location(location)
        lastFixDeliveryAtMs = System.currentTimeMillis()
        providerHealthController.markFixDelivered(lastFixDeliveryAtMs)
        latestObservedRawLocation = Location(locationSnapshot)
        ingestScope.launch {
            processLocationUpdateSerialized(locationSnapshot)
        }
    }

    internal val gpsProviderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isTracking) return
            if (isGpsProviderEnabled()) {
                resumeFromGpsProviderWait(reason = "provider_broadcast")
            } else {
                enterWaitingForGpsProvider(reason = "provider_broadcast")
            }
        }
    }

    internal val settingsRepositoryLazy by lazy {
        TrackerAppServices.from(service.application).trackerSettingsRepository()
    }

    fun onCreate() {
        TrackingServiceLifecycleGate.markStarting()
        try {
            GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "onCreate")
            deps = PositioningDependencies(runtime = this, service = service, serviceScope = serviceScope)
            deps.wire(settingsRepositoryLazy)
            SelectedTrackerManager.syncRuntimeSelectedTracker(service)
            TrackingRecoveryCoordinator.markHeartbeat(service.applicationContext)
            syncRuntimeStateStore()
            startSparseTrackingObserver()
            TrackingServiceLifecycleGate.markUsable()
        } catch (t: Throwable) {
            TrackingServiceLifecycleGate.markDestroyed()
            throw t
        }
    }

    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startupTrigger = TrackingServiceIntents.resolveStartupTrigger(intent?.action)
        val commandPath = TrackingServiceIntents.resolveStartupCommandPath(action = intent?.action)
        val foregroundStartRequired = intent?.getBooleanExtra(
            TrackingServiceIntents.EXTRA_FOREGROUND_SERVICE_START_REQUIRED,
            false
        ) == true
        GeoVaultCaptureLog.i(
            TrackingServiceConstants.TAG,
            "onStartCommand action=${intent?.action} path=$commandPath startId=$startId " +
                "trigger=$startupTrigger isTracking=$isTracking foregroundStartRequired=$foregroundStartRequired"
        )
        logBackgroundWakeupDiagnostics(commandPath, foregroundStartRequired, intent)
        if (commandPath != TrackingServiceIntents.StartupCommandPath.LocationUpdate) {
            logNotificationSurfaceDiagnostics(
                trigger = startupTrigger,
                action = intent?.action,
                path = commandPath,
                stage = "on_start_command"
            )
        }
        if (TrackingServiceIntents.requiresForegroundPromotion(commandPath, foregroundStartRequired) &&
            !promoteToForegroundForStartup(
                trigger = startupTrigger,
                action = intent?.action,
                path = commandPath
            )
        ) {
            stopSelfSafelyAfterStartup(reason = "fgs_promotion_failed")
            return Service.START_NOT_STICKY
        }

        return when (commandPath) {
            TrackingServiceIntents.StartupCommandPath.StartTracking -> {
                if (requestStartTracking(path = commandPath, trigger = startupTrigger)) Service.START_STICKY else Service.START_NOT_STICKY
            }
            TrackingServiceIntents.StartupCommandPath.StopNoRestart -> {
                TrackingRecoveryCoordinator.markIntentionalStop(service.applicationContext, reason = "restart_not_required")
                stopSelfSafelyAfterStartup(reason = "restart_not_required")
                Service.START_NOT_STICKY
            }
            TrackingServiceIntents.StartupCommandPath.ReshowForeground -> {
                if (isTracking) {
                    serviceScope.launch(Dispatchers.IO) {
                        val trackerId = SelectedTrackerPrefs.selectedTrackerId(service)
                        val count = database.locationDao().getCurrentSessionCountForTracker(
                            trackerId = trackerId,
                            sessionBoundaryId = sessionVisibleBoundaryId
                        )
                        updateRuntimeSnapshot { it.copy(queuedPointsVisible = count) }
                        syncRuntimeStateStore()
                        withContext(Dispatchers.Main) {
                            service.startForeground(
                                TrackingServiceConstants.NOTIFICATION_ID,
                                notificationPresenter.buildTrackingNotification(runtimeSnapshot),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            )
                            logNotificationSurfaceDiagnostics(
                                trigger = startupTrigger,
                                action = intent?.action,
                                path = commandPath,
                                stage = "reshow_foreground"
                            )
                        }
                    }
                }
                Service.START_STICKY
            }
            TrackingServiceIntents.StartupCommandPath.ManualSendPoint -> {
                handleManualSendPointCommand()
                if (isTracking) Service.START_STICKY else Service.START_NOT_STICKY
            }
            TrackingServiceIntents.StartupCommandPath.LocationUpdate -> {
                if (handleLocationUpdateCommand(intent)) {
                    Service.START_STICKY
                } else {
                    stopSelfSafelyAfterStartup(reason = "location_update_not_tracking")
                    Service.START_NOT_STICKY
                }
            }
            TrackingServiceIntents.StartupCommandPath.StopUnknown -> {
                if (intent?.action == TrackingServiceIntents.ACTION_STOP) {
                    stopTracking(reason = "action_stop")
                } else {
                    TrackingRecoveryCoordinator.markIntentionalStop(service.applicationContext, reason = "unknown_action")
                    stopSelfSafelyAfterStartup(reason = "unknown_action")
                }
                Service.START_NOT_STICKY
            }
        }
    }

    fun onBind(intent: Intent?): IBinder? = null



    fun onTaskRemoved(rootIntent: Intent?) {
        GeoVaultCaptureLog.i(
            TrackingServiceConstants.TAG,
            "lifecycle_correlation event=task_removed isTracking=$isTracking startupInProgress=$startupInProgress " +
                "gpsState=$gpsRuntimeState lifecycle=${controlState.lifecycleState} generation=$trackingGeneration " +
                "rootAction=${rootIntent?.action ?: "none"}"
        )
        TrackingRuntimeController.get(service.applicationContext).handle(
            com.geovault.tracker.runtime.RuntimeCommand(
                type = com.geovault.tracker.runtime.RuntimeCommandType.TASK_REMOVED,
                trigger = com.geovault.tracker.runtime.RuntimeTrigger.TASK_REMOVED,
                reason = "task_removed"
            )
        )
    }

    fun onDestroy() {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "onDestroy isTracking=$isTracking")
        TrackingServiceLifecycleGate.markDestroying()
        if (isTracking) {
            TrackingRecoveryCoordinator.markUnexpectedDestroy(service.applicationContext, wasTracking = true)
            transitionToStoppedState(failureReason = "unexpected_destroy")
        }
        cleanupServiceResources(reason = "on_destroy")
        significantMotionBridge?.cancel()
        significantMotionBridge = null
        stationaryFreshnessCoordinator.onStopped(reason = "on_destroy")
        serviceJob.cancel()
        TrackingServiceLifecycleGate.markDestroyed()
    }












    /**
     * Service-owned stationary probe timer. While GPS is paused for
     * stationarity, this wakes GPS inside the active foreground service so
     * the tracker can verify the device is still still and emit the anchored
     * freshness point. Significant-motion resume uses the same GPS resume
     * machinery, but this path marks the next fixes as paused-freshness
     * candidates before resuming.
     */
















    /**
     * Applies [RuntimeAccuracyHoldPolicy] for the incoming fix's accuracy and folds the result
     * into the snapshot so brief noisy fixes after a wakeup don't flicker the UI accuracy
     * indicator. Optional [extraTransform] runs against the snapshot *after* the accuracy fields
     * are folded in, so callers can update unrelated fields atomically alongside the accuracy
     * decision.
     */


























































































}
