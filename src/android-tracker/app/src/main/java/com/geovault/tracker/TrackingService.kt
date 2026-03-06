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
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

class TrackingService : Service() {

    companion object {
        const val TAG = "TrackingService"
        const val ACTION_START = "com.geovault.tracker.ACTION_START"
        const val ACTION_STOP = "com.geovault.tracker.ACTION_STOP"
        const val NOTIFICATION_ID = 101
        /** Use v2 so Samsung (and others) get a fresh channel with IMPORTANCE_LOW; channel importance cannot be changed after first creation. */
        const val CHANNEL_ID = "tracker_service_v2"
        /** Group key so the notification can display collapsed on some devices (e.g. Samsung). */
        private const val NOTIFICATION_GROUP_KEY = "tracker_service_group"
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

        // Settings keys
        const val PREF_INTERVAL = "logging_interval"
        const val PREF_DISTANCE = "logging_distance"
        const val PREF_ACCURACY = "logging_accuracy"
        const val PREF_EXTENDED_PARAMS = "extended_params"
        const val PREF_SIGNIFICANT_MOTION = "significant_motion_only"
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
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun getAuthenticatedHttpClient(): OkHttpClient = RetrofitClient.getAuthenticatedOkHttpClient(applicationContext)

    private var sensorManager: SensorManager? = null
    private var significantMotionSensor: Sensor? = null
    private var triggerEventListener: TriggerEventListener? = null
    private var isGpsPaused = false
    private var consecutiveStationaryPoints = 0
    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0f
    private var sigMotionSensorStartTime = 0L
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null
    private val pushMutex = kotlinx.coroutines.sync.Mutex()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        database = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        significantMotionSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        triggerEventListener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                Log.d(TAG, "Significant motion detected, resuming GPS")
                resumeGps()
            }
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
        sendBroadcast(Intent(SESSION_STATS_UPDATE))

        runBlocking(Dispatchers.IO) {
            database.locationDao().deleteAll()
        }

        startForeground(NOTIFICATION_ID, createNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val intervalSec = prefs.getString(PREF_INTERVAL, "15")?.toLongOrNull() ?: 15L
        val distanceFilter = prefs.getString(PREF_DISTANCE, "10")?.toFloatOrNull() ?: 10f

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalSec * 1000)
            .setMinUpdateDistanceMeters(distanceFilter)
            .setMinUpdateIntervalMillis((intervalSec * 1000) / 2)
            .build()

