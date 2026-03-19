package com.geovault.tracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.location.LocationQualityConfig
import com.geovault.tracker.location.LocationQualityGate
import com.geovault.tracker.location.LocationRejectionReason
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.location.TrackingControlPlane
import com.geovault.tracker.location.TrackingControlState
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingSyncPolicy
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.UnifiedLocationClient
import com.geovault.tracker.location.UnifiedLocationSessionRequest
import com.geovault.tracker.pipeline.TrackPointSource
import com.geovault.tracker.pipeline.TrackPointServiceBase
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import com.geovault.tracker.settings.TrackerTrackingProfile
import com.geovault.tracker.settings.TrackingSettingsReapplyPolicy
import com.google.android.gms.location.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import javax.inject.Inject

@AndroidEntryPoint
class TrackingService : TrackPointServiceBase() {

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
        /** Group key so both services can collapse together on some devices (e.g. Samsung). */
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
    private var preflightJob: Job? = null
    private val pushMutex = kotlinx.coroutines.sync.Mutex()
    private var controlState: TrackingControlState = TrackingControlState()
    private var consecutivePushFailures = 0
    private var lastSyncFailureClass = SyncFailureClass.NONE
    
    private var currentActiveProfileIndex = -1
    private var lastSpeedMps: Float = 0f
    private var currentSettings: TrackerSettings = TrackerSettings()
    private var settingsObserveJob: Job? = null

