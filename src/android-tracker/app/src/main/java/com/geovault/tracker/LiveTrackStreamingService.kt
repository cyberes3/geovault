package com.geovault.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.RemoteStreamIngressPolicy
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LiveTrackStreamingService : Service() {
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

        @Volatile
        @JvmStatic
        var isRunning = false
            private set
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var webSocket: WebSocket? = null
    private var currentTrackerIds: Set<String> = emptySet()
    private var currentTrackerName: String? = null
    private var connectJob: Job? = null
    private var wsHttpClient: OkHttpClient? = null
    private val connectionSessionId = AtomicLong(0L)
    private var lifecycle = StreamingLifecycleState()
    private val sessionGuard = StreamingSessionGuard.createDefault()
    private val stateLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val (restoredTrackerIds, restoredTrackerName) = MapStreamingServiceHelper.persistedTargets(this)
            if (restoredTrackerIds.isEmpty()) {
                Log.w(TAG, "Null intent received with no persisted stream targets; stopping streaming service")
                stopSelf()
                return START_NOT_STICKY
            }
            Log.i(TAG, "Null intent restored live streaming targets count=${restoredTrackerIds.size}")
            startStreamingTargets(restoredTrackerIds, restoredTrackerName)
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val trackerIds = extractTrackerIds(intent)
                val trackerName = intent.getStringExtra(EXTRA_TRACKER_NAME)
                startStreamingTargets(trackerIds, trackerName)
            }

            ACTION_STOP -> {
                disconnectWebSocket()
                connectionSessionId.incrementAndGet()
                applyLifecycleEvent(StreamingLifecycleEvent.StopRequested, emptySet())
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_STICKY
            }

            ACTION_RESHOW_FOREGROUND -> {
                val snapshot = synchronized(stateLock) {
                    Triple(isRunning, currentTrackerIds, currentTrackerName)
                }
                if (snapshot.first && snapshot.second.isNotEmpty()) {
                    ensureStreamingChannel()
                    startForegroundForStreaming(
                        createNotification(snapshot.third, snapshot.second.size),
                    )
                }
            }

            else -> {
                Log.w(TAG, "Unexpected onStartCommand action=${intent.action}; stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startStreamingTargets(trackerIds: Set<String>, trackerName: String?) {
        val effectiveTrackerIds = removeLocallyRecordedTracker(trackerIds)
        ensureStreamingChannel()
        startForegroundForStreaming(
            createNotification(trackerName, effectiveTrackerIds.size),
        )
        if (effectiveTrackerIds.isEmpty()) {
            if (trackerIds.isEmpty()) {
                applyLifecycleEvent(
                    event = StreamingLifecycleEvent.PermanentFailure,
                    activeTrackerIds = emptySet(),
                    failureReason = getString(R.string.no_tracker_selected_go_to_settings)
                )
            } else {
                applyLifecycleEvent(StreamingLifecycleEvent.StopRequested, emptySet())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val assessment = synchronized(stateLock) {
            sessionGuard.assess(
                requestedTrackerIds = effectiveTrackerIds,
                currentTrackerIds = currentTrackerIds,
                hasSocket = webSocket != null,
                lifecycleState = lifecycle.lifecycleState
            )
        }
        if (assessment.decision == StreamingSessionReuseDecision.REUSE) {
            applyLifecycleEvent(StreamingLifecycleEvent.Connected, effectiveTrackerIds)
            return
        }

        applyLifecycleEvent(StreamingLifecycleEvent.StartRequested, effectiveTrackerIds)
        disconnectWebSocket()
        synchronized(stateLock) {
            currentTrackerIds = effectiveTrackerIds
            currentTrackerName = trackerName
        }
        val sessionId = connectionSessionId.incrementAndGet()
        connectJob?.cancel()
        connectJob = serviceScope.launch { connect(sessionId) }
    }

    private fun removeLocallyRecordedTracker(trackerIds: Set<String>): Set<String> {
        val runtime = TrackingRuntimeStateStore.state.value
        val selectedTrackerId = runtime.selectedTrackerId.trim()
        if (!runtime.isRunning || selectedTrackerId.isEmpty()) return trackerIds
        return trackerIds.filterNot { it.trim() == selectedTrackerId }.toSet()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        disconnectWebSocket()
        connectionSessionId.incrementAndGet()
        applyLifecycleEvent(StreamingLifecycleEvent.StopRequested, emptySet())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        connectJob?.cancel()
        disconnectWebSocket()
        applyLifecycleEvent(StreamingLifecycleEvent.StopRequested, emptySet())
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun connect(sessionId: Long) {
        if (!serviceScope.isActive || currentTrackerIdsSnapshot().isEmpty()) return
        val token = withContext(Dispatchers.IO) {
            runCatching { GeovaultAuthManager.getValidAccessToken(applicationContext, null) }.getOrNull()
        }
        if (token.isNullOrBlank()) {
            val reason = getString(R.string.error_server_unreachable)
            emitStreamingErrorIfNeeded(reason)
            scheduleReconnect(sessionId, StreamingFailureClass.AUTH, reason)
            return
        }
        val serverUrl = GeovaultAuthManager.getServerUrl(applicationContext).trimEnd('/')
        if (serverUrl.isBlank()) {
            val reason = getString(R.string.error_server_unreachable)
            emitStreamingError(reason)
            applyLifecycleEvent(StreamingLifecycleEvent.PermanentFailure, currentTrackerIdsSnapshot(), reason)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val wsScheme = when {
            serverUrl.startsWith("https://") -> "wss"
            serverUrl.startsWith("http://") -> "ws"
            else -> "wss"
        }
        val base = serverUrl.removePrefix("https://").removePrefix("http://")
        val request = Request.Builder()
            .url("$wsScheme://$base/ws/extensions/live-track/trackers-live/")
            .addHeader("Authorization", "Bearer $token")
            .build()
        val trackerIdsSnapshot = currentTrackerIdsSnapshot()
        val listener = TrackersWebSocketListener(
            filterTrackIds = trackerIdsSnapshot,
            onPoint = { point -> publishRemotePoint(point) },
            onActivity = { sessionGuard.markMessageReceived() },
            onDisconnect = {
                sessionGuard.markDisconnected()
                scheduleReconnect(
                    sessionId = sessionId,
                    failureClass = StreamingFailureClass.TRANSIENT,
                    failureReason = getString(R.string.error_server_unreachable),
                )
            }
        )
        try {
            RemoteStreamIngressPolicy.resetTracks(trackerIdsSnapshot)
            val socket = getWebSocketClient().newWebSocket(request, listener)
            val accepted = synchronized(stateLock) {
                if (sessionId == connectionSessionId.get() && currentTrackerIds.isNotEmpty()) {
                    webSocket = socket
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                socket.close(1000, "stale_session")
                return
            }
            sessionGuard.markConnected()
            applyLifecycleEvent(StreamingLifecycleEvent.Connected, currentTrackerIdsSnapshot())
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connect failed", e)
            scheduleReconnect(
                sessionId = sessionId,
                failureClass = StreamingFailureClass.TRANSIENT,
                failureReason = e.message ?: getString(R.string.error_server_unreachable),
            )
        }
    }

    private fun scheduleReconnect(sessionId: Long, failureClass: StreamingFailureClass, failureReason: String) {
        val trackerIdsSnapshot = currentTrackerIdsSnapshot()
        if (trackerIdsSnapshot.isEmpty() || failureClass == StreamingFailureClass.PERMANENT) return
        applyLifecycleEvent(StreamingLifecycleEvent.RecoverableFailure, trackerIdsSnapshot, failureReason)
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            val delayMs = StreamingLifecycleOrchestrator.nextReconnectDelayMs(
                reconnectAttempt = synchronized(stateLock) { lifecycle.reconnectAttempt },
                failureClass = failureClass
            )
            delay(delayMs)
            val retryTrackerIds = currentTrackerIdsSnapshot()
            if (sessionId == connectionSessionId.get() && retryTrackerIds.isNotEmpty()) {
                applyLifecycleEvent(StreamingLifecycleEvent.RetryRequested, retryTrackerIds)
                connect(sessionId)
            }
        }
    }

    private fun disconnectWebSocket() {
        connectJob?.cancel()
        connectJob = null
        val socket = synchronized(stateLock) {
            webSocket.also {
                webSocket = null
                sessionGuard.markDisconnected()
                currentTrackerIds = emptySet()
                currentTrackerName = null
            }
        }
        runCatching { socket?.close(1000, null) }
    }

    private fun getWebSocketClient(): OkHttpClient {
        synchronized(stateLock) {
            val existingClient = wsHttpClient
            if (existingClient != null) {
                return existingClient
            }
            return RetrofitClient.getAuthenticatedOkHttpClient(applicationContext).newBuilder()
                .readTimeout(WS_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .pingInterval(WS_PING_INTERVAL_SEC, TimeUnit.SECONDS)
                .build()
                .also { builtClient -> wsHttpClient = builtClient }
        }
    }

    private fun applyLifecycleEvent(
        event: StreamingLifecycleEvent,
        activeTrackerIds: Set<String>,
        failureReason: String? = null,
    ) {
        val snapshot = synchronized(stateLock) {
            lifecycle = StreamingLifecycleOrchestrator.transition(
                current = lifecycle,
                event = event,
                failureReason = failureReason
            )
            val running = lifecycle.lifecycleState == TrackingLifecycleState.RUNNING
            isRunning = running
            Triple(running, lifecycle.lifecycleState, lifecycle.failureReason)
        }
        LiveStreamRuntimeStateStore.update {
            it.copy(
                isRunning = snapshot.first,
                lifecycleState = snapshot.second,
                activeTrackerIds = activeTrackerIds,
                failureReason = snapshot.third
            )
        }
    }

    private fun currentTrackerIdsSnapshot(): Set<String> {
        return synchronized(stateLock) { currentTrackerIds }
    }

    private fun publishRemotePoint(point: StreamingTrackPoint) {
        TrackPointBus.publish(
            TrackPointEvent(
                source = TrackPointSource.REMOTE_STREAM,
                trackId = point.trackId,
                lon = point.lon,
                lat = point.lat,
                timestampMs = point.timestampMs,
                accuracyMeters = point.accuracyMeters,
                propsJson = point.propsJson,
                quality = TrackPointQuality.HIGH_CONFIDENCE
            )
        )
    }

    private fun extractTrackerIds(intent: Intent): Set<String> {
        val idsFromArray = intent.getStringArrayListExtra(EXTRA_TRACKER_IDS)
            ?.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            ?.toSet()
            ?: emptySet()
        if (idsFromArray.isNotEmpty()) return idsFromArray
        return intent.getStringExtra(EXTRA_TRACKER_ID)?.trim()?.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: emptySet()
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
        val shouldEmit = synchronized(stateLock) {
            lifecycle.lifecycleState != TrackingLifecycleState.FAILED || lifecycle.failureReason != message
        }
        if (shouldEmit) {
            emitStreamingError(message)
        }
    }

    /**
     * After `startForegroundService`, the system requires `startForeground` within a short
     * deadline. Use a minimal notification if the primary notification cannot be posted.
     */
    private fun startForegroundForStreaming(notification: Notification) {
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed; using minimal FGS notification", e)
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    createMinimalStreamingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            }.exceptionOrNull()?.let { inner ->
                Log.e(TAG, "Minimal startForeground also failed", inner)
                throw inner
            }
        }
    }

    private fun createMinimalStreamingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.live_track_streaming_title))
            .setContentText(getString(R.string.live_track_streaming_text_anon))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureStreamingChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.live_track_streaming_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.live_track_streaming_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            enableLights(false)
            setLockscreenVisibility(Notification.VISIBILITY_SECRET)
            setBypassDnd(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(trackerName: String?, trackerCount: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, LiveTrackStreamingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
            trackerName?.isNotBlank() == true -> getString(R.string.live_track_streaming_text, trackerName)
            trackerCount > 1 -> String.format(Locale.US, getString(R.string.live_track_streaming_text_many), trackerCount)
            else -> getString(R.string.live_track_streaming_text_anon)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop_streaming), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(dismissPendingIntent)
            .build()
    }

    private class TrackersWebSocketListener(
        private val filterTrackIds: Set<String>,
        private val onPoint: (StreamingTrackPoint) -> Unit,
        private val onActivity: () -> Unit = {},
        private val onDisconnect: () -> Unit = {},
    ) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                onActivity()
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
        if (json.optString("module", "") != "live_track" || json.optString("type", "") != "track_updated") return null
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
        val propsJson = props?.takeIf { it.length() > 0 }?.toString()
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
    val propsJson: String? = null,
)