        isGpsPaused = false
        consecutiveStationaryPoints = 0
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
        getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE).edit()
            .remove(PREF_WAS_TRACKING_BEFORE_EXIT).apply()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        cancelSignificantMotion()
        stopRetryJob()
        runBlocking(Dispatchers.IO) {
            database.locationDao().deleteAll()
        }
        sendBroadcast(Intent(SESSION_STATS_UPDATE))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onLocationReceived(location: Location) {
        // Always update last accuracy from the most recent fix so the UI shows current GPS fix quality
        lastAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null

        val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
        val accuracyFilter = prefs.getString(PREF_ACCURACY, "50")?.toFloatOrNull() ?: 50f

        if (location.hasAccuracy() && location.accuracy > accuracyFilter) {
            Log.d(TAG, "Location discarded (accuracy ${location.accuracy} > $accuracyFilter)")
            sendBroadcast(Intent(SESSION_STATS_UPDATE))
            return
        }

        Log.d(TAG, "Location received: ${location.latitude}, ${location.longitude}")
        val sigMotionOnly = prefs.getBoolean(PREF_SIGNIFICANT_MOTION, true)

        if (sigMotionOnly) {
            val dist = lastLocation?.distanceTo(location) ?: Float.MAX_VALUE
            val distanceFilter = prefs.getString(PREF_DISTANCE, "10")?.toFloatOrNull() ?: 10f
            
            if (dist < distanceFilter) {
                consecutiveStationaryPoints++
                Log.d(TAG, "Stationary point count: $consecutiveStationaryPoints")
            } else {
                consecutiveStationaryPoints = 0
            }

            if (consecutiveStationaryPoints >= 3) {
                Log.d(TAG, "User stationary for 3 points, pausing GPS")
                pauseGps()
            }
        }
        
        totalDistanceMeters += lastLocation?.distanceTo(location) ?: 0f
        sessionTotalDistanceMeters = totalDistanceMeters
        lastLocation = location

        // Notify MainActivity if it's visible
        val intent = Intent("com.geovault.tracker.LOCATION_UPDATE")
        intent.putExtra("location", location)
        sendBroadcast(intent)

        serviceScope.launch {
            val queued = QueuedLocation.fromLocation(location, totalDistanceMeters)
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
            val trackerIdStr = prefs.getString("selected_tracker_id", "") ?: ""
            if (trackerIdStr.isEmpty()) {
                Log.e(TAG, "No tracker selected, cannot push locations")
                updateNotificationCount()
                return
            }
            val trackerId = try {
                java.util.UUID.fromString(trackerIdStr)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid selected_tracker_id, cannot push locations", e)
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
            while (batchesSent < MAX_BATCHES_PER_PUSH) {
                val locationsToPush = database.locationDao().getAll()
                if (locationsToPush.isEmpty()) break

                // Limit to 50 locations per payload to avoid massive payloads
                val batch = locationsToPush.take(50)
                val sessionStart = sessionStartTimeMs
                val (batteryLevel, isCharging) = getBatteryStatus()
                val buildSerial = getBuildSerial()

                val payload = BinaryPayloadBuilder.buildPayload(
                    batch,
                    trackerId,
                    sessionStart,
                    batteryLevel,
                    isCharging,
                    buildSerial
                )
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
                        sendBroadcast(Intent(SESSION_STATS_UPDATE))
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

    private fun updateNotificationCount() {
        serviceScope.launch {
            val count = database.locationDao().getCount()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(count))
        }
    }

    private fun createNotification(queuedCount: Int): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        // Launch MainActivity with ACTION_STOP so the activity handles stop and updates UI (works when app was in background)
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STOP
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val stopPendingIntent = PendingIntent.getActivity(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val text = when {
            isGpsPaused -> "GPS Paused (waiting for motion)"
            queuedCount > 0 -> "Tracking location ($queuedCount points queued)"
            else -> "Tracking location"
        }

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
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSignificantMotion()
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
            requestSignificantMotion()
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
                    cancelSignificantMotion()
                    requestSignificantMotion()
                }
            }
        }
    }

    private fun resumeGps() {
        if (isGpsPaused && isTracking) {
            isGpsPaused = false
            consecutiveStationaryPoints = 0
            watchdogJob?.cancel()
            
            val prefs = getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
            val intervalSec = prefs.getString(PREF_INTERVAL, "15")?.toLongOrNull() ?: 15L
            val distanceFilter = prefs.getString(PREF_DISTANCE, "10")?.toFloatOrNull() ?: 10f

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalSec * 1000)
                .setMinUpdateDistanceMeters(distanceFilter)
                .setMinUpdateIntervalMillis((intervalSec * 1000) / 2)
                .build()

            try {
                fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission lost during resume", e)
            }
            updateNotificationCount()
        }
    }

    private fun requestSignificantMotion() {
        if (significantMotionSensor != null && triggerEventListener != null) {
            sensorManager?.requestTriggerSensor(triggerEventListener, significantMotionSensor)
            sigMotionSensorStartTime = System.currentTimeMillis()
            Log.d(TAG, "Requested significant motion trigger")
        }
    }

    private fun cancelSignificantMotion() {
        if (significantMotionSensor != null && triggerEventListener != null) {
            sensorManager?.cancelTriggerSensor(triggerEventListener, significantMotionSensor)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial() ?: ""
            } else {
                ""
            }
        } catch (e: SecurityException) {
            ""
        }
    }
}
