package com.geovault.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Foreground service that holds a WebSocket connection to the trackers-live endpoint
 * and broadcasts incoming track_updated points to the map. Runs while a non-default
 * track is shown on the map so streaming continues in the background.
 */
class LiveTrackStreamingService : Service() {

    companion object {
        private const val TAG = "LiveTrackStreaming"
        const val ACTION_START = "com.geovault.tracker.LIVE_TRACK_STREAMING_START"
        const val ACTION_STOP = "com.geovault.tracker.LIVE_TRACK_STREAMING_STOP"
        const val EXTRA_TRACKER_ID = "tracker_id"
        const val EXTRA_TRACKER_NAME = "tracker_name"
        const val NOTIFICATION_ID = 102
        private const val CHANNEL_ID = "live_track_streaming"
        const val BROADCAST_TRACK_POINT = "com.geovault.tracker.LIVE_TRACK_POINT"
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_POINT_LON = "point_lon"
        const val EXTRA_POINT_LAT = "point_lat"
        const val EXTRA_POINT_TS_MS = "point_ts_ms"
        const val EXTRA_ACCURACY_METERS = "accuracy_meters"
        /** JSON object string of extended point params (props), so params UI can update live. */
        const val EXTRA_PROPS_JSON = "props_json"
        private const val RECONNECT_BASE_DELAY_MS = 3000L
        private const val RECONNECT_MAX_DELAY_MS = 60000L
        private const val WS_READ_TIMEOUT_SEC = 90L
        private const val WS_PING_INTERVAL_SEC = 30L
        private const val MAX_BUFFERED_POINTS = 1000

        /** True while the service is actively running in foreground. */
        @Volatile
        @JvmStatic
        var isRunning = false
            private set

        /**
         * In-memory buffer of streamed points. Points are added by the service
         * and drained by MapFragment on resume so no data is lost when the
         * map tab is not visible.
         */
        private val pointBuffer = ConcurrentLinkedQueue<TrackPointBroadcast>()

        /**
         * Drain all buffered points and return them as a list.
         * Call from MapFragment.onResume() to catch up on missed points.
         */
        @JvmStatic
        fun drainBufferedPoints(): List<TrackPointBroadcast> {
            val result = mutableListOf<TrackPointBroadcast>()
            while (true) {
                val p = pointBuffer.poll() ?: break
                result.add(p)
            }
            return result
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var webSocket: WebSocket? = null
    private var currentTrackerId: String? = null
    private var currentTrackerName: String? = null
    private var connectJob: Job? = null
    private var reconnectAttempts = 0

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val trackerId = intent.getStringExtra(EXTRA_TRACKER_ID)
                val trackerName = intent.getStringExtra(EXTRA_TRACKER_NAME)

                // Show notification immediately to satisfy Android 14+ contract
                val notification = createNotification(trackerName)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                isRunning = true

                if (!trackerId.isNullOrBlank()) {
                    // Debounce: If already streaming this tracker, don't restart WebSocket
                    if (trackerId == currentTrackerId && webSocket != null) {
                        Log.d(TAG, "Already streaming tracker $trackerId, skipping reset")
                        return START_NOT_STICKY
                    }

                    // Close previous WebSocket if switching trackers
                    disconnectWebSocket()
                    pointBuffer.clear()
                    currentTrackerId = trackerId
                    currentTrackerName = trackerName
                    reconnectAttempts = 0
                    
                    connectJob?.cancel()
                    connectJob = serviceScope.launch { connect(trackerName) }
                } else {
                    Log.w(TAG, "ACTION_START received with null/empty trackerId")
                    isRunning = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                disconnectWebSocket()
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Service was created by startService() without a valid action
                // (e.g. spurious stop when not running). Just stop immediately.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connectJob?.cancel()
        disconnectWebSocket()
        isRunning = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed, stopping streaming service")
        disconnectWebSocket()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotification(trackerName: String?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getActivity(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = getString(R.string.live_track_streaming_title)
        val text = trackerName?.takeIf { it.isNotBlank() }?.let {
            getString(R.string.live_track_streaming_text, it)
        } ?: getString(R.string.live_track_streaming_text_anon)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, getString(R.string.stop_streaming), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SYSTEM)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSortKey("\uFFFF")
            .setGroup("geovault_service_group")
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private suspend fun connect(trackerName: String?) {
        while (serviceScope.isActive && currentTrackerId != null) {
            val token = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    GeovaultAuthManager.getValidAccessToken(applicationContext, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Token refresh failed", e)
                    null
                }
            }
            if (token.isNullOrBlank()) {
                Log.w(TAG, "No token, retrying in 5s")
                delay(5000L)
                continue
            }
            val serverUrl = GeovaultAuthManager.getServerUrl(applicationContext).trimEnd('/')
            if (serverUrl.isEmpty()) {
                stopSelf()
                return
            }
            val wsScheme = when {
                serverUrl.startsWith("https://") -> "wss"
                serverUrl.startsWith("http://") -> "ws"
                else -> "wss"
            }
            val base = serverUrl.removePrefix("https://").removePrefix("http://")
            val wsUrl = "$wsScheme://$base/ws/extensions/live-track/trackers-live/"
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val listener = TrackersWebSocketListener(
                currentTrackerId!!,
                onPoint = { bufferAndBroadcast(it) },
                onDisconnect = { scheduleReconnect() }
            )
            val wsClient = RetrofitClient.getAuthenticatedOkHttpClient(applicationContext).newBuilder()
                .readTimeout(WS_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .pingInterval(WS_PING_INTERVAL_SEC, TimeUnit.SECONDS)
                .build()
            try {
                webSocket = wsClient.newWebSocket(request, listener)
                reconnectAttempts = 0
                break
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connect failed", e)
            }
            val delayMs = (RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(4)))
                .coerceAtMost(RECONNECT_MAX_DELAY_MS)
            reconnectAttempts++
            delay(delayMs)
        }
    }

    private fun bufferAndBroadcast(point: TrackPointBroadcast) {
        // Buffer the point so MapFragment can catch up on resume
        pointBuffer.add(point)
        while (pointBuffer.size > MAX_BUFFERED_POINTS) {
            pointBuffer.poll()
        }

        // Also broadcast for the live case (map is visible right now; params fragment can update too)
        val intent = Intent(BROADCAST_TRACK_POINT).apply {
            setPackage(packageName)
            putExtra(EXTRA_TRACK_ID, point.trackId)
            putExtra(EXTRA_POINT_LON, point.lon)
            putExtra(EXTRA_POINT_LAT, point.lat)
            putExtra(EXTRA_POINT_TS_MS, point.timestampMs)
            point.accuracyMeters?.let { putExtra(EXTRA_ACCURACY_METERS, it) }
            point.propsJson?.let { putExtra(EXTRA_PROPS_JSON, it) }
        }
        sendBroadcast(intent)
    }

    private fun scheduleReconnect() {
        if (currentTrackerId == null) return
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            val delayMs = (RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempts.coerceAtMost(4)))
                .coerceAtMost(RECONNECT_MAX_DELAY_MS)
            reconnectAttempts++
            delay(delayMs)
            if (currentTrackerId != null) connect(currentTrackerName)
        }
    }

    /**
     * Close WebSocket and cancel reconnect jobs.
     * Does NOT update isRunning (the caller is responsible for that).
     */
    private fun disconnectWebSocket() {
        currentTrackerId = null
        currentTrackerName = null
        connectJob?.cancel()
        connectJob = null
        try {
            webSocket?.close(1000, null)
        } catch (_: Exception) { }
        webSocket = null
        pointBuffer.clear()
    }

    data class TrackPointBroadcast(
        val trackId: String,
        val lon: Double,
        val lat: Double,
        val timestampMs: Long,
        val accuracyMeters: Float? = null,
        /** JSON object string of extended point params (props) for params UI. */
        val propsJson: String? = null
    )

    private class TrackersWebSocketListener(
        private val filterTrackId: String,
        private val onPoint: (TrackPointBroadcast) -> Unit,
        private val onDisconnect: () -> Unit = {}
    ) : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                val module = json.optString("module", "")
                val type = json.optString("type", "")
                if (module != "live_track" || type != "track_updated") return
                val data = json.optJSONObject("data") ?: return
                val trackId = data.optString("track_id", "")
                if (trackId != filterTrackId) return
                val pointArr = data.optJSONArray("point") ?: return
                if (pointArr.length() < 2) return
                val lon = pointArr.getDouble(0)
                val lat = pointArr.getDouble(1)
                val ts = if (pointArr.length() >= 3) pointArr.getLong(2) else 0L
                val props = data.optJSONObject("props")
                val acc = props?.optDouble("acc", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat()
                val propsJson = props?.takeIf { props.length() > 0 }?.toString()
                onPoint(TrackPointBroadcast(trackId, lon, lat, ts, acc, propsJson))
            } catch (e: Exception) {
                Log.e(TAG, "Parse track_updated failed", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failed: ${t.message}")
            onDisconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code != 1000) onDisconnect()
        }
    }
}
