package com.geovault.tracker

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.pipeline.TrackPointSource
import com.geovault.tracker.pipeline.TrackPointServiceBase
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

class TrackingService : TrackPointServiceBase() {

    companion object {
        const val TAG = "TrackingService"
        const val ACTION_START = "com.geovault.tracker.ACTION_START"
        const val ACTION_STOP = "com.geovault.tracker.ACTION_STOP"
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

        // Settings keys
        const val PREF_INTERVAL = "logging_interval"
        const val PREF_DISTANCE = "logging_distance"
        const val PREF_ACCURACY = "logging_accuracy"
        const val PREF_EXTENDED_PARAMS = "extended_params"
        const val PREF_SIGNIFICANT_MOTION = "significant_motion_only"
        const val PREF_AUTO_TRACKING = "auto_tracking_enabled"
        const val PREF_TRACKING_PROFILE = "tracking_profile"
        const val PREF_WAS_TRACKING_BEFORE_EXIT = "was_tracking_before_exit"

        /** Interval between retry attempts when the queue has failed-to-send items. */
        const val RETRY_INTERVAL_MS = 60_000L

        /** ±jitter (ms) added to retry interval to avoid thundering herd. */
        private const val RETRY_JITTER_MS = 10_000L

        /** Max batches to send in one push call to avoid holding the lock too long. */
        private const val MAX_BATCHES_PER_PUSH = 10

    }

    private var isTracking = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
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
    private val pushMutex = kotlinx.coroutines.sync.Mutex()
    
    private var currentActiveProfileIndex = -1
    private var lastSpeedMps: Float = 0f

