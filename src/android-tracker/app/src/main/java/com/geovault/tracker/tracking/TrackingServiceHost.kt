package com.geovault.tracker.tracking

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
import com.geovault.tracker.location.TrackerLocationMotionContext
import com.geovault.tracker.location.TrackerLocationPipeline
import com.geovault.tracker.location.FixIngestMode
import com.geovault.tracker.location.TrackerLocationPipelineInput
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
import com.geovault.tracker.services.GpsRuntimeEvent
import com.geovault.tracker.services.GpsRuntimeState
import com.geovault.tracker.services.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.PositioningDensity
import com.geovault.tracker.services.PositioningPresetValues
import com.geovault.tracker.services.PositioningPresets
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.TrackerPositioningRuntimeContext
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.PositioningPolicyConfig
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

internal class TrackingServiceHost(
    internal val service: TrackingService,
) {
    val session = TrackingRecordingSession()

    internal val serviceJob = SupervisorJob()
    internal val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    internal val ingestScope = CoroutineScope(serviceJob + Dispatchers.Default)
    internal lateinit var deps: TrackingServiceDependencies

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
        get() = session.sparseTrackingObserverJob
        set(value) { session.sparseTrackingObserverJob = value }
    internal var httpClient: OkHttpClient?
        get() = deps.httpClient
        set(value) { deps.httpClient = value }
    internal val locationUpdateMutex = Mutex()
    internal val localTrackPointOrderingCounter get() = session.localTrackPointOrderingCounter

    @Volatile
    internal var isTracking: Boolean = false
    @Volatile
    internal var startupInProgress: Boolean = false
    @Volatile
    internal var startupReadyForEvents: Boolean = false
    internal var controlState: TrackingControlState = TrackingControlState()
    internal var startupForegroundPromoted: Boolean = false
    internal var sessionVisibleBoundaryId: Long = 0L
    internal var sessionBoundaryForBacklogId: Long = 0L
    internal var lastFilteredLocation: Location? = null
    internal var latestObservedRawLocation: Location? = null
    internal var lowAccuracyFallbackCandidate: Location? = null
    internal var lowAccuracyFallbackTimerArmedAtMs: Long = 0L
    internal var lowAccuracyFallbackEmitCountThisSession: Int = 0
    internal var lowAccuracyFallbackArmCountThisSession: Int = 0
    internal var lowAccuracyFallbackCancelCountThisSession: Int = 0
    internal var lowAccuracyFallbackRejectedFixCountThisSession: Int = 0
    internal var lowAccuracyFallbackLastRejectSummaryAtMs: Long = 0L
    internal var lastLowAccuracyFallbackWaitReason: String? = null
    internal val lowAccuracyFallbackCoordinator get() = deps.lowAccuracyFallbackCoordinator
    internal var lowAccuracyFallbackJob: Job?
        get() = session.lowAccuracyFallbackJob
        set(value) { session.lowAccuracyFallbackJob = value }
    internal val repeatedOutlierSuppressor get() = deps.repeatedOutlierSuppressor
    internal val freshnessRecoveryController get() = deps.freshnessRecoveryController
    internal val providerHealthController get() = deps.providerHealthController
    internal var recoveryAnchorState: RecoveryAnchorState? = null
    internal var uploadLivenessState: UploadLivenessState = UploadLivenessState()
    internal val pointFreshnessTracker get() = deps.pointFreshnessTracker
    internal var lastLoggedPointEmissionTrouble: PointEmissionTrouble = PointEmissionTrouble.None
    internal var lastAccuracyHoldLogKey: String? = null
    internal var lastLocationFilterLogSignature: String? = null
    internal var lastPositioningDiagnosticSnapshotKey: String? = null
    internal val autoTrackingMotionEngine get() = deps.autoTrackingMotionEngine
    internal val autoTrackingMotionCoordinator get() = deps.autoTrackingMotionCoordinator
    internal var lastAutoModeChangedAtMs: Long = 0L
    internal var autoModeTickJob: Job? = null
    internal var locationRequestReapplyRetryJob: Job? = null
    internal var lastAppliedLocationRequestKey: LocationRequestKey? = null
    internal var lastLocationRequestAppliedAtMs: Long = 0L
    internal var lastFixDeliveryAtMs: Long = 0L
    internal var fixDeliveryWatchdogJob: Job? = null
    internal var elasticDistanceOverrideMeters: Float? = null
    internal var elasticitySpeedBucket: Int = 0
    internal var lastSpeedReferenceLocation: Location? = null
    internal var isFastGpsLockWindowActive: Boolean = false
    internal var isFastGpsLockPriming: Boolean = false
    internal var fastGpsLockWindowJob: Job? = null
    internal var fastGpsLockSampleCount: Int = 0
    internal var fastGpsLockPreferredSample: Location? = null
    internal var fastGpsLockBestAccuracySample: Location? = null
    internal var fastGpsLockFreshestSample: Location? = null
    internal var fastGpsLockNewestSample: Location? = null
    internal var fastGpsLockStartCountThisSession: Int = 0
    internal var fastGpsLockStopCountThisSession: Int = 0
    internal var fastGpsLockTimeoutCountThisSession: Int = 0
    internal var fastGpsLockLastSummaryAtMs: Long = 0L
    internal var sigMotionSensorStartTime: Long = 0L
    internal var watchdogJob: Job? = null
    internal var significantMotionBridge: SignificantMotionResumeBridge?
        get() = deps.significantMotionBridge
        set(value) { deps.significantMotionBridge = value }
    internal var consecutiveStationaryPoints: Int = 0
    internal var stationaryAnchorLocation: Location? = null
    internal var consecutivePushFailures = 0
    internal var lastSyncFailureClass: SyncFailureClass = SyncFailureClass.NONE
    @Volatile
    internal var gpsRuntimeState: GpsRuntimeState = GpsRuntimeState.INACTIVE
    internal var trackingGeneration: Int = 0
    internal val runtimeSnapshotLock = Any()
    internal var runtimeSnapshot: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot()
    internal val startupStateLock = Any()
    internal val pushDispatcher: CoroutineDispatcher = Dispatchers.IO

    internal val locationListener: LocationListener = LocationListener { location ->
        if (!isTracking) return@LocationListener
        if (!isGpsProviderEnabled()) {
            enterWaitingForGpsProvider(reason = "location_callback")
            return@LocationListener
        }
        if (isWaitingForProviderState()) {
            resumeFromGpsProviderWait(reason = "location_callback")
            if (isWaitingForProviderState()) {
                // Resume did not reactivate tracking updates yet.
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

    internal var recoveryHeartbeatJob: Job? = null
    internal var retryJob: Job? = null
    internal var backlogUploaderJob: Job? = null
    internal var preflightJob: Job? = null
    internal var gpsProviderReceiverRegistered: Boolean = false
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
            deps = TrackingServiceDependencies(host = this, service = service, serviceScope = serviceScope)
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
