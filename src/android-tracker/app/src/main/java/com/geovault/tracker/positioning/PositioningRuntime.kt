package com.geovault.tracker.positioning

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import android.location.Location
import android.location.LocationListener
import android.os.IBinder
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.positioning.collection.GpsCollectionSubsystem
import com.geovault.tracker.positioning.collection.LocationRequestSubsystem
import com.geovault.tracker.positioning.ingest.FixIngestSubsystem
import com.geovault.tracker.positioning.motion.MotionSubsystem
import com.geovault.tracker.positioning.recovery.RecoverySubsystem
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.runtime.RuntimeCommand
import com.geovault.tracker.runtime.RuntimeCommandType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.tracking.TrackingService
import com.geovault.tracker.tracking.TrackingServiceConstants
import com.geovault.tracker.tracking.TrackingServiceIntents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal class PositioningRuntime(
    val ports: PositioningAndroidPorts,
) {
    interface Listener {
        fun onFixProcessed(accepted: Boolean, pointPersisted: Boolean) {}
        fun onCollectionStateChanged() {}
    }

    val state = PositioningSessionState()
    val service: TrackingService get() = ports.service

    internal val serviceJob = SupervisorJob()
    internal val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    internal val ingestScope = CoroutineScope(serviceJob + Dispatchers.Default)
    internal val locationUpdateMutex = Mutex()
    internal val pushDispatcher: CoroutineDispatcher = Dispatchers.IO

    internal lateinit var deps: PositioningDependencies
    internal var listener: Listener = object : Listener {}

    internal lateinit var utilities: PositioningHostUtilities
    internal lateinit var contextBuilder: PositioningContextBuilder
    internal lateinit var projection: RuntimeProjectionSubsystem
    internal lateinit var collection: GpsCollectionSubsystem
    internal lateinit var locationRequests: LocationRequestSubsystem
    internal lateinit var recovery: RecoverySubsystem
    internal lateinit var motion: MotionSubsystem
    internal lateinit var fixIngest: FixIngestSubsystem
    internal lateinit var lifecycle: SessionLifecycleSubsystem
    internal lateinit var foreground: ForegroundSubsystem
    internal lateinit var commands: CommandDiagnosticsSubsystem
    internal lateinit var manualFix: ManualFixSubsystem
    internal lateinit var upload: UploadSubsystem

    internal val localTrackPointOrderingCounter get() = state.localTrackPointOrderingCounter

    internal val locationListener: LocationListener = LocationListener { location ->
        if (!state.isTracking) return@LocationListener
        if (!utilities.isGpsProviderEnabled()) {
            collection.enterWaitingForGpsProvider(reason = "location_callback")
            return@LocationListener
        }
        if (utilities.isWaitingForProviderState()) {
            collection.resumeFromGpsProviderWait(reason = "location_callback")
            if (utilities.isWaitingForProviderState()) {
                return@LocationListener
            }
        }
        val locationSnapshot = Location(location)
        state.lastFixDeliveryAtMs = System.currentTimeMillis()
        deps.providerHealthController.markFixDelivered(state.lastFixDeliveryAtMs)
        state.latestObservedRawLocation = Location(locationSnapshot)
        ingestScope.launch {
            fixIngest.processLocationUpdateSerialized(locationSnapshot)
        }
    }

    internal val gpsProviderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!state.isTracking) return
            if (utilities.isGpsProviderEnabled()) {
                collection.resumeFromGpsProviderWait(reason = "provider_broadcast")
            } else {
                collection.enterWaitingForGpsProvider(reason = "provider_broadcast")
            }
        }
    }

    private val settingsRepositoryLazy by lazy {
        TrackerAppServices.from(service.application).trackerSettingsRepository()
    }

    private fun wireSubsystems() {
        utilities = PositioningHostUtilities(this)
        contextBuilder = PositioningContextBuilder(this)
        projection = RuntimeProjectionSubsystem(this)
        collection = GpsCollectionSubsystem(this)
        locationRequests = LocationRequestSubsystem(this)
        recovery = RecoverySubsystem(this)
        motion = MotionSubsystem(this)
        fixIngest = FixIngestSubsystem(this)
        lifecycle = SessionLifecycleSubsystem(this)
        foreground = ForegroundSubsystem(this)
        commands = CommandDiagnosticsSubsystem(this)
        manualFix = ManualFixSubsystem(this)
        upload = UploadSubsystem(this)
    }

    fun onCreate() {
        TrackingServiceLifecycleGate.markStarting()
        try {
            GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "onCreate")
            deps = PositioningDependencies(runtime = this, service = service, serviceScope = serviceScope)
            wireSubsystems()
            deps.wire(settingsRepositoryLazy)
            SelectedTrackerManager.syncRuntimeSelectedTracker(service)
            TrackingRecoveryCoordinator.markHeartbeat(service.applicationContext)
            projection.syncRuntimeStateStore()
            contextBuilder.startSparseTrackingObserver()
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
            false,
        ) == true
        GeoVaultCaptureLog.i(
            TrackingServiceConstants.TAG,
            "onStartCommand action=${intent?.action} path=$commandPath startId=$startId " +
                "trigger=$startupTrigger isTracking=${state.isTracking} foregroundStartRequired=$foregroundStartRequired",
        )
        commands.logBackgroundWakeupDiagnostics(commandPath, foregroundStartRequired, intent)
        if (commandPath != TrackingServiceIntents.StartupCommandPath.LocationUpdate) {
            foreground.logNotificationSurfaceDiagnostics(
                trigger = startupTrigger,
                action = intent?.action,
                path = commandPath,
                stage = "on_start_command",
            )
        }
        if (
            TrackingServiceIntents.requiresForegroundPromotion(commandPath, foregroundStartRequired) &&
            !foreground.promoteToForegroundForStartup(
                trigger = startupTrigger,
                action = intent?.action,
                path = commandPath,
            )
        ) {
            foreground.stopSelfSafelyAfterStartup(reason = "fgs_promotion_failed")
            return Service.START_NOT_STICKY
        }

        return when (commandPath) {
            TrackingServiceIntents.StartupCommandPath.StartTracking -> {
                if (lifecycle.requestStartTracking(path = commandPath, trigger = startupTrigger)) {
                    Service.START_STICKY
                } else {
                    Service.START_NOT_STICKY
                }
            }
            TrackingServiceIntents.StartupCommandPath.StopNoRestart -> {
                TrackingRecoveryCoordinator.markIntentionalStop(service.applicationContext, reason = "restart_not_required")
                foreground.stopSelfSafelyAfterStartup(reason = "restart_not_required")
                Service.START_NOT_STICKY
            }
            TrackingServiceIntents.StartupCommandPath.ReshowForeground -> {
                if (state.isTracking) {
                    serviceScope.launch(Dispatchers.IO) {
                        val trackerId = ports.selectedTrackerId()
                        val count = deps.database.locationDao().getCurrentSessionCountForTracker(
                            trackerId = trackerId,
                            sessionBoundaryId = state.sessionVisibleBoundaryId,
                        )
                        projection.updateRuntimeSnapshot { it.copy(queuedPointsVisible = count) }
                        projection.syncRuntimeStateStore()
                        withContext(Dispatchers.Main) {
                            val notification: Notification = deps.notificationPresenter.buildTrackingNotification(
                                state.runtimeSnapshot,
                            )
                            ports.startForeground(notification)
                            foreground.logNotificationSurfaceDiagnostics(
                                trigger = startupTrigger,
                                action = intent?.action,
                                path = commandPath,
                                stage = "reshow_foreground",
                            )
                        }
                    }
                }
                Service.START_STICKY
            }
            TrackingServiceIntents.StartupCommandPath.ManualSendPoint -> {
                manualFix.handleManualSendPointCommand()
                if (state.isTracking) Service.START_STICKY else Service.START_NOT_STICKY
            }
            TrackingServiceIntents.StartupCommandPath.LocationUpdate -> {
                if (commands.handleLocationUpdateCommand(intent)) {
                    Service.START_STICKY
                } else {
                    foreground.stopSelfSafelyAfterStartup(reason = "location_update_not_tracking")
                    Service.START_NOT_STICKY
                }
            }
            TrackingServiceIntents.StartupCommandPath.StopUnknown -> {
                if (intent?.action == TrackingServiceIntents.ACTION_STOP) {
                    lifecycle.stopTracking(reason = "action_stop")
                } else {
                    TrackingRecoveryCoordinator.markIntentionalStop(service.applicationContext, reason = "unknown_action")
                    foreground.stopSelfSafelyAfterStartup(reason = "unknown_action")
                }
                Service.START_NOT_STICKY
            }
        }
    }

    fun onBind(intent: Intent?): IBinder? = null

    fun onTaskRemoved(rootIntent: Intent?) {
        GeoVaultCaptureLog.i(
            TrackingServiceConstants.TAG,
            "lifecycle_correlation event=task_removed isTracking=${state.isTracking} " +
                "startupInProgress=${state.startupInProgress} gpsState=${state.gpsRuntimeState} " +
                "lifecycle=${state.controlState.lifecycleState} generation=${state.trackingGeneration} " +
                "rootAction=${rootIntent?.action ?: "none"}",
        )
        TrackingRuntimeController.get(service.applicationContext).handle(
            RuntimeCommand(
                type = RuntimeCommandType.TASK_REMOVED,
                trigger = RuntimeTrigger.TASK_REMOVED,
                reason = "task_removed",
            ),
        )
    }

    fun onDestroy() {
        GeoVaultCaptureLog.d(TrackingServiceConstants.TAG, "onDestroy isTracking=${state.isTracking}")
        TrackingServiceLifecycleGate.markDestroying()
        if (state.isTracking) {
            TrackingRecoveryCoordinator.markUnexpectedDestroy(service.applicationContext, wasTracking = true)
            lifecycle.transitionToStoppedState(failureReason = "unexpected_destroy")
        }
        lifecycle.cleanupServiceResources(reason = "on_destroy")
        deps.significantMotionBridge?.cancel()
        deps.significantMotionBridge = null
        deps.stationaryFreshnessCoordinator.onStopped(reason = "on_destroy")
        serviceJob.cancel()
        TrackingServiceLifecycleGate.markDestroyed()
    }

    internal fun notifyFixProcessed(accepted: Boolean, pointPersisted: Boolean) {
        listener.onFixProcessed(accepted, pointPersisted)
    }

    internal fun notifyCollectionStateChanged() {
        listener.onCollectionStateChanged()
    }
}