    @Inject
    lateinit var settingsRepository: TrackerSettingsRepository

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        database = AppDatabase.getDatabase(this)
        unifiedLocationClient = UnifiedLocationClient(this)
        currentSettings = settingsRepository.getSettings()
        settingsObserveJob?.cancel()
        settingsObserveJob = serviceScope.launch {
            settingsRepository.observeSettings().collect { newSettings ->
                val previousSettings = currentSettings
                currentSettings = newSettings
                onSettingsChanged(previousSettings, newSettings)
            }
        }
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
                START_STICKY
            }
            ACTION_STOP -> {
                stopTracking()
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
                    START_STICKY
                } else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }
            ACTION_RESHOW_FOREGROUND -> {
                if (isTracking) {
                    serviceScope.launch {
                        val count = database.locationDao().getCount()
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
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    private fun startTracking() {
        if (isTracking) return
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

        sessionStartTimeMs = System.currentTimeMillis()
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
        syncRuntimeStateStore()
        broadcastSessionStats()

        startForeground(NOTIFICATION_ID, createNotification(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        isGpsPaused = false
        isWaitingForGpsLock = false
        consecutiveStationaryPoints = 0
        consecutiveBadAccuracyPoints = 0
        lastLocation = null
        currentActiveProfileIndex = if (currentSettings.autoTrackingMode) {
            TrackingLocationPolicy.getAutoStartProfileIndex()
        } else {
            -1
        }
        if (!applyCurrentLocationRequest("start_tracking")) {
            failActiveTracking(getString(R.string.unable_to_start_location_updates))
            return
        }
        
        // Push any existing queued locations immediately when tracking starts
        serviceScope.launch {
            lastSyncFailureClass = pushLocations()
        }
        
        // Start periodic retry job to push failed locations every minute
        startRetryJob()
        startPreflightMonitor()
    }

    private fun stopTracking() {
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
        unifiedLocationClient.stopSession()
        significantMotionBridge?.cancel()
        stopPreflightMonitor()
        stopRetryJob()
        broadcastSessionStats()
        stopForeground(STOP_FOREGROUND_REMOVE)
        transitionControlState(TrackingControlEvent.StopCompleted)
        stopSelf()
    }

    private fun onLocationReceived(location: Location) {
        if (!unifiedLocationClient.isGpsProviderEnabled()) {
            failActiveTracking(getString(R.string.gps_provider_required))
            return
        }
        // Always update last accuracy from the most recent fix so the UI shows current GPS fix quality
        lastAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        syncRuntimeStateStore()

        val quality = LocationQualityGate.evaluate(
            lastAcceptedLocation = lastLocation,
            newLocation = location,
            nowMs = System.currentTimeMillis(),
            config = LocationQualityConfig(
                maxAccuracyMeters = currentSettings.accuracyFilterMeters,
                maxJumpSpeedMps = 100.0,
                freshnessTtlMs = 120_000L,
                smoothingAlpha = 0.5f
            )
        )
        if (!quality.accepted) {
            if (quality.rejectionReason == LocationRejectionReason.BAD_ACCURACY ||
                quality.rejectionReason == LocationRejectionReason.STALE
            ) {
                consecutiveBadAccuracyPoints++
                if (consecutiveBadAccuracyPoints >= 3) {
                    isWaitingForGpsLock = true
                    updateNotificationCount()
                }
            }
            broadcastSessionStats()
            return
        }

        consecutiveBadAccuracyPoints = 0
        isWaitingForGpsLock = false
        val smoothedLocation = quality.location
        
        Log.d(TAG, "Location received: ${smoothedLocation.latitude}, ${smoothedLocation.longitude}")
        val sigMotionOnly = currentSettings.significantDataOnly
        val distanceFilter = currentSettings.distanceFilterMeters

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
        
        // Auto-profile switching logic
        if (currentSettings.autoTrackingMode) {
            val speed = if (location.hasSpeed()) location.speed else {
                // Fallback to calculated speed if hardware speed is missing
                val dist = lastLocation?.distanceTo(location) ?: 0f
                val timeSec = (location.time - (lastLocation?.time ?: location.time)) / 1000f
                if (timeSec > 0) dist / timeSec else 0f
            }
            
            // Simple smoothing for auto-mode speed detection
            lastSpeedMps = (0.7f * lastSpeedMps) + (0.3f * speed)
            
            val recommended = TrackingLocationPolicy.getRecommendedProfile(lastSpeedMps, currentActiveProfileIndex)
            if (recommended != currentActiveProfileIndex) {
                Log.d(TAG, "Auto-switching profile from $currentActiveProfileIndex to $recommended (speed: ${lastSpeedMps}m/s)")
                currentActiveProfileIndex = recommended
                settingsRepository.setTrackingProfile(TrackerTrackingProfile.fromIndex(recommended))
                reapplyLocationRequestIfActive("auto_profile_switch")
            }
        }

        lastLocation = smoothedLocation

        broadcastTrackPoint(smoothedLocation)

        serviceScope.launch {
            val queued = QueuedLocation.fromLocation(smoothedLocation, totalDistanceMeters)
            database.locationDao().insert(queued)
            val failureClass = pushLocations()
            if (failureClass == SyncFailureClass.NONE) {
                consecutivePushFailures = 0
            }
        }
    }

    private suspend fun pushLocations(): SyncFailureClass {
        // Prevent concurrent pushes
        if (!pushMutex.tryLock()) {
            Log.d(TAG, "Push already in progress, skipping")
            return SyncFailureClass.TRANSIENT
        }
        
        try {
            trimQueuedLocationsRetention()
            if (!NetworkStatusMonitor.hasUsableNetwork(this)) {
                updateNotificationCount()
                return SyncFailureClass.TRANSIENT
            }
            val trackerIdStr = SelectedTrackerPrefs.selectedTrackerId(this)
            if (trackerIdStr.isEmpty()) {
                Log.e(TAG, "No tracker selected, cannot push locations")
                broadcastTrackingError(getString(R.string.no_tracker_selected_go_to_settings))
                updateNotificationCount()
                return SyncFailureClass.PERMANENT
            }
            val trackerId = try {
                java.util.UUID.fromString(trackerIdStr)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid selected tracker id, cannot push locations", e)
                broadcastTrackingError(getString(R.string.tracker_validation_failed_go_to_settings))
                updateNotificationCount()
                return SyncFailureClass.PERMANENT
            }

            val serverUrl = GeovaultAuthManager.getServerUrl(this)
            if (serverUrl.isEmpty()) {
                updateNotificationCount()
                return SyncFailureClass.PERMANENT
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
                val batch = database.locationDao().getOldest(50)
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
                        pointsSentThisSession += batch.size
                        lastPointSentAtMs = System.currentTimeMillis()
                        syncRuntimeStateStore()
                        broadcastSessionStats()
                        database.locationDao().delete(batch)
                        batchesSent++
                    } else {
                        Log.e(TAG, "Failed to push locations: ${response.code} ${response.message}")
                        if (response.code in 400..499) {
                            return SyncFailureClass.PERMANENT
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception pushing locations", e)
                    break
                }
            }

            updateNotificationCount()
            trimQueuedLocationsRetention()
            return if (batchesSent > 0) SyncFailureClass.NONE else SyncFailureClass.TRANSIENT
        } finally {
            pushMutex.unlock()
        }
    }

    private fun broadcastSessionStats() {
        val intent = Intent(SESSION_STATS_UPDATE).apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun updateNotificationCount() {
        serviceScope.launch {
            val count = database.locationDao().getCount()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(pointsSentThisSession, count))
        }
    }

    private fun broadcastTrackPoint(location: Location) {
        val trackerId = SelectedTrackerPrefs.selectedTrackerId(this)
        if (trackerId.isEmpty()) return
        val propsJson = buildLocalPointPropsJson(location, totalDistanceMeters)
        lastTrackedLatitude = location.latitude
        lastTrackedLongitude = location.longitude
        lastTrackedTimestampMs = location.time
        lastTrackedPropsJson = propsJson
        syncRuntimeStateStore()
        val point = LiveTrackStreamingService.TrackPointBroadcast(
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
            propsJson = point.propsJson
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
            val sat = location.extras?.getInt("satellites", 0)?.takeIf { it > 0 }
            if (sat != null) props.put("sat", sat)
            val (batteryLevel, isCharging) = getBatteryStatus()
            props.put("batt", batteryLevel)
            props.put("ischarging", isCharging)
            props.toString()
        } catch (_: Exception) {
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
        // Launch MainActivity with ACTION_STOP so the activity handles stop and updates UI (works when app was in background)
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STOP
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val stopPendingIntent = PendingIntent.getActivity(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val dismissIntent = Intent(NOTIFICATION_DISMISSED_ACTION).apply { setPackage(packageName) }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val noGoodFix = lastAccuracyMeters == null || lastAccuracyMeters!! > 152.4f
        val status = when {
            isGpsPaused -> getString(R.string.status_gps_paused)
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
            .setGroup(NOTIFICATION_GROUP_KEY)  // Single-line / collapsed on some UIs (e.g. Samsung)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(dismissPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        settingsObserveJob?.cancel()
        super.onDestroy()
        significantMotionBridge?.cancel()
        stopPreflightMonitor()
        stopRetryJob()
        serviceScope.cancel()
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
                    val outcome = pushLocations()
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
    
    private fun stopRetryJob() {
        retryJob?.cancel()
        retryJob = null
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
                        failActiveTracking(getString(R.string.gps_provider_required))
                    }
                    return@launch
                }
            }
        }
    }

    private fun stopPreflightMonitor() {
        preflightJob?.cancel()
        preflightJob = null
    }

    private fun pauseGps() {
        if (!isGpsPaused && isTracking) {
            isGpsPaused = true
            unifiedLocationClient.stopSession()
            significantMotionBridge?.request()
            sigMotionSensorStartTime = System.currentTimeMillis()
            startSensorWatchdog()
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
            lastSpeedMps = 0f
            watchdogJob?.cancel()

            if (!applyCurrentLocationRequest("resume_gps")) {
                failActiveTracking(getString(R.string.location_permission_revoked))
                return
            }
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

    private fun onSettingsChanged(previous: TrackerSettings, current: TrackerSettings) {
        if (!isTracking) return
        val needsReapply = TrackingSettingsReapplyPolicy.shouldReapplyLocationRequest(previous, current)
        if (needsReapply) {
            reapplyLocationRequestIfActive("settings_changed")
        }
    }

    private fun resolveCurrentIntervalAndDistance(): Pair<Long, Float> {
        val isAuto = currentSettings.autoTrackingMode
        if (isAuto) {
            // In auto mode, always honor the latest profile from settings so
            // live updates (UI or service-driven) are applied immediately.
            currentActiveProfileIndex = currentSettings.trackingProfile.index
            val params = TrackingLocationPolicy.getProfileParams(currentActiveProfileIndex)
            return params.first to params.second
        }

        currentActiveProfileIndex = -1
        return currentSettings.loggingIntervalSec to currentSettings.distanceFilterMeters
    }

    private fun buildLocationRequest(intervalSec: Long, distanceFilter: Float): LocationRequest {
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(intervalSec)
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(distanceFilter)
            .setMinUpdateIntervalMillis(minUpdateMs)
            .build()
    }

    private fun applyCurrentLocationRequest(reason: String): Boolean {
        if (!isTracking) return false
        if (!unifiedLocationClient.hasLocationPermission()) {
            return false
        }
        val (intervalSec, distanceFilter) = resolveCurrentIntervalAndDistance()
        val request = buildLocationRequest(intervalSec, distanceFilter)
        val started = unifiedLocationClient.startSession(
            sessionRequest = UnifiedLocationSessionRequest(request),
            onLocation = ::onLocationReceived,
            onError = { error ->
                Log.e(TAG, "Failed to apply location request ($reason)", error)
            }
        )
        if (!started) return false
        Log.d(
            TAG,
            "Applied LocationRequest ($reason): interval=${intervalSec}s, distance=${distanceFilter}m, profile=$currentActiveProfileIndex, auto=${currentSettings.autoTrackingMode}"
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
        stopTracking()
    }

    private fun failStartup(message: String) {
        Log.w(TAG, "Tracking start failed: $message")
        settingsRepository.clearWasTrackingBeforeExit()
        transitionControlState(TrackingControlEvent.StartFailed, message)
        syncRuntimeStateStore()
        broadcastTrackingError(message)
        stopSelf()
    }

    private fun transitionControlState(event: TrackingControlEvent, failureReason: String? = null) {
        controlState = TrackingControlPlane.transition(controlState, event, failureReason)
        syncRuntimeStateStore()
    }

}