    private fun clearQueuedLocationsAsync() {
        serviceScope.launch {
            database.locationDao().deleteAll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        database = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val significantMotionTrigger = SensorManagerSignificantMotionTrigger(applicationContext)
        significantMotionBridge = SignificantMotionResumeBridge(significantMotionTrigger) {
            Log.d(TAG, "Significant motion detected, resuming GPS")
            resumeGps()
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    onLocationReceived(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            null -> {
                val shouldRestart = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
                    .getBoolean(PREF_WAS_TRACKING_BEFORE_EXIT, false)
                if (shouldRestart) {
                    startTracking()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (isTracking) return
        isTracking = true
        isRunning = true
        Log.d(TAG, "Starting tracking")
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_WAS_TRACKING_BEFORE_EXIT, true).apply()

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
        syncRuntimeStateStore()
        broadcastSessionStats()

        clearQueuedLocationsAsync()

        startForeground(NOTIFICATION_ID, createNotification(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val isAuto = prefs.getBoolean(PREF_AUTO_TRACKING, false)
        
        currentActiveProfileIndex = if (isAuto) {
            prefs.getString(PREF_TRACKING_PROFILE, "1")?.toIntOrNull() ?: 1
        } else {
            -1 // Manual mode
        }

        val intervalSec: Long
        val distanceFilter: Float
        
        if (isAuto) {
            val params = TrackingLocationPolicy.getProfileParams(currentActiveProfileIndex)
            intervalSec = params.first
            distanceFilter = params.second
        } else {
            intervalSec = prefs.getString(PREF_INTERVAL, "15")?.toLongOrNull() ?: 15L
            distanceFilter = prefs.getString(PREF_DISTANCE, "10")?.toFloatOrNull() ?: 10f
        }
        
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(intervalSec)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(distanceFilter)
            .setMinUpdateIntervalMillis(minUpdateMs)
            .build()

        isGpsPaused = false
        isWaitingForGpsLock = false
        consecutiveStationaryPoints = 0
        consecutiveBadAccuracyPoints = 0
        lastLocation = null
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        
        // Push any existing queued locations immediately when tracking starts
        serviceScope.launch {
            pushLocations()
        }
        
        // Start periodic retry job to push failed locations every minute
        startRetryJob()
    }

    private fun stopTracking() {
        if (!isTracking) return
        Log.d(TAG, "Stopping tracking")
        isTracking = false
        isRunning = false
        sessionStartTimeMs = 0
        lastTrackedLatitude = null
        lastTrackedLongitude = null
        lastTrackedTimestampMs = 0L
        lastTrackedPropsJson = null
        syncRuntimeStateStore()
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
            .remove(PREF_WAS_TRACKING_BEFORE_EXIT).commit()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        significantMotionBridge?.cancel()
        stopRetryJob()
        clearQueuedLocationsAsync()
        broadcastSessionStats()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onLocationReceived(location: Location) {
        // Always update last accuracy from the most recent fix so the UI shows current GPS fix quality
        lastAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        syncRuntimeStateStore()

        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val accuracyFilter = prefs.getString(PREF_ACCURACY, "50")?.toFloatOrNull() ?: 50f

        if (!TrackingLocationPolicy.acceptByAccuracy(location, accuracyFilter)) {
            Log.d(TAG, "Location discarded (accuracy ${location.accuracy} > $accuracyFilter)")
            consecutiveBadAccuracyPoints++
            if (consecutiveBadAccuracyPoints >= 3) {
                isWaitingForGpsLock = true
                updateNotificationCount()
            }
            broadcastSessionStats()
            return
        }

        // Jump Filtering: Discard points implying speeds > 100 m/s (~220 mph)
        if (TrackingLocationPolicy.isJump(lastLocation, location)) {
            Log.d(TAG, "Location discarded (Jump detected)")
            return
        }

        consecutiveBadAccuracyPoints = 0
        isWaitingForGpsLock = false
        
        // EWMA Smoothing to stabilize coordinates
        val smoothedLocation = TrackingLocationPolicy.smooth(lastLocation, location)
        
        Log.d(TAG, "Location received: ${smoothedLocation.latitude}, ${smoothedLocation.longitude}")
        val sigMotionOnly = prefs.getBoolean(PREF_SIGNIFICANT_MOTION, true)
        val distanceFilter = prefs.getString(PREF_DISTANCE, "10")?.toFloatOrNull() ?: 10f

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
        if (prefs.getBoolean(PREF_AUTO_TRACKING, false)) {
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
                prefs.edit().putString(PREF_TRACKING_PROFILE, recommended.toString()).apply()
                updateLocationRequest(recommended)
            }
        }

        lastLocation = smoothedLocation

        broadcastTrackPoint(smoothedLocation)

        serviceScope.launch {
            val queued = QueuedLocation.fromLocation(smoothedLocation, totalDistanceMeters)
            database.locationDao().insert(queued)
            pushLocations()
        }
    }

    private suspend fun pushLocations() {
        // Prevent concurrent pushes
        if (!pushMutex.tryLock()) {
            Log.d(TAG, "Push already in progress, skipping")
            return
        }
        
        try {
            val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            val trackerIdStr = SelectedTrackerPrefs.selectedTrackerId(this)
            if (trackerIdStr.isEmpty()) {
                Log.e(TAG, "No tracker selected, cannot push locations")
                updateNotificationCount()
                return
            }
            val trackerId = try {
                java.util.UUID.fromString(trackerIdStr)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid selected tracker id, cannot push locations", e)
                updateNotificationCount()
                return
            }

            val serverUrl = GeovaultAuthManager.getServerUrl(this)
            if (serverUrl.isEmpty()) {
                updateNotificationCount()
                return
            }

            val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            val ingressUrl = "${baseUrl}api/extensions/live-track/app-ingress/"

            var batchesSent = 0
            val useExtendedParams = prefs.getBoolean(PREF_EXTENDED_PARAMS, true)
            
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
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception pushing locations", e)
                    break
                }
            }

            updateNotificationCount()
            
            // Clean up old ones if queue is getting too big (e.g., max 1000)
            // This ensures the device doesn't run out of space if the server is permanently down
            val count = database.locationDao().getCount()
            if (count > 1000) {
                val oldest = database.locationDao().getOldest(count - 1000)
                database.locationDao().delete(oldest)
            }
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
        val useExtendedParams = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_EXTENDED_PARAMS, true)
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
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        significantMotionBridge?.cancel()
        stopRetryJob()
        serviceScope.cancel()
    }
    
    private fun startRetryJob() {
        retryJob?.cancel()
        retryJob = serviceScope.launch {
            while (isActive && isTracking) {
                val jitter = Random.nextLong(-RETRY_JITTER_MS, RETRY_JITTER_MS + 1)
                delay(RETRY_INTERVAL_MS + jitter)
                val count = database.locationDao().getCount()
                if (count > 0) {
                    Log.d(TAG, "Retry job: attempting to push $count queued locations")
                    pushLocations()
                }
            }
        }
    }
    
    private fun stopRetryJob() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun pauseGps() {
        if (!isGpsPaused && isTracking) {
            isGpsPaused = true
            fusedLocationClient.removeLocationUpdates(locationCallback)
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
            watchdogJob?.cancel()
            
            val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            val intervalSec = prefs.getString(PREF_INTERVAL, "15")?.toLongOrNull() ?: 15L
            val distanceFilter = prefs.getString(PREF_DISTANCE, "10")?.toFloatOrNull() ?: 10f
            val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(intervalSec)

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateDistanceMeters(distanceFilter)
                .setMinUpdateIntervalMillis(minUpdateMs)
                .build()

            try {
                fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission lost during resume", e)
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

    @SuppressLint("MissingPermission")
    private fun updateLocationRequest(profileIndex: Int) {
        if (!isTracking) return
        
        val params = TrackingLocationPolicy.getProfileParams(profileIndex)
        val intervalSec = params.first
        val distanceFilter = params.second
        val (intervalMs, minUpdateMs) = TrackingLocationPolicy.locationRequestIntervalFromSec(intervalSec)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(distanceFilter)
            .setMinUpdateIntervalMillis(minUpdateMs)
            .build()
            
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        Log.d(TAG, "Updated LocationRequest: interval=${intervalSec}s, distance=${distanceFilter}m")
    }
}
