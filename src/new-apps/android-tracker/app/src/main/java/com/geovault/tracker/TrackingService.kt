package com.geovault.tracker

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.location.TrackingSyncPolicy
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.GpsRuntimeEvent
import com.geovault.tracker.services.GpsRuntimeState
import com.geovault.tracker.services.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingUiStatusResolver
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.settings.TrackerSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TrackingService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    private lateinit var database: AppDatabase
    private lateinit var settingsRepository: TrackerSettingsRepository
    private lateinit var locationManager: LocationManager
    private lateinit var sessionCoordinator: TrackingSessionCoordinator
    private lateinit var locationIngestCoordinator: LocationIngestCoordinator
    private lateinit var notificationPresenter: TrackingNotificationPresenter
    private lateinit var runtimeEventPublisher: RuntimeEventPublisher
    private lateinit var queueUploadEngine: QueueUploadEngine
    private lateinit var locationSessionCoordinator: LocationSessionCoordinator
    private var httpClient: OkHttpClient? = null

    @Volatile
    private var isTracking: Boolean = false
    @Volatile
    private var startupInProgress: Boolean = false
    private var startupForegroundPromoted: Boolean = false
    private var sessionVisibleBoundaryId: Long = 0L
    private var sessionBoundaryForBacklogId: Long = 0L
    private var lastFilteredLocation: Location? = null
    private var latestObservedRawLocation: Location? = null
    private var lowAccuracyFallbackCandidate: Location? = null
    private val lowAccuracyFallbackCoordinator = LowAccuracyFallbackCoordinator()
    private var lowAccuracyFallbackJob: Job? = null
    private var consecutivePushFailures = 0
    private var lastSyncFailureClass: SyncFailureClass = SyncFailureClass.NONE
    private var gpsRuntimeState: GpsRuntimeState = GpsRuntimeState.INACTIVE
    private var trackingGeneration: Int = 0
    private var runtimeSnapshot: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot()
    private val startupStateLock = Any()
    private val pushDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val locationListener: LocationListener = LocationListener { location ->
        if (!isTracking) return@LocationListener
        latestObservedRawLocation = Location(location)
        serviceScope.launch(Dispatchers.IO) {
            processLocationUpdate(location)
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
            if (isGpsProviderEnabled()) {
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
        const val ACTION_TRACKING_ERROR = "com.geovault.tracker.ACTION_TRACKING_ERROR"
        const val EXTRA_TRACKING_ERROR_MESSAGE = "extra_tracking_error_message"
        const val NOTIFICATION_DISMISSED_ACTION = "com.geovault.tracker.TRACKING_NOTIFICATION_DISMISSED"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "tracker_service"
        const val SESSION_STATS_UPDATE = "com.geovault.tracker.SESSION_STATS_UPDATE"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var sessionStartTimeMs: Long = 0

        @Volatile
        var pointsSentThisSession: Int = 0

        @Volatile
        var lastPointSentAtMs: Long = 0

        @Volatile
        var queuedPointsVisible: Int = 0

        @Volatile
        var sessionTotalDistanceMeters: Float = 0f

        @Volatile
        var lastAccuracyMeters: Float? = null

        @Volatile
        var lastTrackedLatitude: Double? = null

        @Volatile
        var lastTrackedLongitude: Double? = null

        @Volatile
        var lastTrackedTimestampMs: Long = 0L

        @Volatile
        var lastTrackedPropsJson: String? = null

        private const val MAX_QUEUE_SIZE = 5000
        private const val MAX_QUEUE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val RETRY_JITTER_MS = 2_000L
        private const val MAX_BATCHES_PER_PUSH = 10
        private const val EXTRAS_KEY_LOW_ACCURACY_FALLBACK = "low_accuracy_fallback"
        private const val EXTRAS_KEY_MANUAL_SEND = "manual_send"

        @JvmStatic
        fun shouldRestartTrackingAfterProcessDeath(): Boolean = false

        internal enum class StartupCommandPath {
            StartTracking,
            StopNoRestart,
            ReshowForeground,
            ManualSendPoint,
            StopUnknown
        }

        @JvmStatic
        internal fun resolveStartupCommandPath(action: String?): StartupCommandPath {
            return when (action) {
                ACTION_START -> StartupCommandPath.StartTracking
                ACTION_STOP -> StartupCommandPath.StopUnknown
                ACTION_RESHOW_FOREGROUND -> StartupCommandPath.ReshowForeground
                ACTION_SEND_MANUAL_POINT -> StartupCommandPath.ManualSendPoint
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
                null -> "process_restart"
                else -> "unknown_action"
            }
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
        Log.d(TAG, "onCreate")
        settingsRepository = settingsRepositoryLazy
        database = AppDatabase.getDatabase(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationSessionCoordinator = LocationSessionCoordinator(this, locationManager)
        sessionCoordinator = TrackingSessionCoordinator()
        locationIngestCoordinator = LocationIngestCoordinator(database.locationDao())
        notificationPresenter = TrackingNotificationPresenter(this)
        runtimeEventPublisher = RuntimeEventPublisher(applicationContext)
        queueUploadEngine = QueueUploadEngine(
            context = applicationContext,
            locationDao = database.locationDao(),
            pushContext = pushDispatcher,
            authenticatedClientProvider = { getAuthenticatedHttpClient() }
        )
        TrackingRecoveryCoordinator.markHeartbeat(applicationContext)
        runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.HEARTBEAT,
            reason = "service_created"
        )
        syncRuntimeStateStore()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startupTrigger = resolveStartupTrigger(intent?.action)
        val commandPath = resolveStartupCommandPath(action = intent?.action)
        Log.i(
            TAG,
            "onStartCommand action=${intent?.action} path=$commandPath startId=$startId trigger=$startupTrigger isTracking=$isTracking"
        )
        if (requiresForegroundPromotion(commandPath) &&
            !promoteToForegroundForStartup(trigger = startupTrigger)
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
                        val count = database.locationDao().getCurrentSessionCountById(sessionVisibleBoundaryId)
                        runtimeSnapshot = runtimeSnapshot.copy(queuedPointsVisible = count)
                        syncRuntimeStateStore()
                        withContext(Dispatchers.Main) {
                            startForeground(
                                NOTIFICATION_ID,
                                notificationPresenter.buildTrackingNotification(runtimeSnapshot.pointsSentThisSession, count),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            )
                        }
                    }
                }
                START_STICKY
            }
            StartupCommandPath.ManualSendPoint -> {
                handleManualSendPointCommand()
                if (isTracking || startupInProgress) START_STICKY else START_NOT_STICKY
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
        if (isTracking) {
            TrackingRecoveryCoordinator.markUnexpectedDestroy(applicationContext, wasTracking = true)
            runtimeEventPublisher.publish(
                type = RuntimeServiceEventType.UNEXPECTED_DESTROY,
                reason = "on_destroy_while_tracking"
            )
        }
        cleanupServiceResources(reason = "on_destroy")
        serviceJob.cancel()
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
            startupInProgress = true
        }
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (!hasValidSelectedTrackerId(selectedTrackerId)) {
            Log.w(TAG, "Start blocked: invalid selected tracker id")
            startupInProgress = false
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
            startupInProgress = false
            failStartup(
                message = getString(R.string.location_permissions_required),
                path = path,
                trigger = trigger,
                reason = "permissions_missing"
            )
            return false
        }
        if (!isGpsProviderEnabled()) {
            Log.w(TAG, "Start blocked: GPS provider disabled")
            startupInProgress = false
            failStartup(
                message = getString(R.string.gps_provider_required),
                path = path,
                trigger = trigger,
                reason = "gps_disabled"
            )
            return false
        }
        serviceScope.launch {
            try {
                performStartTracking(trigger = trigger)
            } finally {
                startupInProgress = false
            }
        }
        return true
    }

    private suspend fun performStartTracking(trigger: String) {
        trackingGeneration++
        val runGeneration = trackingGeneration
        sessionVisibleBoundaryId = withContext(Dispatchers.IO) {
            database.locationDao().getMaxId()
        }
        sessionBoundaryForBacklogId = sessionVisibleBoundaryId
        lastFilteredLocation = null
        latestObservedRawLocation = null
        lowAccuracyFallbackCandidate = null
        transitionGpsState(GpsRuntimeEvent.TRACKING_STARTED, "perform_start_tracking")
        isTracking = true
        runtimeSnapshot = sessionCoordinator.transitionToRunning(
            previous = runtimeSnapshot,
            nowMs = System.currentTimeMillis()
        )

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
            updateNotificationFromDb(broadcastStats = true)
            Log.i(TAG, "Tracking session started boundary=$sessionVisibleBoundaryId")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location updates security failure", e)
            failActiveTrackingAndStop(getString(R.string.unable_to_start_location_updates))
        }
    }

    private fun stopTracking(reason: String, failureReason: String? = null) {
        val wasRunning = isTracking
        Log.d(TAG, "Stopping tracking reason=$reason wasRunning=$wasRunning")
        transitionToStoppedState(failureReason = failureReason)
        settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = reason)
        if (wasRunning) {
            runtimeEventPublisher.publish(
                type = RuntimeServiceEventType.TRACKING_STOPPED,
                reason = reason,
                trigger = RuntimeTrigger.EXPLICIT_STOP
            )
        }
        cleanupServiceResources(reason = reason)
        stopServiceInstance(reason = reason)
    }

    private fun transitionToStoppedState(failureReason: String?) {
        trackingGeneration++
        isTracking = false
        startupInProgress = false
        transitionGpsState(GpsRuntimeEvent.TRACKING_STOPPED, "transition_to_stopped_state")
        lastFilteredLocation = null
        latestObservedRawLocation = null
        lowAccuracyFallbackCandidate = null
        runtimeSnapshot = sessionCoordinator.transitionToStopped(
            previous = runtimeSnapshot,
            failureReason = failureReason
        )
        syncRuntimeStateStore()
    }

    private fun cleanupServiceResources(reason: String) {
        Log.d(TAG, "Cleaning service resources reason=$reason")
        stopRecoveryHeartbeat()
        stopRetryJob()
        stopPreflightMonitor()
        stopBacklogUploader()
        unregisterGpsProviderReceiverIfNeeded()
        cancelLowAccuracyFallbackTimer(clearCandidate = true)
        stopLocationUpdates()
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
        val settings = settingsRepository.getSettings()
        val intervalMs = TrackingLocationPolicy.locationRequestIntervalFromSec(settings.loggingIntervalSec).first
        val minDistance = settings.distanceFilterMeters.coerceAtLeast(0f)
        locationSessionCoordinator.stopGpsSession(locationListener)
        locationSessionCoordinator.startGpsSession(
            intervalMs = intervalMs,
            minDistanceMeters = minDistance,
            listener = locationListener
        )
    }

    private fun stopLocationUpdates() {
        locationSessionCoordinator.stopGpsSession(locationListener)
    }

    private suspend fun processLocationUpdate(
        location: Location,
        bypassFilters: Boolean = false,
        propsJson: String? = null
    ) {
        val settings = settingsRepository.getSettings()
        val distanceDeltaMeters = if (lastFilteredLocation != null) {
            lastFilteredLocation!!.distanceTo(location).coerceAtLeast(0f)
        } else {
            0f
        }
        val nextSessionDistance = runtimeSnapshot.sessionTotalDistanceMeters + distanceDeltaMeters
        if (!bypassFilters && settings.lowAccuracyFallbackEnabled) {
            val rejectedByAccuracy = location.hasAccuracy() && location.accuracy > settings.accuracyFilterMeters
            if (rejectedByAccuracy) {
                transitionGpsState(GpsRuntimeEvent.FIX_REJECTED, "rejected_by_accuracy")
                lowAccuracyFallbackCandidate = Location(location)
                val shouldStartTimer = lowAccuracyFallbackCoordinator.onRejectedFixForLock(
                    fallbackEligible = true,
                    candidateLatitude = location.latitude,
                    candidateLongitude = location.longitude,
                    candidateTimestampMs = location.time
                )
                if (shouldStartTimer) {
                    transitionGpsState(GpsRuntimeEvent.FALLBACK_TIMER_ARMED, "fallback_timer_armed")
                    ensureLowAccuracyFallbackTimerRunning()
                }
            }
        }
        val result = locationIngestCoordinator.ingest(
            location = location,
            settings = settings,
            previousAcceptedLocation = lastFilteredLocation,
            sessionVisibleBoundaryId = sessionVisibleBoundaryId,
            maxQueueSize = MAX_QUEUE_SIZE,
            bypassFilters = bypassFilters,
            propsJson = propsJson,
            totalDistanceMeters = nextSessionDistance
        )
        runtimeSnapshot = runtimeSnapshot.copy(
            lastAccuracyMeters = result.lastAccuracyMeters,
            sessionTotalDistanceMeters = if (result.accepted) nextSessionDistance else runtimeSnapshot.sessionTotalDistanceMeters
        )
        withContext(Dispatchers.Main) { syncRuntimeStateStore() }
        if (!result.accepted) {
            return
        }

        transitionGpsState(GpsRuntimeEvent.FIX_ACCEPTED, "fix_accepted")
        lowAccuracyFallbackCoordinator.onAcceptedFix()
        cancelLowAccuracyFallbackTimer(clearCandidate = false)
        lastFilteredLocation = result.lastFilteredLocation
        runtimeSnapshot = runtimeSnapshot.copy(
            queuedPointsVisible = result.queuedPointsVisible,
            lastTrackedLatitude = result.lastTrackedLatitude,
            lastTrackedLongitude = result.lastTrackedLongitude,
            lastTrackedTimestampMs = result.lastTrackedTimestampMs,
            lastTrackedPropsJson = result.lastTrackedPropsJson
        )
        withContext(Dispatchers.Main) {
            syncRuntimeStateStore()
            updateNotificationFromDb(broadcastStats = false)
        }
        pushQueuedLocations(scope = QueueUploadScope.LIVE_ONLY)
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
        val candidate = getManualSendCandidateLocation() ?: run {
            Log.w(TAG, "Manual send ignored: no candidate location available")
            return false
        }
        val manualLocation = buildManualSendLocation(candidate)
        serviceScope.launch(Dispatchers.IO) {
            processLocationUpdate(
                location = manualLocation,
                bypassFilters = true,
                propsJson = """{"manual_send":true}"""
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@TrackingService,
                    getString(R.string.manual_send_point_sent),
                    Toast.LENGTH_SHORT
                ).show()
            }
            updateNotificationFromDb(broadcastStats = true)
        }
        return true
    }

    private fun failStartup(message: String, path: StartupCommandPath, trigger: String, reason: String) {
        Log.w(TAG, "Tracking start failed: $reason path=$path trigger=$trigger")
        settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = "startup_failed")
        runtimeSnapshot = runtimeSnapshot.copy(failureReason = message)
        runtimeEventPublisher.publish(
            type = RuntimeServiceEventType.STARTUP_FAILED,
            reason = reason,
            trigger = mapRuntimeTrigger(trigger)
        )
        syncRuntimeStateStore()
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(this@TrackingService, message, Toast.LENGTH_LONG).show()
        }
        stopSelfSafelyAfterStartup(reason = "startup_failed")
    }

    private fun failActiveTrackingAndStop(message: String) {
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

    private fun promoteToForegroundForStartup(trigger: String): Boolean {
        if (startupForegroundPromoted) return true
        return try {
            startForeground(
                NOTIFICATION_ID,
                notificationPresenter.buildTrackingNotification(
                    runtimeSnapshot.pointsSentThisSession,
                    runtimeSnapshot.queuedPointsVisible
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            startupForegroundPromoted = true
            Log.i(TAG, "Foreground promotion succeeded trigger=$trigger")
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
            false
        }
    }

    private fun stopSelfSafelyAfterStartup(reason: String) {
        cleanupServiceResources(reason = reason)
        stopServiceInstance(reason = reason)
    }

    private fun updateNotificationFromDb(broadcastStats: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            val count = if (isTracking) {
                database.locationDao().getCurrentSessionCountById(sessionVisibleBoundaryId)
            } else {
                0
            }
            runtimeSnapshot = runtimeSnapshot.copy(queuedPointsVisible = count)
            withContext(Dispatchers.Main) {
                syncRuntimeStateStore()
                if (startupForegroundPromoted) {
                    notificationPresenter.updateForegroundNotification(
                        sentCount = runtimeSnapshot.pointsSentThisSession,
                        queuedCount = count
                    )
                }
            }
            if (broadcastStats) {
                sendBroadcast(
                    Intent(SESSION_STATS_UPDATE).apply { setPackage(packageName) }
                )
            }
        }
    }

    private fun syncRuntimeStateStore() {
        val gpsOk = isGpsProviderEnabled()
        val settings = settingsRepository.getSettings()
        val effectiveAccuracyThreshold = settings.accuracyFilterMeters
        val uiStatus = TrackingUiStatusResolver.resolve(
            isRunning = isTracking,
            gpsProviderEnabled = gpsOk,
            gpsPaused = gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
                gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION,
            lastAccuracyMeters = runtimeSnapshot.lastAccuracyMeters,
            effectiveAccuracyThresholdMeters = effectiveAccuracyThreshold
        )
        val next = runtimeSnapshot.copy(
            isRunning = isTracking,
            lifecycleState = if (isTracking) TrackingLifecycleState.RUNNING else TrackingLifecycleState.STOPPED,
            selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this),
            selectedTrackerName = SelectedTrackerPrefs.selectedTrackerName(this),
            gpsProviderEnabled = gpsOk,
            autoTrackingEnabled = settings.autoTrackingMode,
            activeMotionMode = TrackingMotionMode.fromProfileIndex(settings.trackingProfile.index),
            uiStatus = uiStatus,
            gpsPaused = gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER ||
                gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION,
            effectiveAccuracyThresholdMeters = effectiveAccuracyThreshold
        )
        runtimeSnapshot = next
        TrackingRuntimeStateStore.update { next }
        mirrorLegacyCompanionState(next)
    }

    private fun isGpsProviderEnabled(): Boolean {
        return locationSessionCoordinator.isGpsProviderEnabled()
    }

    private fun startRecoveryHeartbeat() {
        recoveryHeartbeatJob?.cancel()
        recoveryHeartbeatJob = serviceScope.launch {
            while (isTracking) {
                TrackingRecoveryCoordinator.markHeartbeat(applicationContext)
                runtimeEventPublisher.publish(
                    type = RuntimeServiceEventType.HEARTBEAT,
                    reason = "service_heartbeat"
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
                val count = database.locationDao().getCount()
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
                val backlogCount = database.locationDao().getBacklogCountById(sessionBoundaryId)
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
                if (!isGpsProviderEnabled()) {
                    withContext(Dispatchers.Main) {
                        enterWaitingForGpsProvider(reason = "preflight_monitor")
                    }
                    continue
                }
                if (gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER) {
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
        if (!isTracking || gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER) return
        transitionGpsState(GpsRuntimeEvent.PROVIDER_DISABLED, reason)
        stopLocationUpdates()
        Log.w(TAG, "GPS provider disabled while tracking reason=$reason")
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    private fun resumeFromGpsProviderWait(reason: String) {
        if (!isTracking || gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER) return
        transitionGpsState(GpsRuntimeEvent.PROVIDER_ENABLED, reason)
        if (!runCatching { startLocationUpdates() }.isSuccess) {
            failActiveTrackingAndStop(getString(R.string.unable_to_start_location_updates))
            return
        }
        Log.i(TAG, "GPS provider re-enabled, resumed updates reason=$reason")
        syncRuntimeStateStore()
        updateNotificationFromDb(broadcastStats = true)
    }

    private fun ensureLowAccuracyFallbackTimerRunning() {
        if (lowAccuracyFallbackJob?.isActive == true) return
        val timeoutSec = settingsRepository.getSettings().lowAccuracyFallbackTimeoutSec
        lowAccuracyFallbackJob = serviceScope.launch(Dispatchers.IO) {
            delay(timeoutSec.coerceAtLeast(1L) * 1000L)
            val candidate = lowAccuracyFallbackCandidate ?: return@launch
            val settings = settingsRepository.getSettings()
            if (!lowAccuracyFallbackCoordinator.shouldEmitFallback(
                    fallbackEligible = settings.lowAccuracyFallbackEnabled,
                    hasCandidate = true
                )
            ) {
                return@launch
            }
            lowAccuracyFallbackCoordinator.onFallbackEmitted(
                candidateLatitude = candidate.latitude,
                candidateLongitude = candidate.longitude,
                candidateTimestampMs = candidate.time
            )
            transitionGpsState(GpsRuntimeEvent.FALLBACK_EMITTED, "fallback_emitted")
            val fallbackLocation = Location(candidate).apply {
                provider = "low_accuracy_fallback:${candidate.provider ?: "gps"}"
                time = System.currentTimeMillis()
                extras = (extras ?: android.os.Bundle()).apply {
                    putBoolean(EXTRAS_KEY_LOW_ACCURACY_FALLBACK, true)
                }
            }
            processLocationUpdate(
                location = fallbackLocation,
                bypassFilters = true,
                propsJson = """{"low_accuracy_fallback":true}"""
            )
        }
    }

    private fun cancelLowAccuracyFallbackTimer(clearCandidate: Boolean) {
        lowAccuracyFallbackJob?.cancel()
        lowAccuracyFallbackJob = null
        lowAccuracyFallbackCoordinator.onTrackingStopped()
        if (clearCandidate) {
            lowAccuracyFallbackCandidate = null
        }
    }

    private suspend fun pushQueuedLocations(scope: QueueUploadScope) {
        if (!NetworkStatusMonitor.hasUsableNetwork(this)) {
            lastSyncFailureClass = SyncFailureClass.TRANSIENT
            consecutivePushFailures++
            return
        }
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
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
                deviceIdentifier = packageName
            ),
            onBatchUploaded = { visibleSentCount ->
                val sentDelta = visibleSentCount.coerceAtLeast(0)
                if (sentDelta > 0) {
                    runtimeSnapshot = runtimeSnapshot.copy(
                        pointsSentThisSession = runtimeSnapshot.pointsSentThisSession + sentDelta,
                        lastPointSentAtMs = System.currentTimeMillis()
                    )
                }
            }
        )
        lastSyncFailureClass = outcome
        if (outcome == SyncFailureClass.NONE) {
            consecutivePushFailures = 0
        } else {
            consecutivePushFailures++
        }
        trimQueuedLocationsRetention()
        withContext(Dispatchers.Main) {
            updateNotificationFromDb(broadcastStats = true)
        }
    }

    private fun trimQueuedLocationsRetention() {
        val cutoff = System.currentTimeMillis() - MAX_QUEUE_AGE_MS
        database.locationDao().deleteOlderThan(cutoff)
        val count = database.locationDao().getCount()
        if (count > MAX_QUEUE_SIZE) {
            database.locationDao().deleteOldestCount(count - MAX_QUEUE_SIZE)
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
        }
        gpsRuntimeState = next
    }

    private fun mirrorLegacyCompanionState(snapshot: TrackingRuntimeSnapshot) {
        isRunning = snapshot.isRunning
        sessionStartTimeMs = snapshot.sessionStartTimeMs
        pointsSentThisSession = snapshot.pointsSentThisSession
        lastPointSentAtMs = snapshot.lastPointSentAtMs
        queuedPointsVisible = snapshot.queuedPointsVisible
        sessionTotalDistanceMeters = snapshot.sessionTotalDistanceMeters
        lastAccuracyMeters = snapshot.lastAccuracyMeters
        lastTrackedLatitude = snapshot.lastTrackedLatitude
        lastTrackedLongitude = snapshot.lastTrackedLongitude
        lastTrackedTimestampMs = snapshot.lastTrackedTimestampMs
        lastTrackedPropsJson = snapshot.lastTrackedPropsJson
    }

    private fun getManualSendCandidateLocation(): Location? {
        latestObservedRawLocation?.let { return Location(it) }
        lowAccuracyFallbackCandidate?.let { return Location(it) }
        lastFilteredLocation?.let { return Location(it) }
        return locationSessionCoordinator.lastKnownGpsLocation()?.let { Location(it) }
    }

    private fun buildManualSendLocation(source: Location): Location {
        return Location(source).apply {
            time = System.currentTimeMillis()
            provider = "manual:${source.provider ?: "gps"}"
        }
    }
}
