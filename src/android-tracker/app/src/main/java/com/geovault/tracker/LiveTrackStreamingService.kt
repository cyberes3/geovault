package com.geovault.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.location.StreamingFailureClass
import com.geovault.tracker.location.StreamingLifecycleEvent
import com.geovault.tracker.location.StreamingLifecycleOrchestrator
import com.geovault.tracker.location.StreamingLifecycleState
import com.geovault.tracker.pipeline.TrackPointSource
import com.geovault.tracker.pipeline.TrackPointServiceBase
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
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
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground service that holds a WebSocket connection to the trackers-live endpoint
 * and broadcasts incoming track_updated points to the map for active tracker contexts.
 */
class LiveTrackStreamingService : TrackPointServiceBase() {

    companion object {
        private const val TAG = "LiveTrackStreaming"
        const val ACTION_START = "com.geovault.tracker.LIVE_TRACK_STREAMING_START"
        const val ACTION_STOP = "com.geovault.tracker.LIVE_TRACK_STREAMING_STOP"
        const val ACTION_RESHOW_FOREGROUND = "com.geovault.tracker.STREAMING_ACTION_RESHOW_FOREGROUND"
        const val NOTIFICATION_DISMISSED_ACTION = "com.geovault.tracker.STREAMING_NOTIFICATION_DISMISSED"
        const val EXTRA_TRACKER_ID = "tracker_id"
        const val EXTRA_TRACKER_IDS = "tracker_ids"
        const val EXTRA_TRACKER_NAME = "tracker_name"
        const val ACTION_STREAMING_ERROR = "com.geovault.tracker.STREAMING_ERROR"
        const val EXTRA_STREAMING_ERROR_MESSAGE = "extra_streaming_error_message"
        const val NOTIFICATION_ID = 102
        private const val CHANNEL_ID = "live_track_streaming"
        private const val WS_READ_TIMEOUT_SEC = 90L
        private const val WS_PING_INTERVAL_SEC = 30L
        /** True while the service is actively running in foreground. */
        @Volatile
        @JvmStatic
        var isRunning = false
            private set
        private const val PREFS_NAME = "streaming_runtime"
        private const val PREFS_TRACKER_IDS = "tracker_ids_csv"
        private const val PREFS_TRACKER_NAME = "tracker_name"

    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var webSocket: WebSocket? = null
    private var currentTrackerIds: Set<String> = emptySet()
    private var currentTrackerName: String? = null
    private var connectJob: Job? = null
    private var connectionSessionId: Long = 0L
    private var lifecycle = StreamingLifecycleState()

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val restored = restoreLastStreamingSession()
            if (restored == null) {
                applyLifecycleEvent(
                    event = StreamingLifecycleEvent.StopRequested,
                    activeTrackerIds = emptySet()
                )
                stopSelf()
                return START_NOT_STICKY
            }
            val restoredIntent = Intent(this, LiveTrackStreamingService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_TRACKER_IDS, ArrayList(restored.first))
                putExtra(EXTRA_TRACKER_NAME, restored.second)
            }
            return onStartCommand(restoredIntent, flags, startId)
        }
        when (intent?.action) {
            ACTION_START -> {
                val trackerIds = extractTrackerIds(intent)
                val trackerName = intent.getStringExtra(EXTRA_TRACKER_NAME)

                // Show notification immediately to satisfy Android 14+ contract
                val notification = createNotification(trackerName, trackerIds.size)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

                if (trackerIds.isNotEmpty()) {
                    // Debounce: If already streaming this exact tracker set, don't restart WebSocket.
                    if (trackerIds == currentTrackerIds && webSocket != null) {
                        Log.d(TAG, "Already streaming tracker set (${trackerIds.size}), skipping reset")
                        applyLifecycleEvent(
                            event = StreamingLifecycleEvent.Connected,
                            activeTrackerIds = trackerIds
                        )
                        return START_STICKY
                    }
                    applyLifecycleEvent(
                        event = StreamingLifecycleEvent.StartRequested,
                        activeTrackerIds = trackerIds
                    )

                    // Close previous WebSocket if switching targets.
                    disconnectWebSocket()
                    currentTrackerIds = trackerIds
                    currentTrackerName = trackerName
                    connectionSessionId++
                    persistStreamingSession(currentTrackerIds, currentTrackerName)
                    
                    connectJob?.cancel()
                    connectJob = serviceScope.launch { connect(connectionSessionId) }
                } else {
                    Log.w(TAG, "ACTION_START received with empty tracker target set")
                    persistStreamingSession(emptySet(), null)
                    applyLifecycleEvent(
                        event = StreamingLifecycleEvent.PermanentFailure,
                        activeTrackerIds = emptySet(),
                        failureReason = getString(R.string.no_tracker_selected_go_to_settings)
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                disconnectWebSocket()
                connectionSessionId++
                persistStreamingSession(emptySet(), null)
                applyLifecycleEvent(
                    event = StreamingLifecycleEvent.StopRequested,
                    activeTrackerIds = emptySet()
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RESHOW_FOREGROUND -> {
                if (isRunning && currentTrackerIds.isNotEmpty()) {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(currentTrackerName, currentTrackerIds.size),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                }
            }
            else -> {
                // Service was created by startService() without a valid action
                // (e.g. spurious stop when not running). Just stop immediately.
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connectJob?.cancel()
        disconnectWebSocket()
        applyLifecycleEvent(
            event = StreamingLifecycleEvent.StopRequested,
            activeTrackerIds = emptySet()
        )
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed, stopping streaming service")
        disconnectWebSocket()
        connectionSessionId++
        persistStreamingSession(emptySet(), null)
        applyLifecycleEvent(
            event = StreamingLifecycleEvent.StopRequested,
            activeTrackerIds = emptySet()
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotification(trackerName: String?, trackerCount: Int): Notification {
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
        val dismissIntent = Intent(NOTIFICATION_DISMISSED_ACTION).apply { setPackage(packageName) }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = getString(R.string.live_track_streaming_title)
        val text = when {
            trackerCount > 1 -> String.format(Locale.US, "%d trackers", trackerCount)
            trackerName?.isNotBlank() == true -> getString(R.string.live_track_streaming_text, trackerName)
            else -> getString(R.string.live_track_streaming_text_anon)
        }
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
            .setDeleteIntent(dismissPendingIntent)
            .build()
    }

    private fun extractTrackerIds(intent: Intent): Set<String> {
        val idsFromArray = intent.getStringArrayListExtra(EXTRA_TRACKER_IDS)
            ?.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            ?.toSet()
            ?: emptySet()
        if (idsFromArray.isNotEmpty()) return idsFromArray
        val legacyId = intent.getStringExtra(EXTRA_TRACKER_ID)?.trim()
        return legacyId?.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: emptySet()
    }

    private suspend fun connect(sessionId: Long) {
        if (!serviceScope.isActive || currentTrackerIds.isEmpty()) return
        val token = kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                GeovaultAuthManager.getValidAccessToken(applicationContext, null)
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed", e)
                null
            }
        }
        if (token.isNullOrBlank()) {
            val failureReason = getString(R.string.error_server_unreachable)
            emitStreamingErrorIfNeeded(failureReason)
            scheduleReconnect(
                sessionId = sessionId,
                failureClass = StreamingFailureClass.AUTH,
                failureReason = failureReason
            )
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(applicationContext).trimEnd('/')
        if (serverUrl.isEmpty()) {
            emitStreamingError(getString(R.string.error_server_unreachable))
            applyLifecycleEvent(
                event = StreamingLifecycleEvent.PermanentFailure,
                activeTrackerIds = currentTrackerIds,
                failureReason = getString(R.string.error_server_unreachable)
            )
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
        val trackerIdsSnapshot = currentTrackerIds
        val listener = TrackersWebSocketListener(
            trackerIdsSnapshot,
            onPoint = { bufferAndBroadcast(it) },
            onDisconnect = {
                scheduleReconnect(
                    sessionId = sessionId,
                    failureClass = StreamingFailureClass.TRANSIENT,
                    failureReason = getString(R.string.error_server_unreachable)
                )
            }
        )
        val wsClient = RetrofitClient.getAuthenticatedOkHttpClient(applicationContext).newBuilder()
            .readTimeout(WS_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(WS_PING_INTERVAL_SEC, TimeUnit.SECONDS)
            .build()
        try {
            webSocket = wsClient.newWebSocket(request, listener)
            applyLifecycleEvent(
                event = StreamingLifecycleEvent.Connected,
                activeTrackerIds = currentTrackerIds
            )
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connect failed", e)
            scheduleReconnect(
                sessionId = sessionId,
                failureClass = StreamingFailureClass.TRANSIENT,
                failureReason = e.message ?: getString(R.string.error_server_unreachable)
            )
        }
    }

    private fun bufferAndBroadcast(point: StreamingTrackPoint) {
        publishTrackPoint(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = point.trackId,
            lon = point.lon,
            lat = point.lat,
            timestampMs = point.timestampMs,
            accuracyMeters = point.accuracyMeters,
            propsJson = point.propsJson
        )
    }

    private fun scheduleReconnect(
        sessionId: Long,
        failureClass: StreamingFailureClass,
        failureReason: String
    ) {
        if (currentTrackerIds.isEmpty()) return
        if (failureClass == StreamingFailureClass.PERMANENT) return
        applyLifecycleEvent(
            event = StreamingLifecycleEvent.RecoverableFailure,
            activeTrackerIds = currentTrackerIds,
            failureReason = failureReason
        )
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            val delayMs = StreamingLifecycleOrchestrator.nextReconnectDelayMs(
                reconnectAttempt = lifecycle.reconnectAttempt,
                failureClass = failureClass
            )
            delay(delayMs)
            if (sessionId == connectionSessionId && currentTrackerIds.isNotEmpty()) {
                applyLifecycleEvent(
                    event = StreamingLifecycleEvent.RetryRequested,
                    activeTrackerIds = currentTrackerIds
                )
                connect(sessionId)
            }
        }
    }

    /**
     * Close WebSocket and cancel reconnect jobs.
     * Does NOT update isRunning (the caller is responsible for that).
     */
    private fun disconnectWebSocket() {
        connectJob?.cancel()
        connectJob = null
        try {
            webSocket?.close(1000, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close streaming websocket cleanly", e)
        }
        webSocket = null
        currentTrackerIds = emptySet()
        currentTrackerName = null
    }

    private fun updateRuntimeState(
        running: Boolean,
        lifecycleState: TrackingLifecycleState,
        activeTrackerIds: Set<String>,
        failureReason: String? = null
    ) {
        isRunning = running
        LiveStreamRuntimeStateStore.update {
            it.copy(
                isRunning = running,
                lifecycleState = lifecycleState,
                activeTrackerIds = activeTrackerIds,
                failureReason = failureReason
            )
        }
    }

    private fun applyLifecycleEvent(
        event: StreamingLifecycleEvent,
        activeTrackerIds: Set<String>,
        failureReason: String? = null
    ) {
        lifecycle = StreamingLifecycleOrchestrator.transition(
            current = lifecycle,
            event = event,
            failureReason = failureReason
        )
        updateRuntimeState(
            running = lifecycle.lifecycleState == TrackingLifecycleState.RUNNING,
            lifecycleState = lifecycle.lifecycleState,
            activeTrackerIds = activeTrackerIds,
            failureReason = lifecycle.failureReason
        )
    }

    private fun persistStreamingSession(trackerIds: Set<String>, trackerName: String?) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(PREFS_TRACKER_IDS, trackerIds.joinToString(","))
            .putString(PREFS_TRACKER_NAME, trackerName)
            .apply()
    }

    private fun restoreLastStreamingSession(): Pair<Set<String>, String?>? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val idsCsv = prefs.getString(PREFS_TRACKER_IDS, "").orEmpty()
        val ids = idsCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (ids.isEmpty()) return null
        return ids to prefs.getString(PREFS_TRACKER_NAME, null)
    }

    private fun emitStreamingError(message: String) {
        sendBroadcast(
            Intent(ACTION_STREAMING_ERROR).apply {
                setPackage(packageName)
                putExtra(EXTRA_STREAMING_ERROR_MESSAGE, message)
            }
        )
    }

    private fun emitStreamingErrorIfNeeded(message: String) {
        if (lifecycle.lifecycleState != TrackingLifecycleState.FAILED || lifecycle.failureReason != message) {
            emitStreamingError(message)
        }
    }

    private class TrackersWebSocketListener(
        private val filterTrackIds: Set<String>,
        private val onPoint: (StreamingTrackPoint) -> Unit,
        private val onDisconnect: () -> Unit = {}
    ) : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val parsed = StreamingTrackPointParser.parseTrackUpdatedMessage(text) ?: return
                if (parsed.trackId !in filterTrackIds) return
                onPoint(parsed)
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

object StreamingTrackPointParser {
    fun parseTrackUpdatedMessage(rawJson: String): StreamingTrackPoint? {
        val json = JSONObject(rawJson)
        val module = json.optString("module", "")
        val type = json.optString("type", "")
        if (module != "live_track" || type != "track_updated") return null
        val data = json.optJSONObject("data") ?: return null
        val trackId = data.optString("track_id", "")
        if (trackId.isBlank()) return null
        val pointArr = data.optJSONArray("point") ?: return null
        if (pointArr.length() < 2) return null
        val lon = pointArr.getDouble(0)
        val lat = pointArr.getDouble(1)
        val ts = if (pointArr.length() >= 3) pointArr.getLong(2) else 0L
        val props = data.optJSONObject("props")
        val acc = props?.optDouble("acc", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat()
        val propsJson = props?.takeIf { props.length() > 0 }?.toString()
        return StreamingTrackPoint(
            trackId = trackId,
            lon = lon,
            lat = lat,
            timestampMs = ts,
            accuracyMeters = acc,
            propsJson = propsJson
        )
    }
}

data class StreamingTrackPoint(
    val trackId: String,
    val lon: Double,
    val lat: Double,
    val timestampMs: Long,
    val accuracyMeters: Float? = null,
    /** JSON object string of extended point params (props) for params UI. */
    val propsJson: String? = null
)
