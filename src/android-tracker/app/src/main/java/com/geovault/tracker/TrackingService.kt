package com.geovault.tracker

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
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.NetworkStatusMonitor
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
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.GpsRuntimeEvent
import com.geovault.tracker.services.GpsRuntimeState
import com.geovault.tracker.services.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.TrackingPolicyProfiles
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingUiStatusResolver
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TrackingService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val ingestScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private lateinit var database: AppDatabase
    private lateinit var settingsRepository: TrackerSettingsRepository
    private lateinit var sessionCoordinator: TrackingSessionCoordinator
    private lateinit var locationIngestCoordinator: LocationIngestCoordinator
    private lateinit var notificationPresenter: TrackingNotificationPresenter
    private lateinit var runtimeEventPublisher: RuntimeEventPublisher
    private lateinit var queueUploadEngine: QueueUploadEngine
    private lateinit var locationSessionCoordinator: LocationSessionCoordinator
    private lateinit var runtimeTelemetry: RuntimeTelemetry
    private var httpClient: OkHttpClient? = null
    private val locationUpdateMutex = Mutex()
    private val localTrackPointOrderingCounter = AtomicLong(0L)

    @Volatile
    private var isTracking: Boolean = false
    @Volatile
    private var startupInProgress: Boolean = false
    @Volatile
    private var startupReadyForEvents: Boolean = false
    private var controlState: TrackingControlState = TrackingControlState()
    private var startupForegroundPromoted: Boolean = false
    private var sessionVisibleBoundaryId: Long = 0L
    private var sessionBoundaryForBacklogId: Long = 0L
    private var lastFilteredLocation: Location? = null
    private var latestObservedRawLocation: Location? = null
    private var lowAccuracyFallbackCandidate: Location? = null
    private var lowAccuracyFallbackTimerArmedAtMs: Long = 0L
    private var lowAccuracyFallbackEmitCountThisSession: Int = 0
    private var lowAccuracyFallbackArmCountThisSession: Int = 0
    private var lowAccuracyFallbackCancelCountThisSession: Int = 0
    private var lowAccuracyFallbackRejectedFixCountThisSession: Int = 0
    private var lowAccuracyFallbackLastRejectSummaryAtMs: Long = 0L
    private val lowAccuracyFallbackCoordinator = LowAccuracyFallbackCoordinator()
    private var lowAccuracyFallbackJob: Job? = null
    private val autoTrackingMotionEngine = AutoTrackingMotionEngine()
    private var autoModeTickJob: Job? = null
    private var elasticDistanceOverrideMeters: Float? = null
    private var elasticitySpeedBucket: Int = 0
    private var lastSpeedReferenceLocation: Location? = null
    private var isFastGpsLockWindowActive: Boolean = false
    private var isFastGpsLockPriming: Boolean = false
    private var fastGpsLockWindowJob: Job? = null
    private var fastGpsLockSampleCount: Int = 0
    private var fastGpsLockPreferredSample: Location? = null
    private var fastGpsLockBestAccuracySample: Location? = null
    private var fastGpsLockFreshestSample: Location? = null
    private var fastGpsLockNewestSample: Location? = null
    private var fastGpsLockStartCountThisSession: Int = 0
    private var fastGpsLockStopCountThisSession: Int = 0
    private var fastGpsLockTimeoutCountThisSession: Int = 0
    private var fastGpsLockLastSummaryAtMs: Long = 0L
    private var sigMotionSensorStartTime: Long = 0L
    private var watchdogJob: Job? = null
    private var significantMotionBridge: SignificantMotionResumeBridge? = null
    private var consecutiveStationaryPoints: Int = 0
    private var stationaryAnchorLocation: Location? = null
    private var consecutivePushFailures = 0
    private var lastSyncFailureClass: SyncFailureClass = SyncFailureClass.NONE
    @Volatile
    private var gpsRuntimeState: GpsRuntimeState = GpsRuntimeState.INACTIVE
    private var trackingGeneration: Int = 0
    private val runtimeSnapshotLock = Any()
    private var runtimeSnapshot: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot()
    private val startupStateLock = Any()
    private val pushDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val locationListener: LocationListener = LocationListener { location ->
        if (!isTracking) return@LocationListener
        if (!isLocationServicesEnabled()) {
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
        latestObservedRawLocation = Location(locationSnapshot)
        ingestScope.launch {
            processLocationUpdateSerialized(locationSnapshot)
        }
    }

    private var recoveryHeartbeatJob: Job? = null
    private var retryJob: Job? = null
    private var backlogUploaderJob: Job? = null
    private var preflightJob: Job? = null
    private var gpsProviderReceiverRegistered: Boolean = false
    private val gpsProviderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isTracking) return
            if (isLocationServicesEnabled()) {
                resumeFromGpsProviderWait(reason = "provider_broadcast")
            } else {
                enterWaitingForGpsProvider(reason = "provider_broadcast")
            }
        }
    }

    private val settingsRepositoryLazy by lazy {
        TrackerAppServices.from(application).trackerSettingsRepository()
    }

    companion object {
        const val TAG = "TrackingService"
        const val ACTION_START = "com.geovault.tracker.ACTION_START"
        const val ACTION_STOP = "com.geovault.tracker.ACTION_STOP"
        const val ACTION_RESHOW_FOREGROUND = "com.geovault.tracker.ACTION_RESHOW_FOREGROUND"
        const val ACTION_SEND_MANUAL_POINT = "com.geovault.tracker.ACTION_SEND_MANUAL_POINT"
        const val ACTION_LOCATION_UPDATE = "com.geovault.tracker.ACTION_LOCATION_UPDATE"
        const val ACTION_TRACKING_ERROR = "com.geovault.tracker.ACTION_TRACKING_ERROR"
        const val EXTRA_TRACKING_ERROR_MESSAGE = "extra_tracking_error_message"
        const val NOTIFICATION_DISMISSED_ACTION = "com.geovault.tracker.TRACKING_NOTIFICATION_DISMISSED"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "tracker_service"
        const val SESSION_STATS_UPDATE = "com.geovault.tracker.SESSION_STATS_UPDATE"

        private const val FALLBACK_TRANSITION_TRACK_ID = "fallback_transition"
        private const val MAX_QUEUE_SIZE = 5000
        private const val MAX_QUEUE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val RETRY_JITTER_MS = 10_000L
        private const val MAX_BATCHES_PER_PUSH = 10
        private const val EXTRAS_KEY_LOW_ACCURACY_FALLBACK = "low_accuracy_fallback"
        private const val EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER = "fallback_source_provider"
        private const val EXTRAS_KEY_MANUAL_SEND = "manual_send"
        private const val EXTRA_LOCATION_UPDATES = "extra_location_updates"
        private const val FALLBACK_REJECT_SUMMARY_INTERVAL_MS = 30_000L
        private const val FAST_GPS_LOCK_WINDOW_MS = 60_000L
        private const val FAST_GPS_LOCK_MIN_SAMPLES = 3
        private const val FAST_GPS_LOCK_EARLY_EXIT_MIN_SAMPLES = 2
        private const val FAST_GPS_LOCK_MAX_LAST_LOCATION_AGE_MS = 30_000L
        private const val FAST_GPS_LOCK_MAX_SAMPLE_AGE_MS = 30_000L
        private const val FAST_GPS_LOCK_SUMMARY_INTERVAL_MS = 30_000L
        private const val ELASTICITY_SPEED_BUCKET_SIZE_MPS = 5f
        private const val ELASTICITY_MULTIPLIER = 0.35f
        private const val ELASTICITY_MAX_SPEED_BUCKET = 8
        private const val WALKING_ELASTICITY_MAX_SPEED_BUCKET = 2
        private const val ELASTICITY_REAPPLY_DISTANCE_DELTA_METERS = 0.5f

        @JvmStatic
        fun shouldRestartTrackingAfterProcessDeath(): Boolean = false

        internal enum class StartupCommandPath {
            StartTracking,
            StopNoRestart,
            ReshowForeground,
            ManualSendPoint,
            LocationUpdate,
            StopUnknown
        }

        @JvmStatic
        internal fun resolveStartupCommandPath(action: String?): StartupCommandPath {
            return when (action) {
                ACTION_START -> StartupCommandPath.StartTracking
                ACTION_STOP -> StartupCommandPath.StopUnknown
                ACTION_RESHOW_FOREGROUND -> StartupCommandPath.ReshowForeground
                ACTION_SEND_MANUAL_POINT -> StartupCommandPath.ManualSendPoint
                ACTION_LOCATION_UPDATE -> StartupCommandPath.LocationUpdate
                null -> {
                    if (shouldRestartTrackingAfterProcessDeath()) {
                        StartupCommandPath.StartTracking
                    } else {
                        StartupCommandPath.StopNoRestart
                    }
                }
                else -> StartupCommandPath.StopUnknown
            }
        }

        @JvmStatic
        internal fun requiresForegroundPromotion(path: StartupCommandPath): Boolean {
            return path == StartupCommandPath.StartTracking
        }

        @JvmStatic
        internal fun resolveStartupTrigger(action: String?): String {
            return when (action) {
                ACTION_START -> "explicit_start"
                ACTION_STOP -> "explicit_stop"
                ACTION_RESHOW_FOREGROUND -> "reshow_foreground"
                ACTION_SEND_MANUAL_POINT -> "manual_send_point"
                ACTION_LOCATION_UPDATE -> "location_update"
                null -> "process_restart"
                else -> "unknown_action"
            }
        }

        @JvmStatic
        fun buildLocationUpdateIntent(context: Context, locations: List<Location>): Intent {
            val appContext = context.applicationContext
            return Intent(appContext, TrackingService::class.java).apply {
                action = ACTION_LOCATION_UPDATE
                setPackage(appContext.packageName)
                putParcelableArrayListExtra(
                    EXTRA_LOCATION_UPDATES,
                    ArrayList(locations.map { Location(it) })
                )
            }
        }

        internal fun extractLocationUpdateIntentLocations(intent: Intent?): List<Location> {
            if (intent?.action != ACTION_LOCATION_UPDATE) return emptyList()
            return intent.getParcelableArrayListExtra(EXTRA_LOCATION_UPDATES, Location::class.java)
                ?.map { Location(it) }
                .orEmpty()
        }

        @JvmStatic
        fun hasValidSelectedTrackerId(selectedTrackerId: String): Boolean {
            if (selectedTrackerId.isBlank()) return false
            return try {
                java.util.UUID.fromString(selectedTrackerId)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        TrackingServiceLifecycleGate.markStarting()
        try {
            Log.d(TAG, "onCreate")
            settingsRepository = settingsRepositoryLazy
            database = AppDatabase.getDatabase(this)
            locationSessionCoordinator = LocationSessionCoordinator(this)
            sessionCoordinator = TrackingSessionCoordinator()
            locationIngestCoordinator = LocationIngestCoordinator(database.locationDao())
            notificationPresenter = TrackingNotificationPresenter(this)
            runtimeEventPublisher = RuntimeEventPublisher(applicationContext)
            runtimeTelemetry = RuntimeTelemetry(applicationContext)
            queueUploadEngine = QueueUploadEngine(
                context = applicationContext,
                locationDao = database.locationDao(),
                pushContext = pushDispatcher,
                authenticatedClientProvider = { getAuthenticatedHttpClient() }
            )
            significantMotionBridge = SignificantMotionResumeBridge(
                trigger = SensorManagerSignificantMotionTrigger(applicationContext),
                onResume = { resumeGps() }
            )
            SelectedTrackerManager.syncRuntimeSelectedTracker(this)
            TrackingRecoveryCoordinator.markHeartbeat(applicationContext)
            syncRuntimeStateStore()
            TrackingServiceLifecycleGate.markUsable()
        } catch (t: Throwable) {
            TrackingServiceLifecycleGate.markDestroyed()
            throw t
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startupTrigger = resolveStartupTrigger(intent?.action)
        val commandPath = resolveStartupCommandPath(action = intent?.action)
        Log.i(
            TAG,
            "onStartCommand action=${intent?.action} path=$commandPath startId=$startId trigger=$startupTrigger isTracking=$isTracking"
        )
        if (commandPath != StartupCommandPath.LocationUpdate) {
            logNotificationSurfaceDiagnostics(
                trigger = startupTrigger,
                action = intent?.action,
                path = commandPath,
                stage = "on_start_command"
            )
        }
        if (requiresForegroundPromotion(commandPath) &&
            !promoteToForegroundForStartup(
                trigger = startupTrigger,
                action = intent?.action,
                path = commandPath
            )
        ) {
            stopSelfSafelyAfterStartup(reason = "fgs_promotion_failed")
            return START_NOT_STICKY
        }

        return when (commandPath) {
            StartupCommandPath.StartTracking -> {
                if (requestStartTracking(path = commandPath, trigger = startupTrigger)) START_STICKY else START_NOT_STICKY
            }
            StartupCommandPath.StopNoRestart -> {
                TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = "restart_not_required")
                stopSelfSafelyAfterStartup(reason = "restart_not_required")
                START_NOT_STICKY
            }
            StartupCommandPath.ReshowForeground -> {
                if (isTracking) {
                    serviceScope.launch(Dispatchers.IO) {
                        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this@TrackingService)
                        val count = database.locationDao().getCurrentSessionCountForTracker(
                            trackerId = trackerId,
                            sessionBoundaryId = sessionVisibleBoundaryId
                        )
                        updateRuntimeSnapshot { it.copy(queuedPointsVisible = count) }
                        syncRuntimeStateStore()
                        withContext(Dispatchers.Main) {
                            startForeground(
                                NOTIFICATION_ID,
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
                START_STICKY
            }
            StartupCommandPath.ManualSendPoint -> {
                handleManualSendPointCommand()
                if (isTracking) START_STICKY else START_NOT_STICKY
            }
            StartupCommandPath.LocationUpdate -> {
                if (handleLocationUpdateCommand(intent)) {
                    START_STICKY
                } else {
                    stopSelfSafelyAfterStartup(reason = "location_update_not_tracking")
                    START_NOT_STICKY
                }
            }
            StartupCommandPath.StopUnknown -> {
                if (intent?.action == ACTION_STOP) {
                    stopTracking(reason = "action_stop")
                } else {
                    TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = "unknown_action")
                    stopSelfSafelyAfterStartup(reason = "unknown_action")
                }
                START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        TrackingRuntimeController.get(applicationContext).handle(
            com.geovault.tracker.runtime.RuntimeCommand(
                type = com.geovault.tracker.runtime.RuntimeCommandType.TASK_REMOVED,
                trigger = com.geovault.tracker.runtime.RuntimeTrigger.TASK_REMOVED,
                reason = "task_removed"
            )
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy isTracking=$isTracking")
        TrackingServiceLifecycleGate.markDestroying()
        if (isTracking) {
            TrackingRecoveryCoordinator.markUnexpectedDestroy(applicationContext, wasTracking = true)
            transitionToStoppedState(failureReason = "unexpected_destroy")
        }
        cleanupServiceResources(reason = "on_destroy")
        significantMotionBridge?.cancel()
        significantMotionBridge = null
        serviceJob.cancel()
        TrackingServiceLifecycleGate.markDestroyed()
        super.onDestroy()
    }

    private fun requestStartTracking(path: StartupCommandPath, trigger: String): Boolean {
        synchronized(startupStateLock) {
            if (isTracking) {
                Log.i(TAG, "Ignoring start request; tracking already active")
                return true
            }
            if (startupInProgress) {
                Log.i(TAG, "Ignoring start request; startup already in progress")
                return true
            }
            setStartupInProgress(true)
            startupReadyForEvents = false
        }
        transitionControlState(TrackingControlEvent.StartRequested)
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (!hasValidSelectedTrackerId(selectedTrackerId)) {
            Log.w(TAG, "Start blocked: invalid selected tracker id")
            setStartupInProgress(false)
            failStartup(
                message = getString(R.string.no_tracker_selected_go_to_settings),
                path = path,
                trigger = trigger,
                reason = "invalid_selected_tracker"
            )
            return false
        }
        if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(this)) {
            Log.w(TAG, "Start blocked: required tracking permissions missing")
            setStartupInProgress(false)
            failStartup(
                message = getString(R.string.location_permissions_required),
                path = path,
                trigger = trigger,
                reason = "permissions_missing"
            )
            return false
        }
        if (!isLocationServicesEnabled()) {
            Log.w(TAG, "Start blocked: location services disabled")
            setStartupInProgress(false)
            failStartup(
                message = getString(R.string.gps_provider_required),
                path = path,
                trigger = trigger,
                reason = "location_services_disabled"
            )
            return false
        }
        serviceScope.launch {
            try {
                performStartTracking(trigger = trigger)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "Start failed during startup pipeline", t)
                failStartup(
                    message = getString(R.string.unable_to_start_location_updates),
                    path = path,
                    trigger = trigger,
                    reason = "startup_pipeline_exception"
                )
            } finally {
                setStartupInProgress(false)
            }
        }
        return true
    }

    private suspend fun performStartTracking(trigger: String) {
        TrackPointBus.pauseLocalDelivery()
        trackingGeneration++
        val runGeneration = trackingGeneration
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (selectedTrackerId.isNotEmpty()) {
            locationIngestCoordinator.resetSession(selectedTrackerId)
        }
        sessionVisibleBoundaryId = withContext(Dispatchers.IO) {
            database.locationDao().getMaxId()
        }
        sessionBoundaryForBacklogId = sessionVisibleBoundaryId
        isTracking = true
        transitionGpsState(GpsRuntimeEvent.TRACKING_STARTED, "perform_start_tracking")
        transitionControlState(TrackingControlEvent.StartSucceeded)
        startAutoModeTickIfNeeded()
        lastFilteredLocation = null
        latestObservedRawLocation = null
        lowAccuracyFallbackCandidate = null
        lowAccuracyFallbackTimerArmedAtMs = 0L
        lowAccuracyFallbackEmitCountThisSession = 0
        lowAccuracyFallbackArmCountThisSession = 0
        lowAccuracyFallbackCancelCountThisSession = 0
        lowAccuracyFallbackRejectedFixCountThisSession = 0
        lowAccuracyFallbackLastRejectSummaryAtMs = 0L
        isFastGpsLockWindowActive = false
        isFastGpsLockPriming = false
        resetFastGpsLockSamples()
        fastGpsLockStartCountThisSession = 0
        fastGpsLockStopCountThisSession = 0
        fastGpsLockTimeoutCountThisSession = 0
        fastGpsLockLastSummaryAtMs = 0L
        resetElasticDistanceOverride(reason = "start_tracking", reapplyRequest = false)
        autoTrackingMotionEngine.reset(System.currentTimeMillis())
        consecutiveStationaryPoints = 0
        stationaryAnchorLocation = null
        updateRuntimeSnapshot {
            sessionCoordinator.transitionToRunning(
                previous = it,
                nowMs = System.currentTimeMillis(),
                sessionVisibleBoundaryId = sessionVisibleBoundaryId
            )
        }

        settingsRepository.setWasTrackingBeforeExit(true)
        TrackingRecoveryCoordinator.markTrackingStarted(applicationContext)
        runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.TRACKING_STARTED,
            reason = "start_tracking",
            trigger = mapRuntimeTrigger(trigger)
        )
        startRecoveryHeartbeat()
        ensureGpsProviderReceiverRegistered()
        startRetryJob(runGeneration)
        startBacklogUploader(sessionBoundaryForBacklogId, runGeneration)
        startPreflightMonitor(runGeneration)
        syncRuntimeStateStore()

        try {
            startLocationUpdates()
            maybeStartFastGpsLockWindow(measuredAccuracyMeters = null)
            startupReadyForEvents = true
            serviceScope.launch(Dispatchers.IO) {
                pushQueuedLocations(scope = QueueUploadScope.ALL, updateFailureCounters = false)
            }
            updateNotificationFromDb(broadcastStats = true)
            Log.i(TAG, "Tracking session started boundary=$sessionVisibleBoundaryId")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location updates security failure", e)
            failActiveTrackingAndStop(getString(R.string.unable_to_start_location_updates))
        } finally {
            TrackPointBus.resumeLocalDelivery()
        }
    }

    private fun stopTracking(reason: String, failureReason: String? = null) {
        Log.d(TAG, "Stopping tracking reason=$reason wasRunning=$isTracking")
        transitionControlState(TrackingControlEvent.StopRequested, failureReason = failureReason)
        transitionToStoppedState(failureReason = failureReason)
        settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = reason)
        transitionControlState(TrackingControlEvent.StopCompleted)
        cleanupServiceResources(reason = reason)
        TrackPointBus.resumeLocalDelivery()
        stopServiceInstance(reason = reason)
    }

    private fun transitionToStoppedState(failureReason: String?) {
        trackingGeneration++
        isTracking = false
        setStartupInProgress(false)
        startupReadyForEvents = false
        transitionGpsState(GpsRuntimeEvent.TRACKING_STOPPED, "transition_to_stopped_state")
        lastFilteredLocation = null
        latestObservedRawLocation = null
        lowAccuracyFallbackCandidate = null
        stopAutoModeTick()
        stopFastGpsLockWindow(reason = "tracking_stopped")
        resetElasticDistanceOverride(reason = "tracking_stopped", reapplyRequest = false)
        updateRuntimeSnapshot {
            sessionCoordinator.transitionToStopped(
                previous = it,
                failureReason = failureReason
            )
        }
        syncRuntimeStateStore(
            lifecycleStateOverride = TrackingLifecycleState.STOPPED,
            failureReasonOverride = failureReason,
        )
    }

    private fun cleanupServiceResources(reason: String) {
        Log.d(TAG, "Cleaning service resources reason=$reason")
        stopRecoveryHeartbeat()
        stopRetryJob()
        stopPreflightMonitor()
        stopBacklogUploader()
        stopAutoModeTick()
        stopFastGpsLockWindow(reason = "cleanup")
        unregisterGpsProviderReceiverIfNeeded()
        cancelLowAccuracyFallbackTimer(clearCandidate = true)
        significantMotionBridge?.cancel()
        watchdogJob?.cancel()
        watchdogJob = null
        stopLocationUpdates()
        TrackPointBus.resumeLocalDelivery()
        if (startupForegroundPromoted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            startupForegroundPromoted = false
        }
    }

    private fun stopServiceInstance(reason: String) {
        Log.d(TAG, "Stopping service instance reason=$reason")
        stopSelf()
    }

    private fun startLocationUpdates() {
        locationSessionCoordinator.stopSession()
        val applied = applyCurrentLocationRequest(reason = "start_or_resume")
        if (!applied) throw SecurityException("Unable to apply location request")
    }

    private fun stopLocationUpdates() {
        locationSessionCoordinator.stopSession()
    }

    private suspend fun processLocationUpdateSerialized(
        location: Location,
        bypassFilters: Boolean = false,
        propsJson: String? = null,
        allowWhenGpsPaused: Boolean = false,
        skipAdaptiveTrackingEffects: Boolean = false
    ) {
        locationUpdateMutex.withLock {
            processLocationUpdate(
                location = location,
                bypassFilters = bypassFilters,
                propsJson = propsJson,
                allowWhenGpsPaused = allowWhenGpsPaused,
                skipAdaptiveTrackingEffects = skipAdaptiveTrackingEffects
            )
        }
    }

    private suspend fun processLocationUpdate(
        location: Location,
        bypassFilters: Boolean = false,
        propsJson: String? = null,
        allowWhenGpsPaused: Boolean = false,
        skipAdaptiveTrackingEffects: Boolean = false
    ) {
        val runGeneration = trackingGeneration
        if (
            !TrackingRuntimeOrchestrator.shouldProcessLocationUpdate(
                RuntimeLocationGateInput(
                    isTracking = isTracking,
                    gpsState = gpsRuntimeState,
                    allowWhenGpsPaused = allowWhenGpsPaused
                )
            )
        ) {
            return
        }
        val settings = settingsRepository.getSettings()
        val previousAcceptedLocation = lastFilteredLocation?.let { Location(it) }
        val nowMs = System.currentTimeMillis()
        val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (isFastGpsLockWindowActive) {
            recordFastGpsLockSample(
                location = location,
                nowMs = nowMs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
            )
        }
        val observedSpeedMps = resolveObservedSpeedMps(location, lastSpeedReferenceLocation)
        if (!isTracking || runGeneration != trackingGeneration) return
        updateRuntimeSnapshot {
            it.copy(lastAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null)
        }
        syncRuntimeStateStore()
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (selectedTrackerId.isEmpty()) return
        val motionMode = if (settings.autoTrackingMode) {
            autoTrackingMotionEngine.snapshot().mode
        } else {
            TrackingMotionMode.fromProfileIndex(settings.trackingProfile.index)
        }
        val pointPropsJson = propsJson
        val result = locationIngestCoordinator.ingest(
            trackId = selectedTrackerId,
            location = location,
            settings = settings,
            motionMode = motionMode,
            effectiveAccuracyFilterMeters = resolveCurrentAccuracyFilter(),
            previousAcceptedLocation = previousAcceptedLocation,
            sessionVisibleBoundaryId = sessionVisibleBoundaryId,
            bypassFilters = bypassFilters,
            propsJson = pointPropsJson,
            totalDistanceMeters = runtimeSnapshot.sessionTotalDistanceMeters,
            queuedTrackerId = selectedTrackerId,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            sessionStartTimeMs = runtimeSnapshot.sessionStartTimeMs,
            isMockLocation = LocationCompat.isMock(location)
        )
        val nextSessionDistance = result.nextSessionDistanceMeters
        updateRuntimeSnapshot {
            it.copy(
                lastAccuracyMeters = result.lastAccuracyMeters,
                sessionTotalDistanceMeters = if (result.accepted) nextSessionDistance else it.sessionTotalDistanceMeters
            )
        }
        result.policyMetrics?.let { metrics ->
            runtimeTelemetry.decision(
                name = "location_filter",
                details = "raw=${metrics.rawDistanceMeters} effective=${metrics.effectiveDistanceMeters} " +
                    "dt=${metrics.elapsedSeconds} impliedSpeed=${metrics.impliedSpeedMps} " +
                    "accuracy=${metrics.accuracyMeters ?: -1f} rollingAverage=${metrics.rollingAverageStepMeters} " +
                    "capCandidate=${metrics.capCandidateMeters} decision=${metrics.decision} " +
                    "reason=${metrics.reason ?: result.rejectReason ?: result.adjustmentReason ?: "none"}"
            )
        }
        if (!result.accepted && result.policyMetrics == null) {
            runtimeTelemetry.decision(
                name = "location_filter",
                details = "accepted=false reason=${result.rejectReason ?: result.adjustmentReason ?: "none"} " +
                    "accuracy=${result.lastAccuracyMeters ?: -1f}"
            )
        }
        withContext(Dispatchers.Main) { syncRuntimeStateStore() }
        if (!result.accepted) {
            val rejectedForLock = result.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
                result.rejectReason == TrackPointRejectReason.STALE
            if (rejectedForLock) {
                maybeStartFastGpsLockWindow(
                    measuredAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    rejectReason = result.rejectReason
                )
                if (settings.lowAccuracyFallbackEnabled) {
                    transitionGpsState(GpsRuntimeEvent.FIX_REJECTED, "rejected_for_lock:${result.rejectReason}")
                    lowAccuracyFallbackRejectedFixCountThisSession++
                    maybeLogFallbackRejectSummary(nowMs)
                    lowAccuracyFallbackCandidate = Location(location)
                    val shouldStartTimer = lowAccuracyFallbackCoordinator.onRejectedFixForLock(
                        fallbackEligible = true,
                        candidateLatitude = location.latitude,
                        candidateLongitude = location.longitude,
                        candidateTimestampMs = location.time
                    )
                    if (shouldStartTimer) {
                        transitionGpsState(GpsRuntimeEvent.FALLBACK_TIMER_ARMED, "fallback_timer_armed")
                        lowAccuracyFallbackArmCountThisSession++
                        lowAccuracyFallbackTimerArmedAtMs = nowMs
                        ensureLowAccuracyFallbackTimerRunning()
                    }
                }
            }
            if (settings.autoTrackingMode) {
                processAutoTrackingOutput(
                    output = autoTrackingMotionEngine.onRejectedFix(
                        speedMpsHint = observedSpeedMps,
                        eventTimeMs = nowMs
                    ),
                    reason = "rejected_fix"
                )
            }
            broadcastSessionStats()
            lastSpeedReferenceLocation = Location(location)
            return
        }
        if (!isTracking || runGeneration != trackingGeneration) return

        if (!isWaitingForProviderState()) {
            transitionGpsState(GpsRuntimeEvent.FIX_ACCEPTED, "fix_accepted")
        }
        lastFilteredLocation = result.lastFilteredLocation
        val acceptedLocation = result.lastFilteredLocation ?: location
        val finalPropsJson = pointPropsJson ?: buildLocalPointPropsJson(
            location = acceptedLocation,
            distanceMeters = nextSessionDistance
        )
        updateRuntimeSnapshot {
            it.copy(
                queuedPointsVisible = result.queuedPointsVisible,
                lastTrackedLatitude = result.lastTrackedLatitude,
                lastTrackedLongitude = result.lastTrackedLongitude,
                lastTrackedTimestampMs = result.lastTrackedTimestampMs,
                lastTrackedPropsJson = finalPropsJson
            )
        }
        val acceptedQuality = result.trackPointQuality ?: resolveTrackPointQuality(acceptedLocation, finalPropsJson)
        if (
            isFastGpsLockWindowActive &&
            hasRecoveredFastGpsLock(
                quality = acceptedQuality,
                measuredAccuracyMeters = result.lastAccuracyMeters,
                accuracyFilterMeters = resolveCurrentAccuracyFilter()
            )
        ) {
            stopFastGpsLockWindow(reason = "accepted_fix_lock_recovered")
            lowAccuracyFallbackCoordinator.onAcceptedFix()
            cancelLowAccuracyFallbackTimer(clearCandidate = true)
        }
        if (!skipAdaptiveTrackingEffects) {
            val stationaryRadius = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS
            val autoMotionSnapshot = autoTrackingMotionEngine.snapshot()
            val activeMotionHint = settings.autoTrackingMode &&
                ((observedSpeedMps ?: 0f) > 0.75f || autoMotionSnapshot.smoothedSpeedMps > 0.75f)
            val stationaryResult = TrackingLocationPolicy.stationaryUpdate(
                lastLocation = stationaryAnchorLocation,
                location = result.lastFilteredLocation ?: location,
                stationaryRadiusMeters = stationaryRadius,
                currentConsecutive = consecutiveStationaryPoints,
                significantMotionOnly = settings.significantDataOnly,
                activeMotionHint = activeMotionHint,
            )
            consecutiveStationaryPoints = stationaryResult.first
            stationaryAnchorLocation = when (consecutiveStationaryPoints) {
                0 -> null
                1 -> Location(result.lastFilteredLocation ?: location)
                else -> stationaryAnchorLocation
            }
            if (stationaryResult.second) {
                pauseGps()
            }
            if (settings.autoTrackingMode) {
                processAutoTrackingOutput(
                    output = autoTrackingMotionEngine.onAcceptedFix(
                        speedMps = observedSpeedMps ?: 0f,
                        eventTimeMs = nowMs
                    ),
                    reason = "accepted_fix"
                )
            }
            maybeApplyElasticDistanceFilter(
                observedSpeedMps = observedSpeedMps,
                measuredAccuracyMeters = (result.lastFilteredLocation ?: location)
                    .takeIf { it.hasAccuracy() }
                    ?.accuracy
            )
        }
        publishTrackPoint(
            trackId = selectedTrackerId,
            location = acceptedLocation,
            propsJson = finalPropsJson,
            quality = acceptedQuality
        )
        lastSpeedReferenceLocation = Location(location)
        withContext(Dispatchers.Main) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = false)
        }
        if (result.pointPersisted) {
            serviceScope.launch(Dispatchers.IO) {
                val outcome = pushQueuedLocations(
                    scope = QueueUploadScope.LIVE_ONLY,
                    updateFailureCounters = false
                )
                if (outcome == SyncFailureClass.NONE) {
                    consecutivePushFailures = 0
                }
            }
        }
    }

    private fun handleLocationUpdateCommand(intent: Intent?): Boolean {
        if (!isTracking) return false
        val locations = extractLocationUpdateIntentLocations(intent)
        locations.forEach { location ->
            val snapshot = Location(location)
            locationListener.onLocationChanged(snapshot)
        }
        return true
    }

    private fun handleManualSendPointCommand(): Boolean {
        if (!isTracking) {
            serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    this@TrackingService,
                    getString(R.string.manual_send_point_requires_active_tracking),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (!hasValidSelectedTrackerId(selectedTrackerId)) {
            Log.w(TAG, "Manual send ignored: invalid selected tracker id")
            return false
        }
        val candidate = getManualSendCandidateLocation() ?: run {
            Log.w(TAG, "Manual send ignored: no candidate location available")
            return false
        }
        val manualLocation = buildManualSendLocation(candidate)
        serviceScope.launch(Dispatchers.IO) {
            processLocationUpdateSerialized(
                location = manualLocation,
                bypassFilters = true,
                allowWhenGpsPaused = true,
                skipAdaptiveTrackingEffects = true
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@TrackingService,
                    getString(R.string.manual_send_point_sent),
                    Toast.LENGTH_SHORT
                ).show()
                triggerLightHaptic()
            }
            updateNotificationFromDb(broadcastStats = true)
        }
        return true
    }

    private fun failStartup(message: String, path: StartupCommandPath, trigger: String, reason: String) {
        Log.w(TAG, "Tracking start failed: $reason path=$path trigger=$trigger")
        TrackPointBus.resumeLocalDelivery()
        transitionControlState(TrackingControlEvent.StartFailed, failureReason = message)
        transitionToStoppedState(failureReason = message)
        settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = "startup_failed")
        runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.STARTUP_FAILED,
            reason = reason,
            trigger = mapRuntimeTrigger(trigger)
        )
        serviceScope.launch(Dispatchers.Main) {
            sendBroadcast(
                Intent(ACTION_TRACKING_ERROR).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_TRACKING_ERROR_MESSAGE, message)
                }
            )
            Toast.makeText(this@TrackingService, message, Toast.LENGTH_LONG).show()
        }
        stopSelfSafelyAfterStartup(reason = "startup_failed")
    }

    private fun failActiveTrackingAndStop(message: String) {
        transitionControlState(TrackingControlEvent.FatalFailure, failureReason = message)
        serviceScope.launch(Dispatchers.Main) {
            sendBroadcast(
                Intent(ACTION_TRACKING_ERROR).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_TRACKING_ERROR_MESSAGE, message)
                }
            )
            Toast.makeText(this@TrackingService, message, Toast.LENGTH_LONG).show()
        }
        stopTracking(reason = "fatal_failure", failureReason = message)
    }

    private fun promoteToForegroundForStartup(
        trigger: String,
        action: String?,
        path: StartupCommandPath
    ): Boolean {
        if (startupForegroundPromoted) return true
        return try {
            startForeground(
                NOTIFICATION_ID,
                notificationPresenter.buildTrackingNotification(runtimeSnapshot),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            startupForegroundPromoted = true
            Log.i(TAG, "Foreground promotion succeeded trigger=$trigger")
            logNotificationSurfaceDiagnostics(
                trigger = trigger,
                action = action,
                path = path,
                stage = "foreground_promoted"
            )
            true
        } catch (e: Exception) {
            if (e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Foreground start not allowed for trigger=$trigger", e)
            } else {
                Log.e(TAG, "Foreground promotion failed for trigger=$trigger", e)
            }
            TrackingRecoveryCoordinator.markIntentionalStop(
                applicationContext,
                reason = "fgs_start_failed_$trigger"
            )
            logNotificationSurfaceDiagnostics(
                trigger = trigger,
                action = action,
                path = path,
                stage = "foreground_promotion_failed"
            )
            false
        }
    }

    private fun stopSelfSafelyAfterStartup(reason: String) {
        cleanupServiceResources(reason = reason)
        stopServiceInstance(reason = reason)
    }

    private fun setStartupInProgress(value: Boolean) {
        startupInProgress = value
    }

    private fun isTrackingActiveOrStarting(): Boolean {
        return isTracking || startupInProgress
    }

    private fun updateNotificationFromDb(broadcastStats: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            val count = if (isTracking) {
                database.locationDao().getCurrentSessionCountForTracker(
                    trackerId = SelectedTrackerPrefs.selectedTrackerId(this@TrackingService),
                    sessionBoundaryId = sessionVisibleBoundaryId
                )
            } else {
                0
            }
            updateRuntimeSnapshot { it.copy(queuedPointsVisible = count) }
            withContext(Dispatchers.Main) {
                syncRuntimeStateStore()
                if (startupForegroundPromoted) {
                    notificationPresenter.updateForegroundNotification(runtimeSnapshot)
                }
            }
            if (broadcastStats) {
                broadcastSessionStats()
            }
        }
    }

    private fun broadcastSessionStats() {
        if (isTracking && !startupReadyForEvents) return
        sendBroadcast(Intent(SESSION_STATS_UPDATE).apply { setPackage(packageName) })
    }

    private fun updateRuntimeSnapshot(
        transform: (TrackingRuntimeSnapshot) -> TrackingRuntimeSnapshot
    ): TrackingRuntimeSnapshot {
        return synchronized(runtimeSnapshotLock) {
            runtimeSnapshot = transform(runtimeSnapshot)
            runtimeSnapshot
        }
    }

    private fun syncRuntimeStateStore(
        lifecycleStateOverride: TrackingLifecycleState? = null,
        failureReasonOverride: String? = null,
    ) {
        val gpsOk = isGpsProviderEnabled()
        val settings = settingsRepository.getSettings()
        val effectiveAccuracyThreshold = resolveCurrentAccuracyFilter()
        validateRuntimeInvariant(gpsProviderEnabled = gpsOk)
        val effectiveRunning = isTrackingActiveOrStarting()
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        val lastAccuracyMeters = synchronized(runtimeSnapshotLock) { runtimeSnapshot.lastAccuracyMeters }
        val uiStatus = TrackingUiStatusResolver.resolveForGpsState(
            isRunning = effectiveRunning,
            gpsProviderEnabled = gpsOk,
            gpsState = gpsRuntimeState,
            lastAccuracyMeters = lastAccuracyMeters,
            effectiveAccuracyThresholdMeters = effectiveAccuracyThreshold
        )
        val activeMotionMode = if (settings.autoTrackingMode) {
            autoTrackingMotionEngine.snapshot().mode
        } else {
            TrackingMotionMode.fromProfileIndex(settings.trackingProfile.index)
        }
        val selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(this)
        val next = synchronized(runtimeSnapshotLock) {
            val recordingRuntime = RecordingRuntimeReducer.fromInputs(
                previous = runtimeSnapshot.recordingRuntime,
                sessionActive = isTracking,
                startupActive = startupInProgress,
                gpsState = gpsRuntimeState,
                gpsProviderEnabled = gpsOk,
                selectedTrackerId = selectedTrackerId,
            )
            RuntimeSnapshotProjector.project(
                previous = runtimeSnapshot,
                input = RuntimeSnapshotProjectionInput(
                    isRunning = effectiveRunning,
                    recordingRuntime = recordingRuntime,
                    lifecycleState = lifecycleStateOverride ?: controlState.lifecycleState,
                    failureReason = failureReasonOverride ?: controlState.failureReason,
                    selectedTrackerId = selectedTrackerId,
                    selectedTrackerName = selectedTrackerName,
                    gpsProviderEnabled = gpsOk,
                    autoTrackingEnabled = settings.autoTrackingMode,
                    activeMotionMode = activeMotionMode,
                    uiStatus = uiStatus,
                    gpsPaused = recordingRuntime.pausedForMotion,
                    effectiveAccuracyThresholdMeters = effectiveAccuracyThreshold,
                    sessionVisibleBoundaryId = sessionVisibleBoundaryId
                )
            ).also { runtimeSnapshot = it }
        }
        TrackingRuntimeStateStore.update { next }
        if (startupForegroundPromoted && startupInProgress) {
            serviceScope.launch(Dispatchers.Main) {
                notificationPresenter.updateForegroundNotification(runtimeSnapshot)
            }
        }
    }

    private fun transitionControlState(event: TrackingControlEvent, failureReason: String? = null) {
        controlState = TrackingControlPlane.transition(
            current = controlState,
            event = event,
            failureReason = failureReason
        )
        syncRuntimeStateStore()
    }

    private fun validateRuntimeInvariant(gpsProviderEnabled: Boolean) {
        when {
            !isTracking && gpsRuntimeState != GpsRuntimeState.INACTIVE -> {
                runtimeTelemetry.event(
                    "runtime_invariant_violation",
                    "state=$gpsRuntimeState while isTracking=false"
                )
            }
            isTracking && gpsRuntimeState == GpsRuntimeState.INACTIVE -> {
                runtimeTelemetry.event(
                    "runtime_invariant_violation",
                    "state=INACTIVE while isTracking=true"
                )
            }
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER && gpsProviderEnabled -> {
                runtimeTelemetry.event(
                    "runtime_invariant_watch",
                    "state=WAITING_FOR_PROVIDER with providerEnabled=true"
                )
            }
        }
    }

    private fun isGpsProviderEnabled(): Boolean {
        return locationSessionCoordinator.isGpsProviderEnabled()
    }

    private fun isLocationServicesEnabled(): Boolean {
        return locationSessionCoordinator.isLocationServicesEnabled()
    }

    private fun startRecoveryHeartbeat() {
        recoveryHeartbeatJob?.cancel()
        recoveryHeartbeatJob = serviceScope.launch {
            while (isTracking) {
                TrackingRecoveryCoordinator.markHeartbeat(applicationContext)
                runtimeEventPublisher.publish(
                    type = RuntimeServiceEventType.HEARTBEAT,
                    reason = "recovery_heartbeat"
                )
                delay(1_000L)
            }
        }
    }

    private fun stopRecoveryHeartbeat() {
        recoveryHeartbeatJob?.cancel()
        recoveryHeartbeatJob = null
    }

    private fun startRetryJob(runGeneration: Int) {
        retryJob?.cancel()
        retryJob = serviceScope.launch(Dispatchers.IO) {
            while (isTracking && runGeneration == trackingGeneration) {
                val baseDelay = TrackingSyncPolicy.nextRetryDelayMs(
                    consecutiveFailures = consecutivePushFailures,
                    failureClass = lastSyncFailureClass
                )
                val jitter = Random.nextLong(-RETRY_JITTER_MS, RETRY_JITTER_MS + 1)
                delay((baseDelay + jitter).coerceAtLeast(5_000L))
                if (!isTracking || runGeneration != trackingGeneration) break
                val trackerId = SelectedTrackerPrefs.selectedTrackerId(this@TrackingService)
                val count = database.locationDao().getCurrentSessionCountForTracker(
                    trackerId = trackerId,
                    sessionBoundaryId = sessionBoundaryForBacklogId
                )
                if (count > 0) {
                    pushQueuedLocations(scope = QueueUploadScope.LIVE_ONLY)
                }
            }
        }
    }

    private fun stopRetryJob() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun startBacklogUploader(sessionBoundaryId: Long, runGeneration: Int) {
        backlogUploaderJob?.cancel()
        backlogUploaderJob = serviceScope.launch(Dispatchers.IO) {
            while (isTracking && runGeneration == trackingGeneration) {
                val trackerId = SelectedTrackerPrefs.selectedTrackerId(this@TrackingService)
                val backlogCount = database.locationDao().getBacklogCountForTracker(
                    trackerId = trackerId,
                    sessionBoundaryId = sessionBoundaryId
                )
                if (backlogCount > 0) {
                    pushQueuedLocations(scope = QueueUploadScope.BACKLOG_ONLY)
                    delay(5_000L)
                } else {
                    delay(30_000L)
                }
            }
        }
    }

    private fun stopBacklogUploader() {
        backlogUploaderJob?.cancel()
        backlogUploaderJob = null
    }

    private fun ensureGpsProviderReceiverRegistered() {
        if (gpsProviderReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            gpsProviderReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        gpsProviderReceiverRegistered = true
    }

    private fun unregisterGpsProviderReceiverIfNeeded() {
        if (!gpsProviderReceiverRegistered) return
        runCatching { unregisterReceiver(gpsProviderReceiver) }
        gpsProviderReceiverRegistered = false
    }

    private fun startPreflightMonitor(runGeneration: Int) {
        preflightJob?.cancel()
        preflightJob = serviceScope.launch(Dispatchers.IO) {
            while (isTracking && runGeneration == trackingGeneration) {
                delay(20_000L)
                if (!isTracking || runGeneration != trackingGeneration) break
                if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(this@TrackingService)) {
                    withContext(Dispatchers.Main) {
                        failActiveTrackingAndStop(getString(R.string.location_permissions_required))
                    }
                    return@launch
                }
                if (!isLocationServicesEnabled()) {
                    withContext(Dispatchers.Main) {
                        enterWaitingForGpsProvider(reason = "preflight_monitor")
                    }
                    continue
                }
                if (
                    gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
                    gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                ) {
                    withContext(Dispatchers.Main) {
                        resumeFromGpsProviderWait(reason = "preflight_monitor")
                    }
                }
            }
        }
    }

    private fun stopPreflightMonitor() {
        preflightJob?.cancel()
        preflightJob = null
    }

    private fun enterWaitingForGpsProvider(reason: String) {
        if (
            !isTracking ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        transitionGpsState(GpsRuntimeEvent.PROVIDER_DISABLED, reason)
        resetElasticDistanceOverride(reason = "gps_provider_disabled", reapplyRequest = false)
        stopFastGpsLockWindow(reason = "gps_provider_disabled")
        cancelLowAccuracyFallbackTimer(clearCandidate = true)
        stopLocationUpdates()
        Log.w(TAG, "GPS provider disabled while tracking reason=$reason")
        runtimeTelemetry.event("gps_provider_disabled", "reason=$reason")
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    private fun resumeFromGpsProviderWait(reason: String) {
        if (
            !isTracking ||
            (gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER &&
                gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        transitionGpsState(GpsRuntimeEvent.PROVIDER_ENABLED, reason)
        if (gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION) {
            Log.i(TAG, "GPS provider re-enabled while paused reason=$reason")
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (isWaitingForProviderState()) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (!applyCurrentLocationRequest("gps_provider_reenabled_$reason")) {
            failActiveTrackingAndStop(resolveLocationRequestFailureMessage())
            return
        }
        Log.i(TAG, "GPS provider re-enabled, resumed updates reason=$reason")
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    private fun ensureLowAccuracyFallbackTimerRunning() {
        if (lowAccuracyFallbackJob?.isActive == true) return
        val runGeneration = trackingGeneration
        lowAccuracyFallbackJob = serviceScope.launch(Dispatchers.IO) {
            lowAccuracyFallbackTimerArmedAtMs = System.currentTimeMillis()
            while (isTracking && runGeneration == trackingGeneration) {
                val timeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(
                    settingsRepository.getSettings().lowAccuracyFallbackTimeoutSec
                )
                delay(timeoutSec * 1000L)
                if (!isTracking || runGeneration != trackingGeneration) break
                if (
                    gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                    gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                ) {
                    break
                }
                val candidate = lowAccuracyFallbackCandidate ?: break
                val settings = settingsRepository.getSettings()
                if (!lowAccuracyFallbackCoordinator.shouldEmitFallback(
                        fallbackEligible = settings.lowAccuracyFallbackEnabled,
                        hasCandidate = true
                    )
                ) {
                    break
                }
                val fallbackLocation = Location(candidate).apply {
                    provider = "low_accuracy_fallback:${candidate.provider ?: "gps"}"
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    extras = (extras ?: Bundle()).apply {
                        putBoolean(EXTRAS_KEY_LOW_ACCURACY_FALLBACK, true)
                        putString(EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER, candidate.provider ?: "gps")
                    }
                }
                if (
                    !shouldEmitFallbackForTransition(
                        previousAcceptedLocation = lastFilteredLocation,
                        fallbackCandidateLocation = fallbackLocation,
                        nowMs = fallbackLocation.time
                    )
                ) {
                    runtimeTelemetry.event("fallback_rejected", "reason=implausible_transition")
                    continue
                }
                lowAccuracyFallbackCoordinator.onFallbackEmitted(
                    candidateLatitude = candidate.latitude,
                    candidateLongitude = candidate.longitude,
                    candidateTimestampMs = candidate.time
                )
                lowAccuracyFallbackEmitCountThisSession++
                transitionGpsState(GpsRuntimeEvent.FALLBACK_EMITTED, "fallback_emitted")
                lowAccuracyFallbackTimerArmedAtMs = System.currentTimeMillis()
                if (!shouldPersistFallbackPoint(lastFilteredLocation, fallbackLocation)) {
                    runtimeTelemetry.event("fallback_skipped_persist", "reason=accuracy_uncertainty")
                    emitFallbackPointWithoutPersist(fallbackLocation)
                    continue
                }
                processLocationUpdateSerialized(
                    location = fallbackLocation,
                    bypassFilters = true
                )
            }
            lowAccuracyFallbackTimerArmedAtMs = 0L
            lowAccuracyFallbackJob = null
            if (isTracking && runGeneration == trackingGeneration) {
                lowAccuracyFallbackCoordinator.onFallbackTimerStopped()
            }
        }
    }

    private fun cancelLowAccuracyFallbackTimer(clearCandidate: Boolean) {
        if (lowAccuracyFallbackJob != null) {
            lowAccuracyFallbackCancelCountThisSession++
        }
        lowAccuracyFallbackJob?.cancel()
        lowAccuracyFallbackJob = null
        lowAccuracyFallbackTimerArmedAtMs = 0L
        lowAccuracyFallbackCoordinator.onTrackingStopped()
        if (clearCandidate) {
            lowAccuracyFallbackCandidate = null
        }
    }

    private suspend fun pushQueuedLocations(
        scope: QueueUploadScope,
        updateFailureCounters: Boolean = true
    ): SyncFailureClass {
        if (!isTracking) return SyncFailureClass.NONE
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        trimQueuedLocationsRetention(trackerId)
        if (!NetworkStatusMonitor.hasUsableNetwork(this)) {
            if (scope != QueueUploadScope.BACKLOG_ONLY) {
                lastSyncFailureClass = SyncFailureClass.TRANSIENT
            }
            if (updateFailureCounters && scope != QueueUploadScope.BACKLOG_ONLY) {
                consecutivePushFailures++
            }
            withContext(Dispatchers.Main) {
                updateNotificationFromDb(broadcastStats = true)
            }
            return SyncFailureClass.TRANSIENT
        }
        if (!hasValidSelectedTrackerId(trackerId)) {
            runtimeTelemetry.event("queue_skip_invalid_tracker", "scope=$scope")
            val trackerError = if (trackerId.isBlank()) {
                getString(R.string.no_tracker_selected_go_to_settings)
            } else {
                getString(R.string.tracker_validation_failed_go_to_settings)
            }
            if (scope != QueueUploadScope.BACKLOG_ONLY) {
                lastSyncFailureClass = SyncFailureClass.PERMANENT
            }
            if (updateFailureCounters && scope != QueueUploadScope.BACKLOG_ONLY) {
                consecutivePushFailures++
            }
            withContext(Dispatchers.Main) {
                sendBroadcast(
                    Intent(ACTION_TRACKING_ERROR).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_TRACKING_ERROR_MESSAGE, trackerError)
                    }
                )
                updateNotificationFromDb(broadcastStats = true)
            }
            return SyncFailureClass.PERMANENT
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(this)
        val settings = settingsRepository.getSettings()
        val outcome = queueUploadEngine.push(
            scope = scope,
            trackerId = trackerId,
            serverUrl = serverUrl,
            config = QueueUploadConfig(
                sessionBoundaryId = sessionBoundaryForBacklogId,
                sessionVisibleBoundaryId = sessionVisibleBoundaryId,
                maxBatchesPerPush = MAX_BATCHES_PER_PUSH,
                useExtendedParams = settings.sendExtendedData,
                sessionStartTimeMs = runtimeSnapshot.sessionStartTimeMs,
                batteryLevel = readBatteryLevel(),
                isCharging = isCharging(),
                deviceIdentifier = getDeviceIdentifier()
            ),
            onBatchUploaded = { visibleSentCount ->
                val sentDelta = visibleSentCount.coerceAtLeast(0)
                if (sentDelta > 0) {
                    updateRuntimeSnapshot {
                        it.copy(
                            pointsSentThisSession = it.pointsSentThisSession + sentDelta,
                            lastPointSentAtMs = System.currentTimeMillis()
                        )
                    }
                }
            }
        )
        if (scope != QueueUploadScope.BACKLOG_ONLY) {
            lastSyncFailureClass = outcome
            if (updateFailureCounters && outcome != SyncFailureClass.SKIPPED) {
                if (outcome == SyncFailureClass.NONE) {
                    consecutivePushFailures = 0
                } else {
                    consecutivePushFailures++
                }
            }
        }
        trimQueuedLocationsRetention(trackerId)
        withContext(Dispatchers.Main) {
            updateNotificationFromDb(broadcastStats = true)
        }
        return outcome
    }

    private fun trimQueuedLocationsRetention(trackerId: String) {
        if (trackerId.isBlank()) return
        val cutoff = System.currentTimeMillis() - MAX_QUEUE_AGE_MS
        val deletedByAge = database.locationDao().deleteOlderThanForTracker(trackerId, cutoff)
        val count = database.locationDao().getCountForTracker(trackerId)
        val deletedBySize = if (count > MAX_QUEUE_SIZE) {
            database.locationDao().deleteOldestCountForTracker(trackerId, count - MAX_QUEUE_SIZE)
        } else {
            0
        }
        if (deletedByAge > 0 || deletedBySize > 0) {
            runtimeTelemetry.event(
                name = "queue_retention_trim",
                details = "trackerId=$trackerId deletedByAge=$deletedByAge deletedBySize=$deletedBySize maxSize=$MAX_QUEUE_SIZE maxAgeMs=$MAX_QUEUE_AGE_MS"
            )
        }
    }

    private fun getAuthenticatedHttpClient(): OkHttpClient {
        if (httpClient == null) {
            httpClient = RetrofitClient.getAuthenticatedOkHttpClient(applicationContext).newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
        return httpClient!!
    }

    private fun readBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = batteryIntent.getIntExtra("level", -1)
        val scale = batteryIntent.getIntExtra("scale", -1)
        if (level <= 0 || scale <= 0) return 0
        return ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
    }

    private fun isCharging(): Boolean {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = batteryIntent.getIntExtra("status", -1)
        return status == 2 || status == 5
    }

    private fun resolveCurrentProfileParams(): Triple<Long, Float, Float> {
        val settings = settingsRepository.getSettings()
        if (settings.autoTrackingMode) {
            val mode = autoTrackingMotionEngine.snapshot().mode
            return TrackingLocationPolicy.getProfileParams(mode.profileIndex)
        }
        return Triple(
            settings.loggingIntervalSec,
            settings.distanceFilterMeters,
            settings.accuracyFilterMeters
        )
    }

    private fun resolveCurrentIntervalAndDistance(): Pair<Long, Float> {
        val (interval, distance, _) = resolveCurrentProfileParams()
        val effectiveDistance = elasticDistanceOverrideMeters ?: distance
        return interval to effectiveDistance
    }

    private fun resolveCurrentAccuracyFilter(): Float = resolveCurrentProfileParams().third

    private fun applyCurrentLocationRequest(reason: String): Boolean {
        if (!isTracking) return false
        if (
            gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return false
        }
        if (!TrackingPermissionGate.hasLocationPermission(this)) return false
        val (intervalSec, distanceFilter) = resolveCurrentIntervalAndDistance()
        val request = if (isFastGpsLockWindowActive) {
            TrackingLocationRequestPolicy.buildFastLockRequest()
        } else {
            TrackingLocationRequestPolicy.buildNormalRequest(
                TrackingLocationRequestInput(
                    intervalSec = intervalSec,
                    distanceFilterMeters = distanceFilter
                )
            )
        }
        return try {
            locationSessionCoordinator.stopSession()
            val started = locationSessionCoordinator.startSession(request = request)
            if (!started) return false
            runtimeTelemetry.decision(
                name = "location_request_applied",
                details = "reason=$reason intervalSec=$intervalSec distance=$distanceFilter fastLock=$isFastGpsLockWindowActive"
            )
            true
        } catch (security: SecurityException) {
            Log.e(TAG, "Location request failed reason=$reason", security)
            false
        }
    }

    private fun reapplyLocationRequestIfActive(reason: String) {
        if (
            !isTracking ||
            gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        val applied = applyCurrentLocationRequest(reason)
        if (!applied) {
            failActiveTrackingAndStop(resolveLocationRequestFailureMessage())
        }
    }

    private fun resolveLocationRequestFailureMessage(): String {
        return if (TrackingPermissionGate.hasRequiredPermissionsForTracking(this)) {
            getString(R.string.unable_to_start_location_updates)
        } else {
            getString(R.string.location_permissions_required)
        }
    }

    private fun startAutoModeTickIfNeeded() {
        val settings = settingsRepository.getSettings()
        if (!isTracking || !settings.autoTrackingMode) return
        if (autoModeTickJob?.isActive == true) return
        autoModeTickJob = serviceScope.launch {
            while (isTracking && settingsRepository.getSettings().autoTrackingMode) {
                delay(5_000L)
                processAutoTrackingOutput(
                    output = autoTrackingMotionEngine.onTick(System.currentTimeMillis()),
                    reason = "periodic_decay_tick"
                )
            }
        }
    }

    private fun stopAutoModeTick() {
        autoModeTickJob?.cancel()
        autoModeTickJob = null
    }

    private fun processAutoTrackingOutput(output: AutoTrackingEngineOutput, reason: String) {
        if (output.modeChanged) {
            resetElasticDistanceOverride(reason = "auto_mode_changed_$reason", reapplyRequest = false)
            reapplyLocationRequestIfActive("auto_mode_$reason")
            runtimeTelemetry.event(
                name = "auto_mode_changed",
                details = "mode=${output.state.mode} reason=$reason"
            )
        }
        syncRuntimeStateStore()
    }

    private fun maybeApplyElasticDistanceFilter(observedSpeedMps: Float?, measuredAccuracyMeters: Float?) {
        if (!isTracking) return
        if (
            gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            return
        }
        if (isFastGpsLockWindowActive) return
        val settings = settingsRepository.getSettings()
        if (!settings.autoTrackingMode) return
        val (_, baseDistanceMeters, accuracyThresholdMeters) = resolveCurrentProfileParams()
        if (baseDistanceMeters <= 0f) return
        if (measuredAccuracyMeters == null || measuredAccuracyMeters > accuracyThresholdMeters) return
        val nextBucketRaw = computeElasticitySpeedBucket(observedSpeedMps)
        val nextBucket = computeElasticityModeBoundBucket(nextBucketRaw, autoTrackingMotionEngine.snapshot().mode)
        val nextDistance = computeElasticDistanceFilterMeters(baseDistanceMeters, nextBucket)
        if (!nextDistance.isFinite() || nextDistance < baseDistanceMeters) return
        val currentDistance = elasticDistanceOverrideMeters ?: baseDistanceMeters
        val distanceDelta = kotlin.math.abs(nextDistance - currentDistance)
        if (nextBucket == elasticitySpeedBucket && distanceDelta < ELASTICITY_REAPPLY_DISTANCE_DELTA_METERS) return
        elasticitySpeedBucket = nextBucket
        elasticDistanceOverrideMeters = if (nextBucket > 0) nextDistance else null
        reapplyLocationRequestIfActive("elasticity_update")
        runtimeTelemetry.decision(
            name = "elasticity_updated",
            details = "base=$baseDistanceMeters speed=${observedSpeedMps ?: -1f} bucket=$nextBucket distance=$nextDistance"
        )
    }

    private fun resetElasticDistanceOverride(reason: String, reapplyRequest: Boolean) {
        val changed = elasticDistanceOverrideMeters != null || elasticitySpeedBucket != 0
        elasticDistanceOverrideMeters = null
        elasticitySpeedBucket = 0
        if (changed) {
            runtimeTelemetry.event("elasticity_reset", "reason=$reason")
        }
        if (reapplyRequest) {
            reapplyLocationRequestIfActive("elasticity_reset_$reason")
        }
    }

    private fun pauseGps() {
        pauseGpsInternal(force = false)
    }

    private fun pauseGpsInternal(force: Boolean) {
        if (!isTracking) return
        if (
            !force &&
            (gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        if (significantMotionBridge?.isAvailable() != true) {
            runtimeTelemetry.event("gps_pause_skipped", "reason=significant_motion_unavailable")
            return
        }
        transitionGpsState(GpsRuntimeEvent.PAUSE_FOR_MOTION, "pause_for_motion")
        transitionControlState(TrackingControlEvent.PauseRequested)
        resetElasticDistanceOverride(reason = "gps_paused", reapplyRequest = false)
        stopFastGpsLockWindow(reason = "gps_paused")
        stopLocationUpdates()
        if (settingsRepository.getSettings().autoTrackingMode) {
            autoTrackingMotionEngine.onGpsPaused(System.currentTimeMillis())
        }
        significantMotionBridge?.request()
        sigMotionSensorStartTime = System.currentTimeMillis()
        startSensorWatchdog()
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    private fun startSensorWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (
                gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
            ) {
                delay(60_000L)
                val age = System.currentTimeMillis() - sigMotionSensorStartTime
                if (age > 5 * 60_000L) {
                    significantMotionBridge?.cancel()
                    significantMotionBridge?.request()
                    sigMotionSensorStartTime = System.currentTimeMillis()
                    runtimeTelemetry.event("sensor_watchdog_refresh", "ageMs=$age")
                }
            }
        }
    }

    private fun resumeGps() {
        if (
            !isTracking ||
            (gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
                gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED)
        ) {
            return
        }
        transitionGpsState(GpsRuntimeEvent.RESUME_FROM_MOTION, "significant_motion_resume")
        transitionControlState(TrackingControlEvent.ResumeRequested)
        resetElasticDistanceOverride(reason = "gps_resumed", reapplyRequest = false)
        stopFastGpsLockWindow(reason = "gps_resumed")
        cancelLowAccuracyFallbackTimer(clearCandidate = false)
        consecutiveStationaryPoints = 0
        stationaryAnchorLocation = null
        if (settingsRepository.getSettings().autoTrackingMode) {
            autoTrackingMotionEngine.onGpsResumed(System.currentTimeMillis())
        }
        watchdogJob?.cancel()
        watchdogJob = null
        if (gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (gpsRuntimeState != GpsRuntimeState.LOCKING) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = true)
            return
        }
        if (!applyCurrentLocationRequest("resume_gps")) {
            failActiveTrackingAndStop(resolveLocationRequestFailureMessage())
            return
        }
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    private fun maybeStartFastGpsLockWindow(
        measuredAccuracyMeters: Float?,
        rejectReason: TrackPointRejectReason? = null
    ) {
        val accuracyFilterMeters = resolveCurrentAccuracyFilter()
        if (
            !TrackingRuntimeOrchestrator.shouldAttemptFastLock(
                FastLockTriggerInput(
                    isTracking = isTracking,
                    isFastGpsLockWindowActive = isFastGpsLockWindowActive,
                    isFastGpsLockPriming = isFastGpsLockPriming,
                    gpsState = gpsRuntimeState,
                    rejectReason = rejectReason,
                    measuredAccuracyMeters = measuredAccuracyMeters,
                    accuracyFilterMeters = accuracyFilterMeters
                )
            )
        ) return
        isFastGpsLockPriming = true
        locationSessionCoordinator.getLastLocation(
            onSuccess = { last ->
                isFastGpsLockPriming = false
                if (!isTracking || isFastGpsLockWindowActive) return@getLastLocation
                if (isFreshAccurateLocation(last, accuracyFilterMeters)) {
                    transitionGpsState(GpsRuntimeEvent.FIX_ACCEPTED, "fast_lock_last_known_recovered")
                    lowAccuracyFallbackCoordinator.onAcceptedFix()
                    cancelLowAccuracyFallbackTimer(clearCandidate = true)
                    return@getLastLocation
                }
                startFastGpsLockBurst(measuredAccuracyMeters = measuredAccuracyMeters, accuracyFilterMeters = accuracyFilterMeters)
            },
            onFailure = { error ->
                Log.e(TAG, "Fast-lock last location lookup failed", error)
                isFastGpsLockPriming = false
                if (!isTracking || isFastGpsLockWindowActive) return@getLastLocation
                startFastGpsLockBurst(measuredAccuracyMeters = measuredAccuracyMeters, accuracyFilterMeters = accuracyFilterMeters)
            }
        )
    }

    private fun startFastGpsLockBurst(measuredAccuracyMeters: Float?, accuracyFilterMeters: Float) {
        if (!isTracking || isFastGpsLockWindowActive) return
        isFastGpsLockWindowActive = true
        transitionGpsState(GpsRuntimeEvent.FAST_LOCK_STARTED, "fast_gps_lock_start")
        fastGpsLockStartCountThisSession++
        resetElasticDistanceOverride(reason = "fast_gps_lock_start", reapplyRequest = false)
        resetFastGpsLockSamples()
        if (!applyCurrentLocationRequest("fast_gps_lock_start")) {
            isFastGpsLockWindowActive = false
            failActiveTrackingAndStop(getString(R.string.unable_to_start_location_updates))
            return
        }
        runtimeTelemetry.event(
            "fast_lock_start",
            "measuredAcc=${measuredAccuracyMeters ?: -1f} accuracyFilter=$accuracyFilterMeters"
        )
        fastGpsLockWindowJob?.cancel()
        val runGeneration = trackingGeneration
        fastGpsLockWindowJob = ingestScope.launch {
            delay(FAST_GPS_LOCK_WINDOW_MS)
            if (!isTracking || runGeneration != trackingGeneration || !isFastGpsLockWindowActive) return@launch
            val best = selectBestFastGpsLockSample(
                desiredAccuracyMeters = accuracyFilterMeters,
                nowMs = System.currentTimeMillis(),
                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            )
            fastGpsLockTimeoutCountThisSession++
            transitionGpsState(GpsRuntimeEvent.FAST_LOCK_TIMEOUT, "fast_gps_lock_timeout")
            if (best != null) {
                val fallbackLocation = Location(best).apply {
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    val sourceProvider = best.provider?.takeIf { it.isNotBlank() } ?: "fused"
                    provider = "low_accuracy_fallback:$sourceProvider"
                    extras = (extras ?: Bundle()).apply {
                        putBoolean(EXTRAS_KEY_LOW_ACCURACY_FALLBACK, true)
                        putString(EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER, sourceProvider)
                    }
                }
                if (
                    !shouldEmitFallbackForTransition(
                        previousAcceptedLocation = lastFilteredLocation,
                        fallbackCandidateLocation = fallbackLocation,
                        nowMs = fallbackLocation.time
                    )
                ) {
                    runtimeTelemetry.event("fast_lock_timeout_rejected", "reason=implausible_transition")
                } else if (!shouldPersistFallbackPoint(lastFilteredLocation, fallbackLocation)) {
                    runtimeTelemetry.event("fast_lock_timeout_skipped_persist", "reason=accuracy_uncertainty")
                    emitFallbackPointWithoutPersist(fallbackLocation)
                } else {
                    processLocationUpdateSerialized(
                        fallbackLocation,
                        bypassFilters = true
                    )
                }
            }
            stopFastGpsLockWindow(reason = "timeout")
        }
    }

    private fun stopFastGpsLockWindow(reason: String) {
        if (!isFastGpsLockWindowActive && fastGpsLockWindowJob == null) return
        fastGpsLockWindowJob?.cancel()
        fastGpsLockWindowJob = null
        if (isFastGpsLockWindowActive) {
            fastGpsLockStopCountThisSession++
            runtimeTelemetry.event("fast_lock_stop", "reason=$reason samples=$fastGpsLockSampleCount")
        }
        isFastGpsLockWindowActive = false
        resetFastGpsLockSamples()
        if (
            isTracking &&
            gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
            gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER &&
            gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            reapplyLocationRequestIfActive("fast_lock_stop_$reason")
        }
    }

    private fun resetFastGpsLockSamples() {
        fastGpsLockSampleCount = 0
        fastGpsLockPreferredSample = null
        fastGpsLockBestAccuracySample = null
        fastGpsLockFreshestSample = null
        fastGpsLockNewestSample = null
    }

    private fun recordFastGpsLockSample(location: Location, nowMs: Long, nowElapsedRealtimeNanos: Long) {
        if (!isFastGpsLockWindowActive) return
        fastGpsLockSampleCount += 1
        val sample = Location(location)
        fastGpsLockNewestSample = sample
        fastGpsLockPreferredSample = selectPreferredFastGpsSample(
            currentBest = fastGpsLockPreferredSample,
            candidate = sample,
            desiredAccuracyMeters = resolveCurrentAccuracyFilter(),
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
        if (
            fastGpsLockBestAccuracySample == null ||
            isMoreAccurateSample(sample, fastGpsLockBestAccuracySample)
        ) {
            fastGpsLockBestAccuracySample = sample
        }
        if (
            fastGpsLockFreshestSample == null ||
            isFresherSample(sample, fastGpsLockFreshestSample, nowMs, nowElapsedRealtimeNanos)
        ) {
            fastGpsLockFreshestSample = sample
        }
        val threshold = resolveCurrentAccuracyFilter()
        val earlyExitSampleWindow = fastGpsLockSampleCount in FAST_GPS_LOCK_EARLY_EXIT_MIN_SAMPLES..FAST_GPS_LOCK_MIN_SAMPLES
        if (earlyExitSampleWindow && isFreshAccurateLocation(sample, threshold)) {
            stopFastGpsLockWindow(reason = "early_lock_recovered")
            cancelLowAccuracyFallbackTimer(clearCandidate = true)
        }
        maybeLogFastGpsLockSummary(nowMs)
    }

    private fun selectBestFastGpsLockSample(
        desiredAccuracyMeters: Float,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long
    ): Location? {
        fastGpsLockPreferredSample?.let { preferred ->
            if (isFreshAccurateLocation(preferred, desiredAccuracyMeters)) {
                return Location(preferred)
            }
        }
        fastGpsLockBestAccuracySample?.let { bestAccuracy ->
            if (isFreshAccurateLocation(bestAccuracy, desiredAccuracyMeters)) {
                return Location(bestAccuracy)
            }
        }
        fastGpsLockFreshestSample?.let { freshest ->
            val normalizedTs = CanonicalTimeNormalizer.normalizeTimestampMs(freshest.time, nowMs)
            val ageMs = CanonicalTimeNormalizer.ageMs(
                nowMs = nowMs,
                eventMs = normalizedTs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                eventElapsedRealtimeNanos = freshest.elapsedRealtimeNanos
            )
            if (ageMs in 0..FAST_GPS_LOCK_MAX_SAMPLE_AGE_MS) {
                return Location(freshest)
            }
        }
        return fastGpsLockPreferredSample?.let { Location(it) }
            ?: fastGpsLockBestAccuracySample?.let { Location(it) }
            ?: fastGpsLockNewestSample?.let { Location(it) }
    }

    private fun isFreshAccurateLocation(location: Location?, accuracyFilterMeters: Float): Boolean {
        location ?: return false
        if (!location.hasAccuracy() || location.accuracy > accuracyFilterMeters) return false
        val nowMs = System.currentTimeMillis()
        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(location.time, nowMs)
        val ageMs = CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = normalizedTimestampMs,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            eventElapsedRealtimeNanos = location.elapsedRealtimeNanos
        )
        return ageMs in 0..FAST_GPS_LOCK_MAX_LAST_LOCATION_AGE_MS
    }

    private fun isMoreAccurateSample(candidate: Location, currentBest: Location?): Boolean {
        currentBest ?: return true
        if (!candidate.hasAccuracy()) return false
        if (!currentBest.hasAccuracy()) return true
        return candidate.accuracy < currentBest.accuracy
    }

    private fun isFresherSample(
        candidate: Location,
        currentBest: Location?,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long
    ): Boolean {
        currentBest ?: return true
        val candidateAgeMs = CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = CanonicalTimeNormalizer.normalizeTimestampMs(candidate.time, nowMs),
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            eventElapsedRealtimeNanos = candidate.elapsedRealtimeNanos
        )
        val currentBestAgeMs = CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = CanonicalTimeNormalizer.normalizeTimestampMs(currentBest.time, nowMs),
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            eventElapsedRealtimeNanos = currentBest.elapsedRealtimeNanos
        )
        return candidateAgeMs < currentBestAgeMs
    }

    private fun maybeLogFastGpsLockSummary(nowMs: Long) {
        if (!isFastGpsLockWindowActive) return
        if (nowMs - fastGpsLockLastSummaryAtMs < FAST_GPS_LOCK_SUMMARY_INTERVAL_MS) return
        fastGpsLockLastSummaryAtMs = nowMs
        runtimeTelemetry.event(
            "fast_lock_summary",
            "samples=$fastGpsLockSampleCount starts=$fastGpsLockStartCountThisSession stops=$fastGpsLockStopCountThisSession timeouts=$fastGpsLockTimeoutCountThisSession"
        )
    }

    private fun maybeLogFallbackRejectSummary(nowMs: Long) {
        if (nowMs - lowAccuracyFallbackLastRejectSummaryAtMs < FALLBACK_REJECT_SUMMARY_INTERVAL_MS) return
        lowAccuracyFallbackLastRejectSummaryAtMs = nowMs
        runtimeTelemetry.event(
            "fallback_reject_summary",
            "rejected=$lowAccuracyFallbackRejectedFixCountThisSession armed=$lowAccuracyFallbackArmCountThisSession emitted=$lowAccuracyFallbackEmitCountThisSession"
        )
    }

    private fun shouldEmitFallbackForTransition(
        previousAcceptedLocation: Location?,
        fallbackCandidateLocation: Location,
        nowMs: Long
    ): Boolean {
        if (previousAcceptedLocation == null) return true
        val trackId = FALLBACK_TRANSITION_TRACK_ID
        val config = TrackingPolicyProfiles.fallbackTransitionConfig()
        TrackPointPolicyEngine.resetStream(source = TrackPointSource.LOCAL_GPS, trackId = trackId)
        TrackPointPolicyEngine.evaluate(
            event = trackPointEventFromLocation(previousAcceptedLocation, trackId),
            nowMs = previousAcceptedLocation.time,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            config = config,
        )
        val decision = TrackPointPolicyEngine.evaluate(
            event = trackPointEventFromLocation(fallbackCandidateLocation, trackId),
            nowMs = nowMs,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            config = config,
        )
        return decision.accepted
    }

    private fun trackPointEventFromLocation(location: Location, trackId: String): TrackPointEvent {
        return TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackId,
            lon = location.longitude,
            lat = location.latitude,
            timestampMs = location.time,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            gpsSpeedMps = if (location.hasSpeed()) location.speed else null,
            gpsBearingDeg = if (location.hasBearing()) location.bearing else null,
        )
    }

    private fun isWaitingForProviderState(): Boolean {
        return gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
            gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
    }

    private fun shouldPersistFallbackPoint(
        previousAcceptedLocation: Location?,
        fallbackLocation: Location
    ): Boolean {
        return !isWithinCombinedAccuracyUncertainty(previousAcceptedLocation, fallbackLocation)
    }

    private fun isWithinCombinedAccuracyUncertainty(
        previousAcceptedLocation: Location?,
        candidateLocation: Location
    ): Boolean {
        val previous = previousAcceptedLocation ?: return false
        val distanceMeters = previous.distanceTo(candidateLocation).toDouble()
        if (distanceMeters <= 0.0) return true
        val previousAccuracyMeters = if (previous.hasAccuracy()) {
            previous.accuracy.toDouble().coerceAtLeast(0.0)
        } else {
            0.0
        }
        val candidateAccuracyMeters = if (candidateLocation.hasAccuracy()) {
            candidateLocation.accuracy.toDouble().coerceAtLeast(0.0)
        } else {
            0.0
        }
        val effectiveDistanceMeters = distanceMeters - previousAccuracyMeters - candidateAccuracyMeters
        return effectiveDistanceMeters <= 0.0
    }

    private fun resolveObservedSpeedMps(location: Location, referenceLocation: Location?): Float? {
        val reported = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else null
        val implied = referenceLocation?.let { previous ->
            val elapsedSec = (location.time - previous.time) / 1000f
            if (elapsedSec <= 0f) {
                null
            } else {
                (previous.distanceTo(location) / elapsedSec).coerceAtLeast(0f)
            }
        }
        return when {
            reported == null && implied == null -> null
            reported == null -> implied
            implied == null -> reported
            else -> maxOf(reported, implied)
        }
    }

    private fun selectPreferredFastGpsSample(
        currentBest: Location?,
        candidate: Location?,
        desiredAccuracyMeters: Float,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long
    ): Location? {
        if (candidate == null) return currentBest
        if (currentBest == null) return candidate
        fun ageMs(location: Location): Long {
            val normalized = CanonicalTimeNormalizer.normalizeTimestampMs(location.time, nowMs)
            return CanonicalTimeNormalizer.ageMs(
                nowMs = nowMs,
                eventMs = normalized,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                eventElapsedRealtimeNanos = location.elapsedRealtimeNanos
            )
        }

        fun isValid(location: Location): Boolean {
            if (!location.hasAccuracy() || location.accuracy < 0f) return false
            return ageMs(location) <= 120_000L
        }

        val candidateValid = isValid(candidate)
        val currentValid = isValid(currentBest)
        if (candidateValid != currentValid) {
            return if (candidateValid) candidate else currentBest
        }
        if (!candidateValid && !currentValid) {
            return if (ageMs(candidate) <= ageMs(currentBest)) candidate else currentBest
        }

        val candidateAcc = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAcc = if (currentBest.hasAccuracy()) currentBest.accuracy else Float.MAX_VALUE
        val candidateAge = ageMs(candidate)
        val currentAge = ageMs(currentBest)
        val ageDeltaMs = candidateAge - currentAge
        val accuracyDelta = candidateAcc - currentAcc
        val candidateMeetsDesired = desiredAccuracyMeters > 0f && candidateAcc <= desiredAccuracyMeters
        val currentMeetsDesired = desiredAccuracyMeters > 0f && currentAcc <= desiredAccuracyMeters

        if (kotlin.math.abs(ageDeltaMs) <= 5_000L && candidateMeetsDesired != currentMeetsDesired) {
            return if (candidateMeetsDesired) candidate else currentBest
        }
        if (kotlin.math.abs(accuracyDelta) > 50f && kotlin.math.abs(ageDeltaMs) <= 30_000L) {
            return if (candidateAcc < currentAcc) candidate else currentBest
        }
        if (kotlin.math.abs(ageDeltaMs) > 30_000L && kotlin.math.abs(accuracyDelta) <= 50f) {
            return if (candidateAge < currentAge) candidate else currentBest
        }

        if (desiredAccuracyMeters > 0f && kotlin.math.abs(ageDeltaMs) <= 5_000L) {
            val candidateDistanceToDesired = kotlin.math.abs(candidateAcc - desiredAccuracyMeters)
            val currentDistanceToDesired = kotlin.math.abs(currentAcc - desiredAccuracyMeters)
            if (candidateDistanceToDesired != currentDistanceToDesired) {
                return if (candidateDistanceToDesired < currentDistanceToDesired) candidate else currentBest
            }
        }

        if (candidateAge != currentAge) {
            return if (candidateAge < currentAge) candidate else currentBest
        }
        if (candidateAcc != currentAcc) {
            return if (candidateAcc < currentAcc) candidate else currentBest
        }
        return candidate
    }

    private fun selectMoreAccurateLocation(currentBest: Location?, candidate: Location): Location {
        if (currentBest == null) return Location(candidate)
        val candidateAcc = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAcc = if (currentBest.hasAccuracy()) currentBest.accuracy else Float.MAX_VALUE
        return if (candidateAcc < currentAcc) Location(candidate) else currentBest
    }

    private fun selectNewerTimestampLocation(currentNewest: Location?, candidate: Location): Location {
        if (currentNewest == null) return Location(candidate)
        return if (candidate.time > currentNewest.time) Location(candidate) else currentNewest
    }

    private fun computeElasticitySpeedBucket(speedMps: Float?): Int {
        if (speedMps == null || !speedMps.isFinite() || speedMps <= 0f) return 0
        val bucket = kotlin.math.round(speedMps / ELASTICITY_SPEED_BUCKET_SIZE_MPS).toInt()
        return bucket.coerceIn(0, ELASTICITY_MAX_SPEED_BUCKET)
    }

    private fun computeElasticDistanceFilterMeters(baseDistanceMeters: Float, speedBucket: Int): Float {
        val base = baseDistanceMeters.coerceAtLeast(0f)
        if (speedBucket <= 0 || base <= 0f) return base
        val extra = base * ELASTICITY_MULTIPLIER * speedBucket.toFloat()
        return (base + extra).coerceAtMost(TrackerSettings.MAX_DISTANCE_FILTER_METERS)
    }

    private fun computeElasticityModeBoundBucket(speedBucket: Int, motionMode: TrackingMotionMode): Int {
        return when (motionMode) {
            TrackingMotionMode.WALKING -> speedBucket.coerceIn(0, WALKING_ELASTICITY_MAX_SPEED_BUCKET)
            TrackingMotionMode.BIKING, TrackingMotionMode.DRIVING -> speedBucket.coerceIn(0, ELASTICITY_MAX_SPEED_BUCKET)
        }
    }

    private fun logNotificationSurfaceDiagnostics(
        trigger: String,
        action: String?,
        path: StartupCommandPath,
        stage: String
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
        val channel = notificationManager?.getNotificationChannel(CHANNEL_ID)
        val activeNotificationIds = runCatching {
            notificationManager?.activeNotifications?.map { it.id } ?: emptyList()
        }.getOrElse { emptyList() }
        val appImportance = runCatching { notificationManager?.importance }.getOrNull()
        Log.i(
            TAG,
            "Notification diagnostics stage=$stage trigger=$trigger action=$action path=$path " +
                "notificationsEnabled=${notificationManager?.areNotificationsEnabled()} appImportance=$appImportance " +
                "channelExists=${channel != null} channelImportance=${channel?.importance} " +
                "channelLockscreenVisibility=${channel?.lockscreenVisibility} " +
                "channelBypassDnd=${channel?.canBypassDnd()} channelShowBadge=${channel?.canShowBadge()} " +
                "activeNotificationIds=$activeNotificationIds " +
                "keyguardLocked=${keyguardManager?.isKeyguardLocked} " +
                "deviceLocked=${keyguardManager?.isDeviceLocked} userUnlocked=${userManager?.isUserUnlocked}"
        )
    }

    private fun mapRuntimeTrigger(trigger: String): RuntimeTrigger {
        return when (trigger) {
            "explicit_start" -> RuntimeTrigger.EXPLICIT_START
            "process_restart" -> RuntimeTrigger.PROCESS_RESTART
            "watchdog_tick" -> RuntimeTrigger.WATCHDOG_TICK
            "main_resume_after_kill" -> RuntimeTrigger.MAIN_RESUME_AFTER_KILL
            "main_start_on_launch" -> RuntimeTrigger.MAIN_START_ON_LAUNCH
            else -> RuntimeTrigger.UNKNOWN
        }
    }

    private fun transitionGpsState(event: GpsRuntimeEvent, reason: String) {
        val previous = gpsRuntimeState
        val next = GpsRuntimeStateMachine.transition(previous, event)
        if (next != previous) {
            Log.d(TAG, "GPS runtime state $previous -> $next event=$event reason=$reason")
            runtimeTelemetry.event(
                name = "gps_state",
                details = "from=$previous to=$next event=$event reason=$reason"
            )
        }
        gpsRuntimeState = next
    }

    private fun publishTrackPoint(
        trackId: String,
        location: Location,
        propsJson: String?,
        quality: TrackPointQuality
    ) {
        val orderingKey = if (location.extras?.getBoolean(EXTRAS_KEY_MANUAL_SEND, false) == true) {
            location.time
        } else {
            localTrackPointOrderingCounter.incrementAndGet()
        }
        TrackPointBus.publish(
            TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = trackId,
                lon = location.longitude,
                lat = location.latitude,
                timestampMs = location.time,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                propsJson = propsJson,
                quality = quality,
                orderingKey = orderingKey,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                gpsSpeedMps = if (location.hasSpeed()) location.speed else null,
                gpsBearingDeg = if (location.hasBearing()) location.bearing else null,
            )
        )
    }

    private fun emitFallbackPointWithoutPersist(location: Location) {
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (!hasValidSelectedTrackerId(trackerId)) return
        val propsJson = buildLocalPointPropsJson(
            location = location,
            distanceMeters = runtimeSnapshot.sessionTotalDistanceMeters
        )
        updateRuntimeSnapshot {
            it.copy(
                lastTrackedLatitude = location.latitude,
                lastTrackedLongitude = location.longitude,
                lastTrackedTimestampMs = location.time,
                lastTrackedPropsJson = propsJson
            )
        }
        publishTrackPoint(
            trackId = trackerId,
            location = location,
            propsJson = propsJson,
            quality = TrackPointQuality.DEGRADED
        )
        syncRuntimeStateStore()
    }

    private fun resolveTrackPointQuality(location: Location, propsJson: String?): TrackPointQuality {
        if (location.extras?.getBoolean(EXTRAS_KEY_MANUAL_SEND, false) == true) {
            return TrackPointQuality.DEGRADED
        }
        if (location.extras?.getBoolean(EXTRAS_KEY_LOW_ACCURACY_FALLBACK, false) == true) {
            return TrackPointQuality.DEGRADED
        }
        if (propsJson?.contains("\"fast_lock_timeout_best_sample\":true") == true) {
            return TrackPointQuality.DEGRADED
        }
        return TrackPointQuality.HIGH_CONFIDENCE
    }

    private fun hasRecoveredFastGpsLock(
        quality: TrackPointQuality,
        measuredAccuracyMeters: Float?,
        accuracyFilterMeters: Float
    ): Boolean {
        if (quality != TrackPointQuality.HIGH_CONFIDENCE) return false
        val measured = measuredAccuracyMeters ?: return false
        return measured <= accuracyFilterMeters
    }

    private fun triggerLightHaptic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val vibratorManager = getSystemService(VibratorManager::class.java) ?: return
        val vibrator = vibratorManager.defaultVibrator
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun buildLocalPointPropsJson(location: Location, distanceMeters: Float): String? {
        val settings = settingsRepository.getSettings()
        if (!settings.sendExtendedData) return null
        return try {
            val props = JSONObject()
            val timestampMs = location.time
            val timestampSec = if (timestampMs >= 1_000_000_000_000L) timestampMs / 1000L else timestampMs
            props.put("timestamp", timestampSec)
            props.put("starttimestamp", runtimeSnapshot.sessionStartTimeMs)
            if (location.hasAccuracy()) props.put("acc", location.accuracy.toDouble())
            if (location.hasAltitude()) props.put("alt", location.altitude)
            if (location.hasBearing()) props.put("bearing", location.bearing.toDouble())
            if (location.hasSpeed()) props.put("spd_kph", location.speed * 3.6f)
            props.put("prov", location.provider ?: "geovault")
            props.put("dist", distanceMeters.toDouble())
            if (location.extras?.getBoolean(EXTRAS_KEY_LOW_ACCURACY_FALLBACK, false) == true) {
                props.put("low_accuracy_fallback", true)
                location.extras?.getString(EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER)?.let { sourceProvider ->
                    props.put("fallback_source_provider", sourceProvider)
                }
            }
            if (location.extras?.getBoolean(EXTRAS_KEY_MANUAL_SEND, false) == true) {
                props.put("manual_send", true)
            }
            location.extras?.getInt("satellites", 0)?.takeIf { it > 0 }?.let { props.put("sat", it) }
            props.put("batt", readBatteryLevel())
            props.put("ischarging", isCharging())
            val deviceIdentifier = getDeviceIdentifier()
            if (deviceIdentifier.isNotEmpty()) {
                props.put("ser", deviceIdentifier)
            }
            props.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build extended tracking point payload", e)
            null
        }
    }

    private fun getManualSendCandidateLocation(): Location? {
        latestObservedRawLocation?.let { return Location(it) }
        lowAccuracyFallbackCandidate?.let { return Location(it) }
        lastFilteredLocation?.let { return Location(it) }
        return null
    }

    private fun buildManualSendLocation(source: Location): Location {
        return Location(source).apply {
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            val sourceProvider = source.provider?.takeIf { it.isNotBlank() } ?: "fused"
            provider = "manual_send:$sourceProvider"
            val mergedExtras = Bundle().apply {
                source.extras?.let { putAll(it) }
                putBoolean(EXTRAS_KEY_MANUAL_SEND, true)
            }
            extras = mergedExtras
        }
    }

    private fun getDeviceIdentifier(): String {
        val androidId = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (!androidId.isNullOrBlank()) {
            return androidId
        }
        return packageName
    }
}
