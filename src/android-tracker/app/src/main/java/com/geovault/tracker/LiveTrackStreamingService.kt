package com.geovault.tracker

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import com.geovault.common.logging.GeoVaultCaptureLog
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
import com.geovault.tracker.policy.RemoteTrackPointAdmissionPipeline
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.policy.WireTimestampNormalizer
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.streaming.ConnectionPhase
import com.geovault.tracker.streaming.ReapplyReason
import com.geovault.tracker.streaming.StreamingConfig
import com.geovault.tracker.streaming.StreamingDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

        /**
         * App-level ping payload, answered directly (not via channel-layer broadcast) by
         * `LiveTrackOnlyConsumer.receive` on the server. This is deliberately independent of
         * OkHttp's own transport-level WebSocket ping (`WS_PING_INTERVAL_SEC`): that one only
         * proves the raw socket is alive, which OkHttp already turns into `onFailure` on timeout.
         * This one proves the server's consumer instance for *this* connection is still accepting
         * and responding to messages, decoupled entirely from whether the tracker being watched
         * has actually reported a new point recently (see [StreamingSessionGuard]).
         */
        private const val APP_PING_PAYLOAD = """{"module":"live_track","type":"ping"}"""

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
    private var retryStreakStartElapsedMs: Long = 0L
    private val sessionGuard = StreamingSessionGuard.createDefault()
    private val stateLock = Any()
    private val repository by lazy { TrackerAppServices.from(application).liveStreamSubscriptionRepository() }
    private val notifier by lazy { StreamingForegroundNotifier(this, NOTIFICATION_ID, CHANNEL_ID) }
    private var livenessWatchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        startLivenessWatchdog()
        registerNetworkCallback()
    }

    /**
     * CONNECTIVITY-DRIVEN-FAST-RECONNECT: without this, a transient failure while offline waits
     * out its full exponential backoff even after connectivity is restored well before the
     * backoff timer elapses (e.g. brief tunnel/elevator loss). Cancels the pending backoff and
     * retries immediately once the OS reports a usable network again, but only while we are
     * actually mid-backoff (FAILED with no socket) — a no-op the rest of the time.
     */
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                triggerFastReconnectIfWaiting()
            }
        }
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { e -> GeoVaultCaptureLog.w(TAG, "Failed to register connectivity callback", e) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun triggerFastReconnectIfWaiting() {
        val trackerIds = currentTrackerIdsSnapshot()
        if (trackerIds.isEmpty()) return
        // CONNECTJOB-SYNCHRONIZATION: the check, `connectJob` cancel+reassign, and session bump
        // all happen inside one `stateLock` acquisition. `setLease`'s dispatch race in
        // `LiveStreamSubscriptionRepository` was the same underlying issue: `Job.cancel()` is
        // only cooperative, so without a lock, this connectivity-callback thread could race
        // `startStreamingTargets` (main) or the watchdog (serviceScope/IO) and stomp whichever
        // one's `connectJob` reference was written last, orphaning the other's job instead of
        // actually cancelling it.
        synchronized(stateLock) {
            val isMidBackoff = lifecycle.lifecycleState == TrackingLifecycleState.FAILED && webSocket == null
            if (!isMidBackoff) return
            connectJob?.cancel()
            val sessionId = connectionSessionId.incrementAndGet()
            connectJob = serviceScope.launch { connect(sessionId) }
        }
        GeoVaultCaptureLog.i(TAG, "Network became available while awaiting backoff; fast-reconnecting")
        applyLifecycleEvent(StreamingLifecycleEvent.RetryRequested, trackerIds)
    }

    /**
     * LIVENESS-WATCHDOG: [StreamingSessionGuard]'s staleness check previously only ran inside
     * [startStreamingTargets], i.e. only when a *new* start request arrived. If the merged
     * target set never changes while the connection has silently gone quiet, nothing ever
     * re-evaluates staleness and the session can sit dead indefinitely. This loop polls
     * [sessionGuard] on a fixed interval for the lifetime of the service and forces a reconnect
     * the moment a RUNNING session goes stale, independent of whether any owner's lease has
     * changed.
     *
     * PING-DRIVEN-NOT-POINT-DRIVEN: this loop also sends the app-level [APP_PING_PAYLOAD] on
     * every tick, which is what [sessionGuard] actually measures staleness against (see
     * [handlePongReceived]). Staleness must never be keyed off `track_updated` recency: a
     * quiet-but-healthy tracker (sparse/significant-motion tracking, or simply stationary) can
     * legitimately go far longer than [StreamingConfig.sessionStaleAfterMs] between real points,
     * which previously caused this watchdog to force-reconnect a perfectly healthy connection
     * and flash the UI to "Reconnecting" on a loop.
     */
    private fun startLivenessWatchdog() {
        livenessWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(StreamingConfig.livenessWatchdogIntervalMs)
                sendAppPingIfRunning()
                checkLivenessAndReconnectIfStale()
            }
        }
    }

    private fun sendAppPingIfRunning() {
        val socket = synchronized(stateLock) {
            if (lifecycle.lifecycleState != TrackingLifecycleState.RUNNING) return
            webSocket
        } ?: return
        runCatching { socket.send(APP_PING_PAYLOAD) }
            .onFailure { e -> GeoVaultCaptureLog.w(TAG, "Failed to send app-level ping", e) }
    }

    private fun checkLivenessAndReconnectIfStale() {
        val trackerIds = currentTrackerIdsSnapshot()
        if (trackerIds.isEmpty()) return
        val assessment = synchronized(stateLock) {
            if (lifecycle.lifecycleState != TrackingLifecycleState.RUNNING) return
            sessionGuard.assess(
                requestedTrackerIds = trackerIds,
                currentTrackerIds = trackerIds,
                hasSocket = webSocket != null,
                lifecycleState = lifecycle.lifecycleState,
            )
        }
        if (assessment.decision != StreamingSessionReuseDecision.STALE_ACTIVITY) return
        StreamingDiagnostics.logWatchdogReconnect(assessment.activityAgeMs ?: 0L)
        forceReconnectDueToStaleness()
    }

    /**
     * Reconnects the current session in place without touching leases: the target set hasn't
     * changed, only the connection turned out to be a zombie. [LiveStreamSubscriptionRepository.requestReapply]
     * is still notified so a caller that keyed a UI recovery action off "did the repository ever
     * re-apply" (e.g. resume-from-background) observes this reconnect too.
     *
     * TOCTOU GUARD: [checkLivenessAndReconnectIfStale] made its staleness decision moments ago,
     * on a stale snapshot, before releasing `stateLock`. By the time this function actually runs
     * (same coroutine, but nothing prevents `stopStreamingSession`/`onStartCommand` from running
     * first on the main thread), the session it decided to reconnect may already have been torn
     * down. Re-validating `currentTrackerIds`/`lifecycleState` here -- atomically with the
     * socket null-out -- stops a stale decision from resurrecting `RECONNECTING` state (and a
     * phantom `connectJob`) for a stream the user already stopped, with nothing left afterward
     * to ever correct it back to STOPPED.
     */
    private fun forceReconnectDueToStaleness() {
        val socket = synchronized(stateLock) {
            if (currentTrackerIds.isEmpty() || lifecycle.lifecycleState != TrackingLifecycleState.RUNNING) {
                return
            }
            connectJob?.cancel()
            webSocket.also {
                webSocket = null
                sessionGuard.markDisconnected()
            }
        }
        runCatching { socket?.close(1000, "watchdog_stale_reconnect") }
        val sessionId = connectionSessionId.incrementAndGet()
        val trackerIds = currentTrackerIdsSnapshot()
        if (trackerIds.isEmpty()) return
        applyLifecycleEvent(StreamingLifecycleEvent.RetryRequested, trackerIds)
        synchronized(stateLock) { connectJob = serviceScope.launch { connect(sessionId) } }
        repository.requestReapply(ReapplyReason.STALE_CONNECTION)
    }

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
                GeoVaultCaptureLog.w(TAG, "Null intent received with no persisted stream targets; stopping streaming service")
                stopSelf()
                return START_NOT_STICKY
            }
            GeoVaultCaptureLog.i(TAG, "Null intent restored live streaming targets count=${restoredTrackerIds.size}")
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
                    notifier.show(snapshot.third, snapshot.second.size)
                }
            }

            else -> {
                GeoVaultCaptureLog.w(TAG, "Unexpected onStartCommand action=${intent.action}; stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startStreamingTargets(trackerIds: Set<String>, trackerName: String?) {
        val effectiveTrackerIds = trackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        notifier.show(trackerName, effectiveTrackerIds.size)
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
            terminateStreaming()
            return
        }
        val previousTrackerIds = currentTrackerIdsSnapshot()
        // ATOMIC ASSESS-AND-COMMIT: previously `assess()` ran under `stateLock`, the lock was
        // released, and only *then* did the REUSE/HOT_UPDATE branch re-acquire the lock to
        // commit `currentTrackerIds`/`currentTrackerName` and unconditionally report
        // `Connected` — with no re-check of `webSocket`/lifecycle in between. A concurrent
        // watchdog-triggered `forceReconnectDueToStaleness` (a different coroutine on
        // `serviceScope`) could null out `webSocket` in exactly that gap, and this path would
        // still report `Connected`/RUNNING to the repository with no live socket underneath —
        // a plausible cause of a tracker silently going stale on the map with nothing to ever
        // correct the mismatched RUNNING state. Deciding and committing inside one lock
        // acquisition means whichever side (this commit, or the watchdog's null-out) runs first
        // is the one the other observes.
        val assessment = synchronized(stateLock) {
            val current = sessionGuard.assess(
                requestedTrackerIds = effectiveTrackerIds,
                currentTrackerIds = currentTrackerIds,
                hasSocket = webSocket != null,
                lifecycleState = lifecycle.lifecycleState
            )
            if (current.decision == StreamingSessionReuseDecision.REUSE ||
                current.decision == StreamingSessionReuseDecision.HOT_UPDATE
            ) {
                // ROSTER-DELTA-HOT-UPDATE: the backend fans every subscribed-account update out
                // over one socket and the admission pipeline's subscription-scope stage filters
                // client-side against `currentTrackerIdsSnapshot` (see `publishRemotePoint`), so
                // a roster change never needs a new socket — just swap the filter set and
                // notification text in place. This also fixes the previous REUSE-path bug where
                // a same-decision reconnect skipped updating `currentTrackerName` entirely,
                // leaving the notification's title stale even though it was re-rendered.
                currentTrackerIds = effectiveTrackerIds
                currentTrackerName = trackerName
            }
            current
        }
        if (assessment.decision == StreamingSessionReuseDecision.REUSE ||
            assessment.decision == StreamingSessionReuseDecision.HOT_UPDATE
        ) {
            // SUBSCRIPTION-SCOPE ORDERING: called only *after* `currentTrackerIds` was already
            // committed above. `publishRemotePoint` gates admission on a fresh
            // `currentTrackerIdsSnapshot()` read per point -- that gate, not this policy's own
            // `subscribedTrackIds` bookkeeping, is what actually decides whether a point for a
            // dropped tracker reaches processing. Calling this before the commit left a window
            // where a point for a tracker being dropped could still pass the (stale, wider)
            // scope check on the OkHttp thread while this policy had already reset that
            // tracker's per-track anchor state, treating an about-to-be-rejected point as a
            // fresh stream start. Committing the scope first closes that window entirely.
            RemoteStreamIngressPolicy.updateSubscribedTracks(effectiveTrackerIds)
            if (assessment.decision == StreamingSessionReuseDecision.HOT_UPDATE) {
                StreamingDiagnostics.logRosterDeltaHotUpdate(
                    previousCount = previousTrackerIds.size,
                    nextCount = effectiveTrackerIds.size,
                )
            }
            applyLifecycleEvent(StreamingLifecycleEvent.Connected, effectiveTrackerIds)
            return
        }

        applyLifecycleEvent(StreamingLifecycleEvent.StartRequested, effectiveTrackerIds)
        disconnectWebSocket()
        synchronized(stateLock) {
            currentTrackerIds = effectiveTrackerIds
            currentTrackerName = trackerName
            connectJob?.cancel()
            val sessionId = connectionSessionId.incrementAndGet()
            connectJob = serviceScope.launch { connect(sessionId) }
        }
        RemoteStreamIngressPolicy.updateSubscribedTracks(effectiveTrackerIds)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopStreamingSession()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopStreamingSession() {
        repository.clearLeasesWithoutDispatch()
        MapStreamingServiceHelper.clearPersistedStreamingTargets(this)
        disconnectWebSocket()
        // STALE-ADMISSION-STATE: previously left RemoteStreamIngressPolicy's per-track ordering
        // bookkeeping untouched on stop, so it could linger across a full session boundary. Clear
        // it unconditionally here rather than relying on the next connect()'s
        // startSubscriptionSession() call, since a subsequent REUSE/HOT_UPDATE decision short-
        // circuits before that call ever runs.
        RemoteStreamIngressPolicy.updateSubscribedTracks(emptySet())
        applyLifecycleEvent(StreamingLifecycleEvent.StopRequested, emptySet())
        terminateStreaming()
    }

    /**
     * TERMINAL-TEARDOWN GUARD: every path that decides the service is permanently done
     * (PERMANENT failure escalation, exhausted retry budget, no tracker selected, explicit
     * stop) must cancel any in-flight `connectJob` and bump `connectionSessionId` *before*
     * calling `stopSelf()`. `Job.cancel()` is only cooperative — an orphaned job (one mid-`delay()`
     * in [scheduleReconnect], or one that lost a `connectJob`-reference race to a concurrent
     * caller) could otherwise wake up and attempt a connect *after* this function already
     * decided the service is finished, briefly resurrecting a stream the rest of the app
     * believes is stopped.
     */
    private fun terminateStreaming() {
        synchronized(stateLock) {
            connectJob?.cancel()
            connectJob = null
            connectionSessionId.incrementAndGet()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        livenessWatchdogJob?.cancel()
        unregisterNetworkCallback()
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
            GeoVaultCaptureLog.w(TAG, "Failed to shut down streaming OkHttpClient cleanly", error)
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
            terminateStreaming()
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
            onOpened = { openedSocket, response ->
                handleSocketOpened(sessionId, openedSocket, response)
            },
            onPoint = { socket, point -> publishRemotePoint(sessionId, socket, point) },
            onPong = { socket -> handlePongReceived(sessionId, socket) },
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
            GeoVaultCaptureLog.e(TAG, "WebSocket connect failed", e)
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
            terminateStreaming()
            return
        }
        // BOUNDED-RETRY: cap total wall-clock time spent retrying TRANSIENT/AUTH failures so a
        // permanently-unreachable server doesn't retry a background service forever. The streak
        // start is recorded the first time this failure run began (see [applyLifecycleEvent],
        // which resets it back to 0 on the next successful Connected/StartRequested/Stop).
        val streakStartElapsedMs = synchronized(stateLock) {
            if (retryStreakStartElapsedMs == 0L) {
                retryStreakStartElapsedMs = android.os.SystemClock.elapsedRealtime()
            }
            retryStreakStartElapsedMs
        }
        val streakAgeMs = android.os.SystemClock.elapsedRealtime() - streakStartElapsedMs
        if (streakAgeMs > StreamingConfig.maxTransientRetryDurationMs) {
            GeoVaultCaptureLog.w(TAG, "Transient retry budget exhausted after ${streakAgeMs}ms; escalating to permanent")
            applyLifecycleEvent(
                event = StreamingLifecycleEvent.PermanentFailure,
                activeTrackerIds = trackerIdsSnapshot,
                failureReason = failureReason,
            )
            terminateStreaming()
            return
        }
        applyLifecycleEvent(StreamingLifecycleEvent.RecoverableFailure, trackerIdsSnapshot, failureReason)
        synchronized(stateLock) {
            connectJob?.cancel()
            connectJob = serviceScope.launch {
                val delayMs = StreamingLifecycleOrchestrator.nextReconnectDelayMs(
                    reconnectAttempt = synchronized(stateLock) { lifecycle.reconnectAttempt },
                    failureClass = failureClass,
                    jitterFraction = StreamingConfig.retryJitterFraction,
                )
                delay(delayMs)
                val retryTrackerIds = currentTrackerIdsSnapshot()
                if (sessionId == connectionSessionId.get() && retryTrackerIds.isNotEmpty()) {
                    applyLifecycleEvent(StreamingLifecycleEvent.RetryRequested, retryTrackerIds)
                    connect(sessionId)
                }
            }
        }
    }

    private fun handleSocketOpened(sessionId: Long, socket: WebSocket, response: Response) {
        // WS_UPGRADE_HTTP_CODE: a successful WebSocket upgrade always reports HTTP 101 Switching
        // Protocols. Any other code means OkHttp delivered onOpen for a non-upgrade response (rare
        // server misconfig); treat it as a permanent failure rather than pretending we are RUNNING.
        if (response.code != WS_UPGRADE_HTTP_CODE) {
            GeoVaultCaptureLog.w(TAG, "Unexpected onOpen response code=${response.code}; closing")
            runCatching { socket.close(1002, "bad_upgrade") }
            handleSocketDisconnected(
                sessionId = sessionId,
                socket = socket,
                failureClass = StreamingFailureClass.PERMANENT,
                failureReason = getString(R.string.error_server_unreachable),
            )
            return
        }
        // ATOMIC MARK-CONNECTED: `sessionGuard.markConnected()` must land inside the same lock
        // acquisition as the accept check, and `currentTrackerIds` must be read from that same
        // critical section, not re-fetched afterward via a second `currentTrackerIdsSnapshot()`
        // call. Otherwise a concurrent teardown (`forceReconnectDueToStaleness`,
        // `disconnectWebSocket`) landing between the check and the mark could stamp "fresh
        // activity" on a session already being torn down, or this could report `Connected` with
        // a tracker set that changed out from under it in the gap.
        val acceptedTrackerIds = synchronized(stateLock) {
            val accepted = sessionId == connectionSessionId.get() &&
                webSocket === socket &&
                currentTrackerIds.isNotEmpty()
            if (accepted) {
                sessionGuard.markConnected()
                currentTrackerIds
            } else {
                null
            }
        }
        if (acceptedTrackerIds != null) {
            RemoteStreamIngressPolicy.markConnected(System.currentTimeMillis())
            applyLifecycleEvent(StreamingLifecycleEvent.Connected, acceptedTrackerIds)
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

    private fun handlePongReceived(sessionId: Long, socket: WebSocket) {
        synchronized(stateLock) {
            if (sessionId == connectionSessionId.get() && webSocket === socket) {
                sessionGuard.markPongReceived()
            }
        }
    }

    private fun disconnectWebSocket() {
        val socket = synchronized(stateLock) {
            connectJob?.cancel()
            connectJob = null
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
            return RetrofitClient.newAuthenticatedWebSocketClient(
                context = applicationContext,
                readTimeoutSec = WS_READ_TIMEOUT_SEC,
                pingIntervalSec = WS_PING_INTERVAL_SEC,
            ).also { builtClient -> wsHttpClient = builtClient }
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
            // BOUNDED-RETRY: a fresh connect attempt, a successful connect, or a deliberate stop
            // all end the current failure streak — the retry-duration ceiling in
            // [scheduleReconnect] only bounds *consecutive* failures, not the service's total
            // lifetime.
            when (event) {
                StreamingLifecycleEvent.StartRequested,
                StreamingLifecycleEvent.Connected,
                StreamingLifecycleEvent.StopRequested,
                StreamingLifecycleEvent.PermanentFailure -> retryStreakStartElapsedMs = 0L
                StreamingLifecycleEvent.RetryRequested,
                StreamingLifecycleEvent.RecoverableFailure -> Unit
            }
            lifecycle.failureReason
        }
        // STREAM-STATE-MACHINE: the service only reports the connection-health axis; "do we want
        // a subscription" is owned exclusively by the repository's leases (see
        // LiveStreamSubscriptionState.wantsSubscription), so there is no parallel "intent" to
        // keep in sync here anymore.
        repository.reportConnectionUpdate(
            connection = nextConnectionPhase(event),
            activeTargets = activeTrackerIds,
            failureReason = snapshot,
        )
    }

    private fun nextConnectionPhase(event: StreamingLifecycleEvent): ConnectionPhase {
        return when (event) {
            StreamingLifecycleEvent.StartRequested -> ConnectionPhase.STARTING
            StreamingLifecycleEvent.RetryRequested -> ConnectionPhase.RECONNECTING
            StreamingLifecycleEvent.Connected -> ConnectionPhase.RUNNING
            StreamingLifecycleEvent.RecoverableFailure -> ConnectionPhase.FAILED_TRANSIENT
            StreamingLifecycleEvent.PermanentFailure -> ConnectionPhase.FAILED_PERMANENT
            StreamingLifecycleEvent.StopRequested -> ConnectionPhase.IDLE
        }
    }

    private fun currentTrackerIdsSnapshot(): Set<String> {
        return synchronized(stateLock) { currentTrackerIds }
    }

    private fun publishRemotePoint(sessionId: Long, socket: WebSocket, point: StreamingTrackPoint) {
        val acceptedSocket = synchronized(stateLock) {
            sessionId == connectionSessionId.get() && webSocket === socket
        }
        if (!acceptedSocket) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update stream_point_drop reason=stale_socket track=${point.trackId.trim()} " +
                    "session=$sessionId currentSession=${connectionSessionId.get()} ts=${point.timestampMs}"
            )
            return
        }
        GeoVaultCaptureLog.d(
            TAG,
            "map_update stream_point_received track=${point.trackId.trim()} session=$sessionId " +
                "ts=${point.timestampMs} lat=${point.lat} lon=${point.lon} acc=${point.accuracyMeters}"
        )
        val acceptedEvent = RemoteTrackPointAdmissionPipeline.process(
            event = TrackPointEvent(
                source = TrackPointSource.REMOTE_STREAM,
                trackId = point.trackId,
                lon = point.lon,
                lat = point.lat,
                timestampMs = point.timestampMs,
                accuracyMeters = point.accuracyMeters,
                propsJson = point.propsJson,
                quality = TrackPointQuality.HIGH_CONFIDENCE
            ),
            subscriptionScope = currentTrackerIdsSnapshot(),
        ) ?: run {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update stream_point_rejected track=${point.trackId.trim()} session=$sessionId ts=${point.timestampMs}"
            )
            return
        }
        GeoVaultCaptureLog.d(
            TAG,
            "map_update stream_point_publish track=${acceptedEvent.trackId.trim()} session=$sessionId " +
                "ts=${acceptedEvent.timestampMs}"
        )
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
     * WebSocket listener for the live-tracker stream.
     *
     * - Subscription-scope filtering (is this track id one we currently care about) previously
     *   happened here, silently and with no diagnostics, before [onPoint] was even called. That
     *   stage now lives in [RemoteTrackPointAdmissionPipeline.process] — called from
     *   [publishRemotePoint] against a fresh [currentTrackerIdsSnapshot] per point — so every
     *   parsed message reaches [onPoint] and a scope-rejected point still gets recorded in
     *   [com.geovault.tracker.policy.RemoteTrackPointAdmissionDiagnostics] instead of vanishing.
     * - Failure classification is done here (where the HTTP response code is available) and
     *   handed back to the service via [onDisconnect], which lets the service apply the AUTH
     *   retry budget and pick the right user-facing copy without inspecting OkHttp internals.
     */
    private class TrackersWebSocketListener(
        // NAMING: these lambdas previously shadowed the WebSocketListener override names (onOpen,
        // onClosed). Kotlin resolves bare `onOpen(...)` to the member function, so the override
        // recursed into itself and crashed with StackOverflowError. Renamed to avoid the clash.
        private val onOpened: (WebSocket, Response) -> Unit = { _, _ -> },
        private val onPoint: (WebSocket, StreamingTrackPoint) -> Unit,
        private val onPong: (WebSocket) -> Unit = {},
        private val onDisconnect: (WebSocket, StreamingFailureClass, String?) -> Unit = { _, _, _ -> },
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onOpened(webSocket, response)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                if (StreamingTrackPointParser.isPongMessage(text)) {
                    onPong(webSocket)
                    return
                }
                StreamingTrackPointParser.parseTrackUpdatedMessages(text)
                    .forEach { point -> onPoint(webSocket, point) }
            } catch (e: Exception) {
                GeoVaultCaptureLog.e(TAG, "Parse track_updated failed", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            GeoVaultCaptureLog.w(TAG, "WebSocket failed: ${t.message} code=$code")
            val failureClass = classifyHttpCode(code)
            val reason = code?.let { "HTTP $it: ${t.message ?: ""}".trim() } ?: t.message
            onDisconnect(webSocket, failureClass, reason)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // CLOSE-CODE-CLASSIFICATION: this previously ignored `code` entirely and always
            // reported TRANSIENT, so a server-initiated policy-violation close (e.g. a revoked
            // session) retried forever exactly like a network blip. Mirror onFailure's HTTP-code
            // classification with the WS close-code (RFC 6455) equivalents.
            onDisconnect(webSocket, classifyCloseCode(code), reason.takeIf { it.isNotBlank() })
        }

        private fun classifyCloseCode(code: Int): StreamingFailureClass {
            return when (code) {
                // Policy Violation: the server closed the connection because it rejected who we
                // are (typically a revoked/invalidated session), not because of a network issue.
                1008 -> StreamingFailureClass.AUTH
                // Unsupported Data / Message Too Big / Mandatory Extension / Internal Error /
                // Service Restart / Try Again Later / Bad Gateway: all point at the server side,
                // not our credentials — worth retrying, never worth burning the AUTH budget on.
                1003, 1009, 1010, 1011, 1012, 1013, 1014 -> StreamingFailureClass.TRANSIENT
                // Normal Closure / Going Away / no code supplied at all: routine teardown
                // (server restart, load balancer recycle); always worth a transient retry.
                else -> StreamingFailureClass.TRANSIENT
            }
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

    /** True for the app-level pong reply to [LiveTrackStreamingService]'s liveness ping. */
    fun isPongMessage(rawJson: String): Boolean {
        val json = JSONObject(rawJson)
        return json.optString("module", "") == "live_track" && json.optString("type", "") == "pong"
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
