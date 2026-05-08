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
import com.geovault.tracker.policy.RemoteTrackPointIngress
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.WireTimestampNormalizer
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.StreamingHealth
import com.geovault.tracker.services.StreamingIntent
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
        private const val WS_UPGRADE_HTTP_CODE = 101

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
            // STREAMING TRUST: the upstream pipeline (TrackerMapSessionProjector +
            // LiveTrackStreamingReconciler / TrackerParamsStreamingController +
            // LiveTrackStreamingTargetCoordinator) is the single source of truth for which
            // trackers belong in the stream. The service trusts the persisted target list
            // verbatim — re-applying any exclusion here would silently drop the user's own
            // tracker from a previously-persisted group stream whenever the service is
            // reconstructed via START_STICKY.
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
                // STREAMING TRUST: see the START_STICKY note above; the incoming intent is
                // authoritative and we just normalize blanks/whitespace.
                val trackerIds = MapStreamingServiceHelper.sanitizeStreamingTargets(extractTrackerIds(intent))
                val trackerName = intent.getStringExtra(EXTRA_TRACKER_NAME)
                startStreamingTargets(trackerIds, trackerName)
            }

            ACTION_STOP -> {
                stopStreamingSession()
                return START_NOT_STICKY
            }

            ACTION_RESHOW_FOREGROUND -> {
                // FGS-RESHOW-PREDICATE: the previous predicate keyed off `isRunning`, which is only
                // true once the WebSocket is fully connected. That meant a notification dismiss
                // during STARTING or RECONNECTING (very common on flaky networks) silently failed
                // to reshow the FGS notification. Use "should currently be foreground" instead:
                // active targets exist and we are not already in the terminal STOPPED state.
                val snapshot = synchronized(stateLock) {
                    Triple(lifecycle.lifecycleState, currentTrackerIds, currentTrackerName)
                }
                val shouldReshow = snapshot.second.isNotEmpty() &&
                    snapshot.first != TrackingLifecycleState.STOPPED
                if (shouldReshow) {
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
        val effectiveTrackerIds = trackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        ensureStreamingChannel()
        startForegroundForStreaming(
            createNotification(trackerName, effectiveTrackerIds.size),
        )
        if (effectiveTrackerIds.isEmpty()) {
            RemoteStreamIngressPolicy.updateSubscribedTracks(emptySet())
            if (trackerIds.isEmpty()) {
                applyLifecycleEvent(
                    event = StreamingLifecycleEvent.PermanentFailure,
                    activeTrackerIds = emptySet(),
                    failureReason = getString(R.string.no_tracker_selected_go_to_settings)
                )
            } else {
                MapStreamingServiceHelper.clearPersistedStreamingTargets(this)
                applyLifecycleEvent(StreamingLifecycleEvent.StopRequested, emptySet())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        RemoteStreamIngressPolicy.updateSubscribedTracks(effectiveTrackerIds)

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopStreamingSession()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopStreamingSession() {
        MapStreamingServiceHelper.clearPersistedStreamingTargets(this)
        disconnectWebSocket()
        connectionSessionId.incrementAndGet()
        applyLifecycleEvent(StreamingLifecycleEvent.StopRequested, emptySet())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        connectJob?.cancel()
        disconnectWebSocket()
        applyLifecycleEvent(StreamingLifecycleEvent.StopRequested, emptySet())
        // OKHTTP-LIFECYCLE: OkHttpClient owns a connection pool and a dispatcher executor. If we
        // never close it, the executor threads survive each service restart and accumulate. Tear
        // both down here so the streaming service has no lingering Java threads after stop.
        shutdownStreamingHttpClient()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun shutdownStreamingHttpClient() {
        val client = synchronized(stateLock) {
            wsHttpClient.also { wsHttpClient = null }
        } ?: return
        runCatching {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            client.cache?.close()
        }.exceptionOrNull()?.let { error ->
            Log.w(TAG, "Failed to shut down streaming OkHttpClient cleanly", error)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun connect(sessionId: Long) {
        if (!serviceScope.isActive || currentTrackerIdsSnapshot().isEmpty()) return
        val token = withContext(Dispatchers.IO) {
            runCatching { GeovaultAuthManager.getValidAccessToken(applicationContext, null) }.getOrNull()
        }
        if (token.isNullOrBlank()) {
            val reason = getString(R.string.error_streaming_auth_failed)
            emitStreamingErrorIfNeeded(reason)
            val attempt = synchronized(stateLock) { lifecycle.reconnectAttempt }
            scheduleReconnect(
                sessionId = sessionId,
                failureClass = StreamingLifecycleOrchestrator.classifyAuthFailure(attempt),
                failureReason = reason,
            )
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
            filterTrackIds = ::currentTrackerIdsSnapshot,
            onOpened = { openedSocket, response ->
                handleSocketOpened(sessionId, openedSocket, response)
            },
            onPoint = { socket, point -> publishRemotePoint(sessionId, socket, point) },
            onActivity = { socket -> markSocketActivity(sessionId, socket) },
            onDisconnect = { socket, failureClass, reasonHint ->
                val effectiveClass = resolveFailureClass(failureClass)
                val reason = when (effectiveClass) {
                    StreamingFailureClass.AUTH,
                    StreamingFailureClass.PERMANENT -> getString(R.string.error_streaming_auth_failed)
                    StreamingFailureClass.TRANSIENT -> reasonHint
                        ?: getString(R.string.error_server_unreachable)
                }
                if (effectiveClass == StreamingFailureClass.AUTH ||
                    effectiveClass == StreamingFailureClass.PERMANENT
                ) {
                    emitStreamingErrorIfNeeded(reason)
                }
                handleSocketDisconnected(
                    sessionId = sessionId,
                    socket = socket,
                    failureClass = effectiveClass,
                    failureReason = reason,
                )
            },
        )
        try {
            RemoteStreamIngressPolicy.startSubscriptionSession(trackerIdsSnapshot)
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
        if (sessionId != connectionSessionId.get()) return
        val trackerIdsSnapshot = currentTrackerIdsSnapshot()
        if (trackerIdsSnapshot.isEmpty()) return
        if (failureClass == StreamingFailureClass.PERMANENT) {
            applyLifecycleEvent(
                event = StreamingLifecycleEvent.PermanentFailure,
                activeTrackerIds = trackerIdsSnapshot,
                failureReason = failureReason,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
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

    private fun handleSocketOpened(sessionId: Long, socket: WebSocket, response: Response) {
        // WS_UPGRADE_HTTP_CODE: a successful WebSocket upgrade always reports HTTP 101 Switching
        // Protocols. Any other code means OkHttp delivered onOpen for a non-upgrade response (rare
        // server misconfig); treat it as a permanent failure rather than pretending we are RUNNING.
        if (response.code != WS_UPGRADE_HTTP_CODE) {
            Log.w(TAG, "Unexpected onOpen response code=${response.code}; closing")
            runCatching { socket.close(1002, "bad_upgrade") }
            handleSocketDisconnected(
                sessionId = sessionId,
                socket = socket,
                failureClass = StreamingFailureClass.PERMANENT,
                failureReason = getString(R.string.error_server_unreachable),
            )
            return
        }
        val acceptedOpen = synchronized(stateLock) {
            sessionId == connectionSessionId.get() &&
                webSocket === socket &&
                currentTrackerIds.isNotEmpty()
        }
        if (acceptedOpen) {
            sessionGuard.markConnected()
            applyLifecycleEvent(StreamingLifecycleEvent.Connected, currentTrackerIdsSnapshot())
        } else {
            socket.close(1000, "stale_session")
        }
    }

    /**
     * Apply the AUTH retry budget. Once the streaming lifecycle has burned through
     * [StreamingLifecycleOrchestrator.MAX_AUTH_RETRY_ATTEMPTS] consecutive AUTH failures we escalate
     * to PERMANENT so the orchestrator stops looping on a token the server keeps rejecting.
     */
    private fun resolveFailureClass(reported: StreamingFailureClass): StreamingFailureClass {
        if (reported != StreamingFailureClass.AUTH) return reported
        val attempt = synchronized(stateLock) { lifecycle.reconnectAttempt }
        return StreamingLifecycleOrchestrator.classifyAuthFailure(attempt)
    }

    private fun handleSocketDisconnected(
        sessionId: Long,
        socket: WebSocket,
        failureClass: StreamingFailureClass,
        failureReason: String,
    ) {
        val acceptedDisconnect = synchronized(stateLock) {
            if (sessionId == connectionSessionId.get() && webSocket === socket) {
                webSocket = null
                sessionGuard.markDisconnected()
                true
            } else {
                false
            }
        }
        if (acceptedDisconnect) {
            scheduleReconnect(
                sessionId = sessionId,
                failureClass = failureClass,
                failureReason = failureReason,
            )
        }
    }

    private fun markSocketActivity(sessionId: Long, socket: WebSocket) {
        synchronized(stateLock) {
            if (sessionId == connectionSessionId.get() && webSocket === socket) {
                sessionGuard.markMessageReceived()
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
            lifecycle.failureReason
        }
        // STREAM-STATE-MACHINE: translate the lifecycle-orchestrator event into the (intent,
        // health) projection. The orchestrator stays in charge of retry-attempt counting and the
        // coarse lifecycle-state shape; we only adapt its output to the snapshot model here.
        val nextIntent = nextStreamingIntent(event = event, activeTrackerIds = activeTrackerIds)
        val nextHealth = nextStreamingHealth(event = event)
        LiveStreamRuntimeStateStore.update { previous ->
            previous.copy(
                intent = nextIntent ?: previous.intent,
                health = nextHealth,
                activeTrackerIds = activeTrackerIds,
                failureReason = snapshot,
            )
        }
    }

    private fun nextStreamingIntent(
        event: StreamingLifecycleEvent,
        activeTrackerIds: Set<String>,
    ): StreamingIntent? {
        return when (event) {
            StreamingLifecycleEvent.StartRequested -> StreamingIntent.Wanted(activeTrackerIds)
            StreamingLifecycleEvent.StopRequested -> StreamingIntent.Idle
            // Retry / Connected / RecoverableFailure / PermanentFailure all preserve the prior
            // intent so consumers can keep telling "we still want to be subscribed but the
            // current attempt is unhealthy" apart from "we deliberately stopped".
            StreamingLifecycleEvent.RetryRequested,
            StreamingLifecycleEvent.Connected,
            StreamingLifecycleEvent.RecoverableFailure,
            StreamingLifecycleEvent.PermanentFailure -> null
        }
    }

    private fun nextStreamingHealth(event: StreamingLifecycleEvent): StreamingHealth {
        return when (event) {
            StreamingLifecycleEvent.StartRequested -> StreamingHealth.Starting
            StreamingLifecycleEvent.RetryRequested -> StreamingHealth.Reconnecting
            StreamingLifecycleEvent.Connected -> StreamingHealth.Running
            StreamingLifecycleEvent.RecoverableFailure -> StreamingHealth.FailedTransient
            StreamingLifecycleEvent.PermanentFailure -> StreamingHealth.FailedPermanent
            StreamingLifecycleEvent.StopRequested -> StreamingHealth.Stopped
        }
    }

    private fun currentTrackerIdsSnapshot(): Set<String> {
        return synchronized(stateLock) { currentTrackerIds }
    }

    private fun publishRemotePoint(sessionId: Long, socket: WebSocket, point: StreamingTrackPoint) {
        val acceptedSocket = synchronized(stateLock) {
            sessionId == connectionSessionId.get() && webSocket === socket
        }
        if (!acceptedSocket) return
        val acceptedEvent = RemoteTrackPointIngress.process(
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
        ) ?: return
        TrackPointBus.publish(acceptedEvent)
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

    /**
     * WebSocket listener for the live-tracker stream.
     *
     * - [filterTrackIds] is a per-message supplier so subscription changes (e.g. group expansion,
     *   tracker reselection) take effect on the next inbound message rather than on the next
     *   reconnect. This eliminates the rare drop/admit anomalies that occurred when the service
     *   updated its target set without tearing the socket down.
     * - Failure classification is done here (where the HTTP response code is available) and
     *   handed back to the service via [onDisconnect], which lets the service apply the AUTH
     *   retry budget and pick the right user-facing copy without inspecting OkHttp internals.
     */
    private class TrackersWebSocketListener(
        private val filterTrackIds: () -> Set<String>,
        // NAMING: these lambdas previously shadowed the WebSocketListener override names (onOpen,
        // onClosed). Kotlin resolves bare `onOpen(...)` to the member function, so the override
        // recursed into itself and crashed with StackOverflowError. Renamed to avoid the clash.
        private val onOpened: (WebSocket, Response) -> Unit = { _, _ -> },
        private val onPoint: (WebSocket, StreamingTrackPoint) -> Unit,
        private val onActivity: (WebSocket) -> Unit = {},
        private val onDisconnect: (WebSocket, StreamingFailureClass, String?) -> Unit = { _, _, _ -> },
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onOpened(webSocket, response)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                onActivity(webSocket)
                val liveFilter = filterTrackIds()
                StreamingTrackPointParser.parseTrackUpdatedMessages(text)
                    .filter { it.trackId in liveFilter }
                    .forEach { point -> onPoint(webSocket, point) }
            } catch (e: Exception) {
                Log.e(TAG, "Parse track_updated failed", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            Log.w(TAG, "WebSocket failed: ${t.message} code=$code")
            val failureClass = classifyHttpCode(code)
            val reason = code?.let { "HTTP $it: ${t.message ?: ""}".trim() } ?: t.message
            onDisconnect(webSocket, failureClass, reason)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDisconnect(webSocket, StreamingFailureClass.TRANSIENT, reason.takeIf { it.isNotBlank() })
        }

        private fun classifyHttpCode(code: Int?): StreamingFailureClass {
            return when (code) {
                null -> StreamingFailureClass.TRANSIENT
                401, 403 -> StreamingFailureClass.AUTH
                in 400..499 -> StreamingFailureClass.PERMANENT
                else -> StreamingFailureClass.TRANSIENT
            }
        }
    }
}

object StreamingTrackPointParser {
    fun parseTrackUpdatedMessage(rawJson: String): StreamingTrackPoint? {
        return parseTrackUpdatedMessages(rawJson).firstOrNull()
    }

    fun parseTrackUpdatedMessages(rawJson: String, nowMs: Long = System.currentTimeMillis()): List<StreamingTrackPoint> {
        val json = JSONObject(rawJson)
        if (json.optString("module", "") != "live_track" || json.optString("type", "") != "track_updated") {
            return emptyList()
        }
        val data = json.optJSONObject("data") ?: return emptyList()
        val trackId = data.optString("track_id", "").trim()
        if (trackId.isBlank()) return emptyList()
        val updates = data.optJSONArray("updates")
        if (updates != null) {
            return (0 until updates.length()).mapNotNull { index ->
                val update = updates.optJSONObject(index) ?: return@mapNotNull null
                parsePoint(
                    trackId = trackId,
                    pointArr = update.optJSONArray("point"),
                    props = update.optJSONObject("props"),
                    nowMs = nowMs,
                )
            }
        }
        return listOfNotNull(
            parsePoint(
                trackId = trackId,
                pointArr = data.optJSONArray("point"),
                props = data.optJSONObject("props"),
                nowMs = nowMs,
            )
        )
    }

    private fun parsePoint(
        trackId: String,
        pointArr: org.json.JSONArray?,
        props: JSONObject?,
        nowMs: Long,
    ): StreamingTrackPoint? {
        if (pointArr == null || pointArr.length() < 2) return null
        val lon = pointArr.getDouble(0)
        val lat = pointArr.getDouble(1)
        val ts = if (pointArr.length() >= 3) {
            WireTimestampNormalizer.normalizeToMilliseconds(pointArr.optLong(2, 0L)) ?: nowMs
        } else {
            nowMs
        }
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
