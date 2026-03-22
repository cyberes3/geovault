package com.geovault.tracker

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.location.TrackingControlPlane
import com.geovault.tracker.location.TrackingControlState
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingSyncPolicy
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.UnifiedLocationClient
import com.geovault.tracker.location.UnifiedLocationSessionRequest
import com.geovault.tracker.pipeline.CanonicalTimeNormalizer
import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointPipeline
import com.geovault.tracker.pipeline.TrackPointQuality
import com.geovault.tracker.pipeline.TrackPointRejectReason
import com.geovault.tracker.pipeline.TrackPointSource
import com.geovault.tracker.pipeline.TrackPointServiceBase
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.google.android.gms.location.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import javax.inject.Inject

@AndroidEntryPoint
class TrackingService : TrackPointServiceBase() {
    internal class QueueInFlightClaimSet {
        private val mutex = Mutex()
        private val claimedIds = mutableSetOf<Long>()

        suspend fun claim(candidates: List<QueuedLocation>, limit: Int): List<QueuedLocation> {
            if (limit <= 0 || candidates.isEmpty()) return emptyList()
            return mutex.withLock {
                val batch = ArrayList<QueuedLocation>(limit)
                for (item in candidates) {
                    if (item.id in claimedIds) continue
                    claimedIds.add(item.id)
                    batch.add(item)
                    if (batch.size >= limit) break
                }
                batch
            }
        }

        suspend fun release(batch: List<QueuedLocation>) {
            if (batch.isEmpty()) return
            mutex.withLock {
                for (item in batch) {
                    claimedIds.remove(item.id)
                }
            }
        }
    }

    private enum class QueueUploadScope {
        BACKLOG_ONLY,
        LIVE_ONLY,
        ALL
    }

    companion object {
        const val TAG = "TrackingService"
        const val ACTION_START = "com.geovault.tracker.ACTION_START"
        const val ACTION_STOP = "com.geovault.tracker.ACTION_STOP"
        const val ACTION_RESHOW_FOREGROUND = "com.geovault.tracker.ACTION_RESHOW_FOREGROUND"
        const val ACTION_TRACKING_ERROR = "com.geovault.tracker.ACTION_TRACKING_ERROR"
        const val EXTRA_TRACKING_ERROR_MESSAGE = "extra_tracking_error_message"
        const val NOTIFICATION_DISMISSED_ACTION = "com.geovault.tracker.TRACKING_NOTIFICATION_DISMISSED"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "tracker_service"
        @Suppress("unused")
        private const val NOTIFICATION_GROUP_KEY = "geovault_service_group"
        const val SESSION_STATS_UPDATE = "com.geovault.tracker.SESSION_STATS_UPDATE"

        @Volatile
        var isRunning = false

        /** Session start time (System.currentTimeMillis()). 0 when not tracking. */
        @Volatile
        var sessionStartTimeMs: Long = 0

        /** Number of points successfully sent to server this session. */
        @Volatile
        var pointsSentThisSession: Int = 0

        /** When we last successfully sent at least one point (System.currentTimeMillis()). */
        @Volatile
        var lastPointSentAtMs: Long = 0

        /** Total distance (meters) traveled this session. 0 when not tracking. */
        @Volatile
        var sessionTotalDistanceMeters: Float = 0f

        /** Accuracy (meters) of the most recent location; null if unknown. */
        @Volatile
        var lastAccuracyMeters: Float? = null

        /** Latest locally tracked point snapshot for UI consumers (e.g. params view). */
        @Volatile
        var lastTrackedLatitude: Double? = null
        @Volatile
        var lastTrackedLongitude: Double? = null
        @Volatile
        var lastTrackedTimestampMs: Long = 0L
        @Volatile
        var lastTrackedPropsJson: String? = null

        /** Interval between retry attempts when the queue has failed-to-send items. */
        const val RETRY_INTERVAL_MS = 60_000L

        /** ±jitter (ms) added to retry interval to avoid thundering herd. */
        private const val RETRY_JITTER_MS = 10_000L

        /** Max batches to send in one push call to avoid holding the lock too long. */
        private const val MAX_BATCHES_PER_PUSH = 10
        private const val MAX_QUEUE_SIZE = 5000
        private const val MAX_QUEUE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val EXTRAS_KEY_LOW_ACCURACY_FALLBACK = "low_accuracy_fallback"
        private const val EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER = "fallback_source_provider"
        private const val FALLBACK_PROVIDER_PREFIX = "low_accuracy_fallback:"
        private const val FALLBACK_REJECT_SUMMARY_INTERVAL_MS = 30_000L
        private const val FAST_GPS_LOCK_INTERVAL_MS = 1_000L
        private const val FAST_GPS_LOCK_MIN_UPDATE_INTERVAL_MS = 500L
        private const val FAST_GPS_LOCK_MIN_DISTANCE_METERS = 0f
        private const val FAST_GPS_LOCK_WINDOW_MS = 60_000L
        private const val FAST_GPS_LOCK_SUMMARY_INTERVAL_MS = 30_000L

        @JvmStatic
        fun shouldRestartTrackingAfterProcessDeath(
            wasTrackingBeforeExit: Boolean,
            restartTrackingIfKilled: Boolean
        ): Boolean {
            return wasTrackingBeforeExit && restartTrackingIfKilled
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

        @JvmStatic
        internal fun resolveLowAccuracyFallbackTimeoutMs(timeoutSec: Long): Long {
            val clampedTimeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(timeoutSec)
            return clampedTimeoutSec * 1000L
        }

        @JvmStatic
        internal fun shouldStartFastGpsLock(
            fastGpsLockEnabled: Boolean,
            rejectReason: TrackPointRejectReason?,
            measuredAccuracyMeters: Float?,
            accuracyFilterMeters: Float
        ): Boolean {
            if (!fastGpsLockEnabled) return false
            val measuredAccuracy = measuredAccuracyMeters ?: return true
            if (rejectReason != TrackPointRejectReason.BAD_ACCURACY) return false
            return measuredAccuracy > accuracyFilterMeters
        }

    }

    private var isTracking = false
    private lateinit var unifiedLocationClient: UnifiedLocationClient
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var database: AppDatabase
    private var httpClient: OkHttpClient? = null

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

    private var significantMotionBridge: SignificantMotionResumeBridge? = null
    private var isGpsPaused = false
    private var isWaitingForGpsLock = false
    private var consecutiveStationaryPoints = 0
    private var consecutiveBadAccuracyPoints = 0
    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0f
    private var sigMotionSensorStartTime = 0L
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null
    private var backlogUploaderJob: Job? = null
    private var preflightJob: Job? = null
    private var recoveryHeartbeatJob: Job? = null
    private val pushDispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val livePushSemaphore = Semaphore(2)
    private val backlogPushSemaphore = Semaphore(1)
    private val inFlightClaims = QueueInFlightClaimSet()
    private var sessionBoundaryForBacklogMs: Long = 0L
    private var controlState: TrackingControlState = TrackingControlState()
    private var consecutivePushFailures = 0
    private var lastSyncFailureClass = SyncFailureClass.NONE

    private val autoTrackingMotionEngine = AutoTrackingMotionEngine()
    private var lastSpeedReferenceLocation: Location? = null
    private var autoModeTickJob: Job? = null
    private var currentSettings: TrackerSettings = TrackerSettings()
    private var lowAccuracyFallbackJob: Job? = null
    private var lowAccuracyFallbackCandidate: Location? = null
    private val lowAccuracyFallbackCoordinator = LowAccuracyFallbackCoordinator()
    private var lowAccuracyFallbackTimerArmedAtMs: Long = 0L
    private var lowAccuracyFallbackEmitCountThisSession: Int = 0
    private var lowAccuracyFallbackArmCountThisSession: Int = 0
    private var lowAccuracyFallbackCancelCountThisSession: Int = 0
    private var lowAccuracyFallbackRejectedFixCountThisSession: Int = 0
    private var lowAccuracyFallbackLastRejectSummaryAtMs: Long = 0L
    private var fastGpsLockWindowJob: Job? = null
    private var isFastGpsLockWindowActive: Boolean = false
    private var fastGpsLockStartCountThisSession: Int = 0
    private var fastGpsLockStopCountThisSession: Int = 0
    private var fastGpsLockTimeoutCountThisSession: Int = 0
    private var fastGpsLockLastSummaryAtMs: Long = 0L
    private var isWaitingForGpsProvider: Boolean = false
    private var gpsProviderReceiverRegistered: Boolean = false
    private val gpsProviderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isTracking) return
            if (unifiedLocationClient.isGpsProviderEnabled()) {
                resumeFromGpsProviderWait(reason = "provider_broadcast")
            } else {
                enterWaitingForGpsProvider(reason = "provider_broadcast")
            }
        }
    }

    @Inject
    lateinit var settingsRepository: TrackerSettingsRepository

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        TrackingRecoveryCoordinator.markHeartbeat(applicationContext)
        database = AppDatabase.getDatabase(this)
        unifiedLocationClient = UnifiedLocationClient(this)
        currentSettings = settingsRepository.getSettings()
        autoTrackingMotionEngine.reset(System.currentTimeMillis())
        val significantMotionTrigger = SensorManagerSignificantMotionTrigger(applicationContext)
        significantMotionBridge = SignificantMotionResumeBridge(significantMotionTrigger) {
            Log.d(TAG, "Significant motion detected, resuming GPS")
            resumeGps()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                startTracking()
                TrackingRecoveryCoordinator.ensureWatchdogScheduled(applicationContext)
                START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received", Exception("ACTION_STOP stacktrace"))
                stopTracking(reason = "action_stop")
                START_NOT_STICKY
            }
            null -> {
                val wasTrackingBeforeExit = settingsRepository.wasTrackingBeforeExit()
                val restartIfKilled = currentSettings.resetTrackingIfKilled
                val shouldRestart = shouldRestartTrackingAfterProcessDeath(
                    wasTrackingBeforeExit = wasTrackingBeforeExit,
                    restartTrackingIfKilled = restartIfKilled
                )
                if (shouldRestart) {
                    startTracking()
                    TrackingRecoveryCoordinator.ensureWatchdogScheduled(applicationContext)
                    START_STICKY
                } else {
                    TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = "restart_not_required")
                    stopSelf()
                    START_NOT_STICKY
                }
            }
            ACTION_RESHOW_FOREGROUND -> {
                if (isTracking) {
                    serviceScope.launch {
                        val count = database.locationDao().getCurrentSessionCount(sessionBoundaryForBacklogMs)
                        withContext(Dispatchers.Main) {
                            startForeground(
                                NOTIFICATION_ID,
                                createNotification(pointsSentThisSession, count),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            )
                        }
                    }
                }
                START_STICKY
            }
            else -> {
                TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = "unknown_action")
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    private fun startTracking() {
        if (isTracking) return
        currentSettings = settingsRepository.getSettings()
        transitionControlState(TrackingControlEvent.StartRequested)
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (!hasValidSelectedTrackerId(selectedTrackerId)) {
            failStartup(getString(R.string.no_tracker_selected_go_to_settings))
            return
        }
        if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(this)) {
            failStartup(getString(R.string.location_permissions_required))
            return
        }
        if (!unifiedLocationClient.isGpsProviderEnabled()) {
            failStartup(getString(R.string.gps_provider_required))
            return
        }
        isTracking = true
        isRunning = true
        transitionControlState(TrackingControlEvent.StartSucceeded)
        Log.d(TAG, "Starting tracking")
        settingsRepository.setWasTrackingBeforeExit(true)
        TrackingRecoveryCoordinator.markTrackingStarted(applicationContext)
        startRecoveryHeartbeat()

        sessionStartTimeMs = System.currentTimeMillis()
        sessionBoundaryForBacklogMs = sessionStartTimeMs
        pointsSentThisSession = 0
        lastPointSentAtMs = 0
        totalDistanceMeters = 0f
        sessionTotalDistanceMeters = 0f
        lastAccuracyMeters = null
        lastTrackedLatitude = null
        lastTrackedLongitude = null
        lastTrackedTimestampMs = 0L
        lastTrackedPropsJson = null
        consecutivePushFailures = 0
        lastSyncFailureClass = SyncFailureClass.NONE
        lowAccuracyFallbackTimerArmedAtMs = 0L
        lowAccuracyFallbackEmitCountThisSession = 0
        lowAccuracyFallbackArmCountThisSession = 0
        lowAccuracyFallbackCancelCountThisSession = 0
        lowAccuracyFallbackRejectedFixCountThisSession = 0
        lowAccuracyFallbackLastRejectSummaryAtMs = 0L
        fastGpsLockStartCountThisSession = 0
        fastGpsLockStopCountThisSession = 0
        fastGpsLockTimeoutCountThisSession = 0
        fastGpsLockLastSummaryAtMs = 0L
        isWaitingForGpsProvider = false
        stopFastGpsLockWindow(reason = "session_reset")
        TrackPointPipeline.resetLocalSession(selectedTrackerId)
        syncRuntimeStateStore()
        broadcastSessionStats()

        startForeground(NOTIFICATION_ID, createNotification(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        isGpsPaused = false
        isWaitingForGpsProvider = false
        isWaitingForGpsLock = false
        consecutiveStationaryPoints = 0
        consecutiveBadAccuracyPoints = 0
        lastLocation = null
        lastSpeedReferenceLocation = null
        if (currentSettings.autoTrackingMode) {
            autoTrackingMotionEngine.reset(System.currentTimeMillis())
            startAutoModeTickIfNeeded()
        } else {
            stopAutoModeTick()
        }
        if (!applyCurrentLocationRequest("start_tracking")) {
            failActiveTracking(getString(R.string.unable_to_start_location_updates))
            return
        }
        maybeStartFastGpsLockWindow(
            rejectReason = null,
            measuredAccuracyMeters = null
        )
        
        // Push any existing queued locations immediately when tracking starts (both
        // current-session and backlog lanes) so startup drain is not delayed.
        serviceScope.launch {
            lastSyncFailureClass = pushLocations(
                scope = QueueUploadScope.ALL,
                sessionBoundaryMs = sessionBoundaryForBacklogMs
            )
        }
        startBacklogUploader(sessionBoundaryForBacklogMs)
        
        // Start periodic retry job to push failed locations every minute
        startRetryJob()
        startPreflightMonitor()
        ensureGpsProviderReceiverRegistered()
    }

    private fun stopTracking(reason: String = "tracking_stopped") {
        if (!isTracking) return
        transitionControlState(TrackingControlEvent.StopRequested)
        Log.d(TAG, "Stopping tracking")
        isTracking = false
        isRunning = false
        sessionStartTimeMs = 0
        lastTrackedLatitude = null
        lastTrackedLongitude = null
        lastTrackedTimestampMs = 0L
        lastTrackedPropsJson = null
        syncRuntimeStateStore()
        settingsRepository.clearWasTrackingBeforeExit()
        stopRecoveryHeartbeat()
        TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = reason)
        isWaitingForGpsProvider = false
        cancelLowAccuracyFallbackTimer(clearCandidate = true, reason = "stop_tracking")
        stopFastGpsLockWindow(reason = "stop_tracking")
        unifiedLocationClient.stopSession()
        significantMotionBridge?.cancel()
        stopAutoModeTick()
        stopPreflightMonitor()
        unregisterGpsProviderReceiverIfNeeded()
        stopRetryJob()
        backlogUploaderJob?.cancel()
        backlogUploaderJob = null
        broadcastSessionStats()
        stopForeground(STOP_FOREGROUND_REMOVE)
        transitionControlState(TrackingControlEvent.StopCompleted)
        stopSelf()
    }

    private fun onLocationReceived(location: Location) {
        if (!unifiedLocationClient.isGpsProviderEnabled()) {
            enterWaitingForGpsProvider(reason = "location_callback")
            return
        }
        if (isWaitingForGpsProvider) {
            resumeFromGpsProviderWait(reason = "location_callback")
        }
        // Always update last accuracy from the most recent fix so the UI shows current GPS fix quality
        lastAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        syncRuntimeStateStore()

        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (selectedTrackerId.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val observedSpeedMps = resolveObservedSpeedMps(location, lastSpeedReferenceLocation)
        val isMockLocation = LocationCompat.isMock(location)
        val decision = TrackPointPipeline.processLocalGps(
            event = TrackPointEvent(
                source = TrackPointSource.LOCAL_GPS,
                trackId = selectedTrackerId,
                lon = location.longitude,
                lat = location.latitude,
                timestampMs = location.time,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
            ),
            maxAccuracyMeters = resolveCurrentAccuracyFilter(),
            freshnessTtlMs = 120_000L,
            isMockLocation = isMockLocation,
            nowMs = nowMs
        )
        if (!decision.accepted || decision.canonicalEvent == null) {
            Log.d(
                TAG,
                "Dropped location reason=${decision.rejectReason} provider=${location.provider} " +
                    "isMock=$isMockLocation rawTs=${location.time} nowMs=$nowMs " +
                    "acc=${if (location.hasAccuracy()) location.accuracy else null}"
            )
            if (decision.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
                decision.rejectReason == TrackPointRejectReason.STALE
            ) {
                consecutiveBadAccuracyPoints++
                onRejectedFixAwaitingLock(location)
                maybeStartFastGpsLockWindow(
                    rejectReason = decision.rejectReason,
                    measuredAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
                )
                maybeLogFastGpsLockSummary(
                    rejectReason = decision.rejectReason,
                    measuredAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
                )
                if (consecutiveBadAccuracyPoints >= 3) {
                    isWaitingForGpsLock = true
                    updateNotificationCount()
                }
            }
            if (currentSettings.autoTrackingMode) {
                processAutoTrackingOutput(
                    autoTrackingMotionEngine.onRejectedFix(
                        speedMpsHint = observedSpeedMps,
                        eventTimeMs = nowMs
                    ),
                    reason = "rejected_fix"
                )
            }
            lastSpeedReferenceLocation = Location(location)
            broadcastSessionStats()
            return
        }

        onAcceptedFixWithLock()
        val canonicalEvent = decision.canonicalEvent ?: return
        val smoothedLocation = Location(location).apply {
            latitude = canonicalEvent.lat
            longitude = canonicalEvent.lon
            time = canonicalEvent.timestampMs
            canonicalEvent.accuracyMeters?.let { accuracy = it }
        }
        
        Log.d(TAG, "Location received: ${smoothedLocation.latitude}, ${smoothedLocation.longitude}")
        val sigMotionOnly = currentSettings.significantDataOnly
        val (_, distanceFilter, _) = resolveCurrentProfileParams()

        // Speed-Aware Stationary: Trust hardware speed attributes to avoid false pauses
        val (newConsecutive, shouldPause) = TrackingLocationPolicy.stationaryUpdate(
            lastLocation, smoothedLocation, distanceFilter, consecutiveStationaryPoints, sigMotionOnly
        )
        consecutiveStationaryPoints = newConsecutive
        if (newConsecutive > 0) Log.d(TAG, "Stationary point count: $consecutiveStationaryPoints")
        if (shouldPause) {
            Log.d(TAG, "User stationary for 3 points, pausing GPS")
            pauseGps()
        }
        
        totalDistanceMeters += lastLocation?.distanceTo(smoothedLocation) ?: 0f
        sessionTotalDistanceMeters = totalDistanceMeters
        syncRuntimeStateStore()
        
        // Auto-mode transition logic
        if (currentSettings.autoTrackingMode) {
            processAutoTrackingOutput(
                autoTrackingMotionEngine.onAcceptedFix(
                    speedMps = observedSpeedMps ?: 0f,
                    eventTimeMs = nowMs
                ),
                reason = "accepted_fix"
            )
        }

        lastLocation = smoothedLocation
        lastSpeedReferenceLocation = Location(location)

        broadcastTrackPoint(smoothedLocation, canonicalEvent)
        enqueueAndPushLocation(smoothedLocation, totalDistanceMeters)
    }

    private suspend fun pushLocations(
        scope: QueueUploadScope = QueueUploadScope.ALL,
        sessionBoundaryMs: Long = sessionBoundaryForBacklogMs
    ): SyncFailureClass {
        return withContext(pushDispatcher) {
            var liveAcquired = false
            var backlogAcquired = false
            val lockAcquired = when (scope) {
                QueueUploadScope.LIVE_ONLY -> {
                    liveAcquired = livePushSemaphore.tryAcquire()
                    liveAcquired
                }
                QueueUploadScope.BACKLOG_ONLY -> {
                    backlogAcquired = backlogPushSemaphore.tryAcquire()
                    backlogAcquired
                }
                QueueUploadScope.ALL -> {
                    liveAcquired = livePushSemaphore.tryAcquire()
                    if (!liveAcquired) {
                        false
                    } else {
                        backlogAcquired = backlogPushSemaphore.tryAcquire()
                        if (!backlogAcquired) {
                            livePushSemaphore.release()
                            liveAcquired = false
                            false
                        } else {
                            true
                        }
                    }
                }
            }
            if (!lockAcquired) {
                Log.d(TAG, "Push already in progress for scope=$scope, skipping")
                return@withContext SyncFailureClass.TRANSIENT
            }

            try {
            trimQueuedLocationsRetention()
            if (!NetworkStatusMonitor.hasUsableNetwork(this@TrackingService)) {
                updateNotificationCount()
                return@withContext SyncFailureClass.TRANSIENT
            }
            val trackerIdStr = SelectedTrackerPrefs.selectedTrackerId(this@TrackingService)
            if (trackerIdStr.isEmpty()) {
                Log.e(TAG, "No tracker selected, cannot push locations")
                broadcastTrackingError(getString(R.string.no_tracker_selected_go_to_settings))
                updateNotificationCount()
                return@withContext SyncFailureClass.PERMANENT
            }
            val trackerId = try {
                java.util.UUID.fromString(trackerIdStr)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid selected tracker id, cannot push locations", e)
                broadcastTrackingError(getString(R.string.tracker_validation_failed_go_to_settings))
                updateNotificationCount()
                return@withContext SyncFailureClass.PERMANENT
            }

            val serverUrl = GeovaultAuthManager.getServerUrl(this@TrackingService)
            if (serverUrl.isEmpty()) {
                updateNotificationCount()
                return@withContext SyncFailureClass.PERMANENT
            }

            val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            val ingressUrl = "${baseUrl}api/extensions/live-track/app-ingress/"

            var batchesSent = 0
            val useExtendedParams = currentSettings.sendExtendedData
            
            // Fetch these once per push to avoid high-cost IPC calls in loop
            val (batteryLevel, isCharging) = if (useExtendedParams) getBatteryStatus() else Pair(0, false)
            val buildSerial = if (useExtendedParams) getBuildSerial() else ""
            val sessionStart = sessionStartTimeMs

            while (batchesSent < MAX_BATCHES_PER_PUSH) {
                val batch = claimNextBatch(
                    scope = scope,
                    sessionBoundaryMs = sessionBoundaryMs,
                    limit = 50
                )
                if (batch.isEmpty()) break
                val payload = if (useExtendedParams) {
                    BinaryPayloadBuilder.buildPayload(
                        batch,
                        trackerId,
                        sessionStart,
                        batteryLevel,
                        isCharging,
                        buildSerial
                    )
                } else {
                    BinaryPayloadBuilder.buildPayloadMinimal(batch, trackerId)
                }
                val compressedBody = gzipCompress(payload)
                val requestBody = compressedBody.toRequestBody("application/octet-stream".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url(ingressUrl)
                    .addHeader("Content-Encoding", "gzip")
                    .post(requestBody)
                    .build()

                try {
                    val response = getAuthenticatedHttpClient().newCall(request).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully pushed ${batch.size} locations")
                        val normalizedSessionStart = if (sessionStart > 0L) {
                            CanonicalTimeNormalizer.normalizeTimestampMs(sessionStart, sessionStart)
                        } else {
                            0L
                        }
                        val sentThisSession = if (normalizedSessionStart > 0L) {
                            batch.count { queued ->
                                CanonicalTimeNormalizer.normalizeTimestampMs(queued.time, normalizedSessionStart) >= normalizedSessionStart
                            }
                        } else {
                            batch.size
                        }
                        pointsSentThisSession += sentThisSession
                        lastPointSentAtMs = System.currentTimeMillis()
                        syncRuntimeStateStore()
                        broadcastSessionStats()
                        withContext(NonCancellable) {
                            try {
                                database.locationDao().delete(batch)
                            } finally {
                                releaseClaimedBatch(batch)
                            }
                        }
                        batchesSent++
                    } else {
                        releaseClaimedBatch(batch)
                        Log.e(TAG, "Failed to push locations: ${response.code} ${response.message}")
                        if (response.code in 400..499) {
                            return@withContext SyncFailureClass.PERMANENT
                        }
                        break
                    }
                } catch (e: Exception) {
                    releaseClaimedBatch(batch)
                    Log.e(TAG, "Exception pushing locations", e)
                    break
                }
            }

            updateNotificationCount()
            trimQueuedLocationsRetention()
            return@withContext if (batchesSent > 0) SyncFailureClass.NONE else SyncFailureClass.TRANSIENT
            } finally {
                if (backlogAcquired) backlogPushSemaphore.release()
                if (liveAcquired) livePushSemaphore.release()
            }
        }
    }

    private suspend fun claimNextBatch(
        scope: QueueUploadScope,
        sessionBoundaryMs: Long,
        limit: Int
    ): List<QueuedLocation> {
        val candidates = when (scope) {
            QueueUploadScope.BACKLOG_ONLY -> database.locationDao().getOldestBacklog(sessionBoundaryMs, limit * 3)
            QueueUploadScope.LIVE_ONLY -> database.locationDao().getOldestCurrentSession(sessionBoundaryMs, limit * 3)
            QueueUploadScope.ALL -> database.locationDao().getOldest(limit * 3)
        }
        if (candidates.isEmpty()) return emptyList()
        return inFlightClaims.claim(candidates, limit)
    }

    private suspend fun releaseClaimedBatch(batch: List<QueuedLocation>) {
        inFlightClaims.release(batch)
    }

    private fun broadcastSessionStats() {
        val intent = Intent(SESSION_STATS_UPDATE).apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun updateNotificationCount() {
        serviceScope.launch {
            val count = database.locationDao().getCurrentSessionCount(sessionBoundaryForBacklogMs)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(pointsSentThisSession, count))
        }
    }

    private fun broadcastTrackPoint(location: Location, canonicalEvent: TrackPointEvent? = null) {
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (trackerId.isEmpty()) return
        val propsJson = buildLocalPointPropsJson(location, totalDistanceMeters)
        lastTrackedLatitude = location.latitude
        lastTrackedLongitude = location.longitude
        lastTrackedTimestampMs = location.time
        lastTrackedPropsJson = propsJson
        syncRuntimeStateStore()
        val point = StreamingTrackPoint(
            trackId = trackerId,
            lon = location.longitude,
            lat = location.latitude,
            timestampMs = location.time,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            propsJson = propsJson
        )
        publishTrackPoint(
            source = TrackPointSource.LOCAL_GPS,
            trackId = point.trackId,
            lon = point.lon,
            lat = point.lat,
            timestampMs = point.timestampMs,
            accuracyMeters = point.accuracyMeters,
            propsJson = point.propsJson,
            quality = canonicalEvent?.quality ?: TrackPointQuality.HIGH_CONFIDENCE,
            orderingKey = canonicalEvent?.orderingKey ?: 0L
        )
    }

    private fun buildLocalPointPropsJson(location: Location, distanceMeters: Float): String? {
        val useExtendedParams = currentSettings.sendExtendedData
        if (!useExtendedParams) return null
        return try {
            val props = JSONObject()
            val timestampMs = location.time
            val timestampSec = if (timestampMs >= 1_000_000_000_000L) timestampMs / 1000L else timestampMs
            props.put("timestamp", timestampSec)
            props.put("starttimestamp", sessionStartTimeMs)
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
            val sat = location.extras?.getInt("satellites", 0)?.takeIf { it > 0 }
            if (sat != null) props.put("sat", sat)
            val (batteryLevel, isCharging) = getBatteryStatus()
            props.put("batt", batteryLevel)
            props.put("ischarging", isCharging)
            props.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build extended tracking point payload", e)
            null
        }
    }

    private fun syncRuntimeStateStore() {
        TrackingRuntimeStateStore.update {
            it.copy(
                isRunning = isRunning,
                lifecycleState = controlState.lifecycleState,
                failureReason = controlState.failureReason,
                gpsProviderEnabled = unifiedLocationClient.isGpsProviderEnabled(),
                autoTrackingEnabled = currentSettings.autoTrackingMode,
                activeMotionMode = resolveRuntimeMotionMode(),
                sessionStartTimeMs = sessionStartTimeMs,
                pointsSentThisSession = pointsSentThisSession,
                lastPointSentAtMs = lastPointSentAtMs,
                sessionTotalDistanceMeters = sessionTotalDistanceMeters,
                lastAccuracyMeters = lastAccuracyMeters,
                lastTrackedLatitude = lastTrackedLatitude,
                lastTrackedLongitude = lastTrackedLongitude,
                lastTrackedTimestampMs = lastTrackedTimestampMs,
                lastTrackedPropsJson = lastTrackedPropsJson
            )
        }
    }

    private fun createNotification(sentCount: Int, queuedCount: Int): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val dismissIntent = Intent(NOTIFICATION_DISMISSED_ACTION).apply { setPackage(packageName) }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val noGoodFix = lastAccuracyMeters == null || lastAccuracyMeters!! > resolveCurrentAccuracyFilter()
        val status = when {
            isWaitingForGpsProvider -> getString(R.string.status_waiting_for_gps_reenabled)
            isGpsPaused -> getString(R.string.status_tracking)
            noGoodFix -> getString(R.string.locking)
            else -> getString(R.string.status_tracking)
        }
        val counts = getString(R.string.stat_label_sent_queued, sentCount, queuedCount)
        val text = "$status\n$counts"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.live_tracker_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, getString(R.string.stop_tracking), stopPendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SYSTEM)  // Helps Samsung show under "Silent" / minimized
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSortKey("\uFFFF")
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(dismissPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called isTracking=$isTracking", Exception("onDestroy stacktrace"))
        if (isTracking) {
            TrackingRecoveryCoordinator.markUnexpectedDestroy(applicationContext, wasTracking = true)
        }
        stopRecoveryHeartbeat()
        super.onDestroy()
        significantMotionBridge?.cancel()
        stopAutoModeTick()
        stopPreflightMonitor()
        unregisterGpsProviderReceiverIfNeeded()
        stopRetryJob()
        cancelLowAccuracyFallbackTimer(clearCandidate = true, reason = "service_destroyed")
        serviceScope.cancel()
        pushDispatcher.close()
    }

    private fun startRecoveryHeartbeat() {
        recoveryHeartbeatJob?.cancel()
        recoveryHeartbeatJob = serviceScope.launch {
            while (isActive && isTracking) {
                TrackingRecoveryCoordinator.markHeartbeat(applicationContext)
                delay(1_000L)
            }
        }
    }

    private fun stopRecoveryHeartbeat() {
        recoveryHeartbeatJob?.cancel()
        recoveryHeartbeatJob = null
    }
    
    private fun startRetryJob() {
        retryJob?.cancel()
        retryJob = serviceScope.launch {
            while (isActive && isTracking) {
                val baseDelay = TrackingSyncPolicy.nextRetryDelayMs(
                    consecutiveFailures = consecutivePushFailures,
                    failureClass = lastSyncFailureClass
                )
                val jitter = Random.nextLong(-RETRY_JITTER_MS, RETRY_JITTER_MS + 1)
                delay((baseDelay + jitter).coerceAtLeast(5_000L))
                val count = database.locationDao().getCount()
                if (count > 0) {
                    Log.d(TAG, "Retry job: attempting to push $count queued locations")
                    val outcome = pushLocations(
                        scope = QueueUploadScope.LIVE_ONLY,
                        sessionBoundaryMs = sessionBoundaryForBacklogMs
                    )
                    lastSyncFailureClass = outcome
                    if (outcome == SyncFailureClass.NONE) {
                        consecutivePushFailures = 0
                    } else {
                        consecutivePushFailures++
                    }
                }
            }
        }
    }

    private fun startBacklogUploader(sessionBoundaryMs: Long) {
        backlogUploaderJob?.cancel()
        backlogUploaderJob = serviceScope.launch {
            while (isActive && isTracking) {
                val backlogCount = database.locationDao().getBacklogCount(sessionBoundaryMs)
                if (backlogCount > 0) {
                    Log.d(TAG, "Backlog uploader: attempting to push $backlogCount queued backlog points")
                    pushLocations(
                        scope = QueueUploadScope.BACKLOG_ONLY,
                        sessionBoundaryMs = sessionBoundaryMs
                    )
                    delay(5_000L)
                } else {
                    // Keep background uploader alive for the full tracking session.
                    // Late-arriving points can still fall into backlog lane (e.g. stale fix timestamps).
                    delay(30_000L)
                }
            }
        }
    }
    
    private fun stopRetryJob() {
        retryJob?.cancel()
        retryJob = null
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
        try {
            unregisterReceiver(gpsProviderReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver may already be unregistered during lifecycle teardown races.
        }
        gpsProviderReceiverRegistered = false
    }

    private fun startPreflightMonitor() {
        preflightJob?.cancel()
        preflightJob = serviceScope.launch {
            while (isActive && isTracking) {
                delay(20_000L)
                if (!TrackingPermissionGate.hasRequiredPermissionsForTracking(this@TrackingService)) {
                    withContext(Dispatchers.Main) {
                        failActiveTracking(getString(R.string.location_permission_revoked))
                    }
                    return@launch
                }
                if (!unifiedLocationClient.isGpsProviderEnabled()) {
                    withContext(Dispatchers.Main) {
                        enterWaitingForGpsProvider(reason = "preflight_monitor")
                    }
                    continue
                }
                if (isWaitingForGpsProvider) {
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
        if (!isTracking || isWaitingForGpsProvider) return
        isWaitingForGpsProvider = true
        stopFastGpsLockWindow(reason = "gps_provider_disabled")
        cancelLowAccuracyFallbackTimer(clearCandidate = true, reason = "gps_provider_disabled")
        unifiedLocationClient.stopSession()
        Log.w(TAG, "GPS provider disabled while tracking; waiting for re-enable reason=$reason")
        syncRuntimeStateStore()
        updateNotificationCount()
        broadcastSessionStats()
    }

    private fun resumeFromGpsProviderWait(reason: String) {
        if (!isTracking || !isWaitingForGpsProvider) return
        isWaitingForGpsProvider = false
        if (isGpsPaused) {
            Log.i(TAG, "GPS provider re-enabled while paused reason=$reason")
            syncRuntimeStateStore()
            updateNotificationCount()
            broadcastSessionStats()
            return
        }
        if (!applyCurrentLocationRequest("gps_provider_reenabled_$reason")) {
            failActiveTracking(getString(R.string.location_permission_revoked))
            return
        }
        Log.i(TAG, "GPS provider re-enabled, resumed location updates reason=$reason")
        syncRuntimeStateStore()
        updateNotificationCount()
        broadcastSessionStats()
    }

    private fun pauseGps() {
        if (!isGpsPaused && isTracking) {
            isGpsPaused = true
            if (currentSettings.autoTrackingMode) {
                autoTrackingMotionEngine.onGpsPaused(System.currentTimeMillis())
            }
            stopFastGpsLockWindow(reason = "gps_paused")
            unifiedLocationClient.stopSession()
            significantMotionBridge?.request()
            sigMotionSensorStartTime = System.currentTimeMillis()
            startSensorWatchdog()
            syncRuntimeStateStore()
            updateNotificationCount()
        }
    }

    private fun startSensorWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive && isGpsPaused) {
                delay(60000) // Check every minute
                val age = System.currentTimeMillis() - sigMotionSensorStartTime
                if (age > 5 * 60 * 1000) { // 5 minutes (gpslogger R&D wisdom)
                    Log.d(TAG, "Significant motion sensor is $age ms old, resetting to prevent staleness")
                    significantMotionBridge?.cancel()
                    significantMotionBridge?.request()
                    sigMotionSensorStartTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun resumeGps() {
        if (isGpsPaused && isTracking) {
            isGpsPaused = false
            isWaitingForGpsLock = false
            consecutiveStationaryPoints = 0
            stopFastGpsLockWindow(reason = "gps_resumed")
            cancelLowAccuracyFallbackTimer(clearCandidate = false, reason = "gps_resumed")
            if (currentSettings.autoTrackingMode) {
                autoTrackingMotionEngine.onGpsResumed(System.currentTimeMillis())
            }
            watchdogJob?.cancel()

            if (!applyCurrentLocationRequest("resume_gps")) {
                failActiveTracking(getString(R.string.location_permission_revoked))
                return
            }
            syncRuntimeStateStore()
            updateNotificationCount()
        }
    }

    private fun getBatteryStatus(): Pair<Int, Boolean> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val batteryPct = if (scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return batteryPct to isCharging
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(data)
            }
            return baos.toByteArray()
        }
    }

    private fun getBuildSerial(): String {
        return try {
            Build.getSerial() ?: ""
        } catch (e: SecurityException) {
            ""
        }
    }

    /**
     * Single source of truth for effective tracking params. When auto mode is on,
     * returns the current motion profile's (interval, distance, accuracy); otherwise
     * returns stored settings. Use this everywhere the service needs interval, distance,
     * or accuracy so behavior is consistent and auto mode correctly adjusts all three.
     */
    private fun resolveCurrentProfileParams(): Triple<Long, Float, Float> {
        if (currentSettings.autoTrackingMode) {
            val mode = autoTrackingMotionEngine.snapshot().mode
            return TrackingLocationPolicy.getProfileParams(mode.profileIndex)
        }
        return Triple(
            currentSettings.loggingIntervalSec,
            currentSettings.distanceFilterMeters,
            currentSettings.accuracyFilterMeters
        )
    }

    private fun resolveCurrentIntervalAndDistance(): Pair<Long, Float> {
        val (interval, distance, _) = resolveCurrentProfileParams()
        return interval to distance
    }

    private fun resolveCurrentAccuracyFilter(): Float =
        resolveCurrentProfileParams().third

    private fun buildLocationRequest(intervalSec: Long, distanceFilter: Float): LocationRequest {
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(intervalSec)
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(distanceFilter)
            .setMinUpdateIntervalMillis(minUpdateMs)
            .build()
    }

    private fun buildFastGpsLockLocationRequest(): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FAST_GPS_LOCK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FAST_GPS_LOCK_MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(FAST_GPS_LOCK_MIN_DISTANCE_METERS)
            .setWaitForAccurateLocation(true)
            .build()
    }

    private fun applyCurrentLocationRequest(reason: String): Boolean {
        if (!isTracking) return false
        if (!unifiedLocationClient.hasLocationPermission()) {
            return false
        }
        val (intervalSec, distanceFilter) = resolveCurrentIntervalAndDistance()
        val request = if (isFastGpsLockWindowActive) {
            buildFastGpsLockLocationRequest()
        } else {
            buildLocationRequest(intervalSec, distanceFilter)
        }
        val started = unifiedLocationClient.startSession(
            sessionRequest = UnifiedLocationSessionRequest(request),
            onLocation = ::onLocationReceived,
            onError = { error ->
                Log.e(TAG, "Failed to apply location request ($reason)", error)
            }
        )
        if (!started) return false
        val accuracyFilter = resolveCurrentAccuracyFilter()
        Log.d(
            TAG,
            "Applied LocationRequest ($reason): interval=${intervalSec}s, distance=${distanceFilter}m, accuracy=${accuracyFilter}m, mode=${autoTrackingMotionEngine.snapshot().mode}, auto=${currentSettings.autoTrackingMode}, fastLock=$isFastGpsLockWindowActive"
        )
        return true
    }

    private fun reapplyLocationRequestIfActive(reason: String) {
        if (!isTracking || isGpsPaused) return
        val applied = applyCurrentLocationRequest(reason)
        if (!applied) {
            failActiveTracking(getString(R.string.location_permission_revoked))
        }
    }

    private fun startAutoModeTickIfNeeded() {
        if (!isTracking || !currentSettings.autoTrackingMode) return
        if (autoModeTickJob?.isActive == true) return
        autoModeTickJob = serviceScope.launch {
            while (isActive && isTracking && currentSettings.autoTrackingMode) {
                delay(5_000L)
                processAutoTrackingOutput(
                    autoTrackingMotionEngine.onTick(System.currentTimeMillis()),
                    reason = "periodic_decay_tick"
                )
            }
        }
    }

    private fun stopAutoModeTick() {
        autoModeTickJob?.cancel()
        autoModeTickJob = null
    }

    private fun processAutoTrackingOutput(
        output: com.geovault.tracker.location.AutoTrackingEngineOutput,
        reason: String
    ) {
        if (output.modeChanged) {
            Log.d(
                TAG,
                "Auto-mode transition ($reason): mode=${output.state.mode}, speed=${output.state.smoothedSpeedMps}m/s"
            )
            reapplyLocationRequestIfActive("auto_mode_$reason")
        }
        syncRuntimeStateStore()
    }

    private fun resolveRuntimeMotionMode(): TrackingMotionMode {
        if (currentSettings.autoTrackingMode) {
            return autoTrackingMotionEngine.snapshot().mode
        }
        return when (currentSettings.trackingProfile.index) {
            0 -> TrackingMotionMode.WALKING
            1 -> TrackingMotionMode.BIKING
            2 -> TrackingMotionMode.DRIVING
            else -> TrackingMotionMode.BIKING
        }
    }

    private fun resolveObservedSpeedMps(
        location: Location,
        referenceLocation: Location?
    ): Float? {
        if (location.hasSpeed()) {
            return location.speed.coerceAtLeast(0f)
        }
        val previous = referenceLocation ?: return null
        val elapsedSec = (location.time - previous.time) / 1000f
        if (elapsedSec <= 0f) return null
        val distanceMeters = previous.distanceTo(location)
        return (distanceMeters / elapsedSec).coerceAtLeast(0f)
    }

    private fun trimQueuedLocationsRetention() {
        val cutoff = System.currentTimeMillis() - MAX_QUEUE_AGE_MS
        database.locationDao().deleteOlderThan(cutoff)
        val count = database.locationDao().getCount()
        if (count > MAX_QUEUE_SIZE) {
            database.locationDao().deleteOldestCount(count - MAX_QUEUE_SIZE)
        }
    }

    private fun broadcastTrackingError(message: String) {
        sendBroadcast(
            Intent(ACTION_TRACKING_ERROR).apply {
                setPackage(packageName)
                putExtra(EXTRA_TRACKING_ERROR_MESSAGE, message)
            }
        )
    }

    private fun failActiveTracking(message: String) {
        Log.w(TAG, "Failing active tracking: $message")
        transitionControlState(TrackingControlEvent.FatalFailure, message)
        broadcastTrackingError(message)
        stopTracking(reason = "fatal_failure")
    }

    private fun failStartup(message: String) {
        Log.w(TAG, "Tracking start failed: $message")
        settingsRepository.clearWasTrackingBeforeExit()
        TrackingRecoveryCoordinator.markIntentionalStop(applicationContext, reason = "startup_failed")
        transitionControlState(TrackingControlEvent.StartFailed, message)
        syncRuntimeStateStore()
        broadcastTrackingError(message)
        stopSelf()
    }

    private fun transitionControlState(event: TrackingControlEvent, failureReason: String? = null) {
        controlState = TrackingControlPlane.transition(controlState, event, failureReason)
        syncRuntimeStateStore()
    }

    private fun maybeStartFastGpsLockWindow(
        rejectReason: TrackPointRejectReason?,
        measuredAccuracyMeters: Float?
    ) {
        if (!isTracking || isGpsPaused || isFastGpsLockWindowActive) return
        val accuracyFilterMeters = resolveCurrentAccuracyFilter()
        if (
            !shouldStartFastGpsLock(
                fastGpsLockEnabled = currentSettings.fastGpsLockEnabled,
                rejectReason = rejectReason,
                measuredAccuracyMeters = measuredAccuracyMeters,
                accuracyFilterMeters = accuracyFilterMeters
            )
        ) {
            return
        }

        isFastGpsLockWindowActive = true
        if (!applyCurrentLocationRequest("fast_gps_lock_start")) {
            isFastGpsLockWindowActive = false
            Log.w(
                TAG,
                "Fast GPS lock start failed rejectReason=$rejectReason measuredAcc=$measuredAccuracyMeters accuracyFilter=$accuracyFilterMeters"
            )
            failActiveTracking(getString(R.string.unable_to_start_location_updates))
            return
        }
        fastGpsLockStartCountThisSession++
        Log.i(
            TAG,
            "Fast GPS lock started rejectReason=$rejectReason acc=$measuredAccuracyMeters accuracyFilter=$accuracyFilterMeters windowMs=$FAST_GPS_LOCK_WINDOW_MS startsThisSession=$fastGpsLockStartCountThisSession"
        )
        fastGpsLockWindowJob?.cancel()
        fastGpsLockWindowJob = serviceScope.launch {
            delay(FAST_GPS_LOCK_WINDOW_MS)
            withContext(Dispatchers.Main) {
                stopFastGpsLockWindow(reason = "window_timeout")
            }
        }
    }

    private fun stopFastGpsLockWindow(reason: String) {
        val wasActive = isFastGpsLockWindowActive
        fastGpsLockWindowJob?.cancel()
        fastGpsLockWindowJob = null
        isFastGpsLockWindowActive = false
        if (!wasActive || !isTracking || isGpsPaused) return
        fastGpsLockStopCountThisSession++
        if (reason == "window_timeout") {
            fastGpsLockTimeoutCountThisSession++
        }
        if (!applyCurrentLocationRequest("fast_gps_lock_stop_$reason")) {
            Log.w(TAG, "Fast GPS lock stop failed reason=$reason")
            failActiveTracking(getString(R.string.location_permission_revoked))
            return
        }
        Log.i(
            TAG,
            "Fast GPS lock stopped reason=$reason stopsThisSession=$fastGpsLockStopCountThisSession timeoutsThisSession=$fastGpsLockTimeoutCountThisSession"
        )
    }

    private fun maybeLogFastGpsLockSummary(
        rejectReason: TrackPointRejectReason?,
        measuredAccuracyMeters: Float?
    ) {
        if (rejectReason != TrackPointRejectReason.BAD_ACCURACY) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - fastGpsLockLastSummaryAtMs < FAST_GPS_LOCK_SUMMARY_INTERVAL_MS) return
        fastGpsLockLastSummaryAtMs = nowMs
        val accuracyFilterMeters = resolveCurrentAccuracyFilter()
        Log.d(
            TAG,
            "Fast GPS lock summary active=$isFastGpsLockWindowActive enabled=${currentSettings.fastGpsLockEnabled} measuredAcc=$measuredAccuracyMeters accuracyFilter=$accuracyFilterMeters startsThisSession=$fastGpsLockStartCountThisSession stopsThisSession=$fastGpsLockStopCountThisSession timeoutsThisSession=$fastGpsLockTimeoutCountThisSession"
        )
    }

    private fun onRejectedFixAwaitingLock(location: Location) {
        if (!currentSettings.lowAccuracyFallbackEnabled || isGpsPaused || !isTracking) return
        lowAccuracyFallbackRejectedFixCountThisSession++
        lowAccuracyFallbackCandidate = Location(location)
        val shouldStartTimer = lowAccuracyFallbackCoordinator.onRejectedFixForLock(
            fallbackEligible = true
        )
        if (shouldStartTimer) {
            lowAccuracyFallbackArmCountThisSession++
            ensureLowAccuracyFallbackTimerRunning()
        }
        maybeLogFallbackRejectSummary(
            provider = location.provider,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        )
    }

    private fun onAcceptedFixWithLock() {
        consecutiveBadAccuracyPoints = 0
        isWaitingForGpsLock = false
        lowAccuracyFallbackCoordinator.onAcceptedFix()
        stopFastGpsLockWindow(reason = "good_accuracy_fix")
        cancelLowAccuracyFallbackTimer(clearCandidate = true, reason = "lock_recovered")
    }

    private fun ensureLowAccuracyFallbackTimerRunning() {
        if (lowAccuracyFallbackJob?.isActive == true) return
        lowAccuracyFallbackTimerArmedAtMs = System.currentTimeMillis()
        Log.i(
            TAG,
            "Low-accuracy fallback timer armed timeoutMs=${resolveLowAccuracyFallbackTimeoutMs()} " +
                "armCountThisSession=$lowAccuracyFallbackArmCountThisSession"
        )
        lowAccuracyFallbackJob = serviceScope.launch {
            while (isActive && isTracking) {
                delay(resolveLowAccuracyFallbackTimeoutMs())
                if (!emitLowAccuracyFallbackPointIfNeeded()) {
                    break
                }
            }
            Log.d(TAG, "Low-accuracy fallback timer loop exited isTracking=$isTracking")
            lowAccuracyFallbackJob = null
        }
    }

    private fun resolveLowAccuracyFallbackTimeoutMs(): Long {
        return resolveLowAccuracyFallbackTimeoutMs(currentSettings.lowAccuracyFallbackTimeoutSec)
    }

    private fun emitLowAccuracyFallbackPointIfNeeded(): Boolean {
        if (!isTracking || isGpsPaused || !currentSettings.lowAccuracyFallbackEnabled) return false
        val candidate = lowAccuracyFallbackCandidate ?: return false
        if (
            !lowAccuracyFallbackCoordinator.shouldEmitFallback(
                fallbackEligible = true,
                hasCandidate = true
            )
        ) {
            return false
        }
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (trackerId.isEmpty()) return false
        val fallbackTimeMs = System.currentTimeMillis()
        val waitingDurationMs = (fallbackTimeMs - lowAccuracyFallbackTimerArmedAtMs).coerceAtLeast(0L)
        val fallbackLocation = Location(candidate).apply {
            time = fallbackTimeMs
            val sourceProvider = candidate.provider?.takeIf { it.isNotBlank() } ?: "fused"
            provider = "$FALLBACK_PROVIDER_PREFIX$sourceProvider"
            val mergedExtras = Bundle().apply {
                candidate.extras?.let { putAll(it) }
                putBoolean(EXTRAS_KEY_LOW_ACCURACY_FALLBACK, true)
                putString(EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER, sourceProvider)
            }
            extras = mergedExtras
        }
        val fallbackEvent = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = trackerId,
            lon = fallbackLocation.longitude,
            lat = fallbackLocation.latitude,
            timestampMs = fallbackLocation.time,
            accuracyMeters = if (fallbackLocation.hasAccuracy()) fallbackLocation.accuracy else null,
            quality = TrackPointQuality.DEGRADED,
            orderingKey = fallbackLocation.time
        )
        Log.d(
            TAG,
            "Low-accuracy fallback emitted provider=${candidate.provider} " +
                "acc=${if (candidate.hasAccuracy()) candidate.accuracy else null} " +
                "emitCountThisSession=${lowAccuracyFallbackEmitCountThisSession + 1} " +
                "waitingDurationMs=$waitingDurationMs"
        )
        lowAccuracyFallbackEmitCountThisSession++
        lowAccuracyFallbackTimerArmedAtMs = System.currentTimeMillis()
        broadcastTrackPoint(fallbackLocation, fallbackEvent)
        enqueueAndPushLocation(fallbackLocation, totalDistanceMeters)
        return true
    }

    private fun enqueueAndPushLocation(location: Location, distanceMeters: Float) {
        serviceScope.launch {
            val queued = QueuedLocation.fromLocation(location, distanceMeters)
            database.locationDao().insert(queued)
            val failureClass = pushLocations(
                scope = QueueUploadScope.LIVE_ONLY,
                sessionBoundaryMs = sessionBoundaryForBacklogMs
            )
            if (failureClass == SyncFailureClass.NONE) {
                consecutivePushFailures = 0
            }
        }
    }

    private fun cancelLowAccuracyFallbackTimer(clearCandidate: Boolean, reason: String) {
        val hadActiveTimer = lowAccuracyFallbackJob?.isActive == true
        val waitingDurationMs = if (lowAccuracyFallbackTimerArmedAtMs > 0L) {
            (System.currentTimeMillis() - lowAccuracyFallbackTimerArmedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        lowAccuracyFallbackJob?.cancel()
        lowAccuracyFallbackJob = null
        lowAccuracyFallbackCoordinator.onTrackingStopped()
        lowAccuracyFallbackTimerArmedAtMs = 0L
        if (hadActiveTimer) {
            lowAccuracyFallbackCancelCountThisSession++
            Log.i(
                TAG,
                "Low-accuracy fallback timer cancelled reason=$reason " +
                    "waitingDurationMs=$waitingDurationMs " +
                    "emitsThisSession=$lowAccuracyFallbackEmitCountThisSession " +
                    "rejectsThisSession=$lowAccuracyFallbackRejectedFixCountThisSession"
            )
        }
        if (clearCandidate) {
            lowAccuracyFallbackCandidate = null
        }
    }

    private fun maybeLogFallbackRejectSummary(provider: String?, accuracyMeters: Float?) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lowAccuracyFallbackLastRejectSummaryAtMs < FALLBACK_REJECT_SUMMARY_INTERVAL_MS) return
        lowAccuracyFallbackLastRejectSummaryAtMs = nowMs
        Log.d(
            TAG,
            "Low-accuracy fallback awaiting lock rejectsThisSession=$lowAccuracyFallbackRejectedFixCountThisSession " +
                "provider=$provider acc=$accuracyMeters fallbackEnabled=${currentSettings.lowAccuracyFallbackEnabled}"
        )
    }

}
