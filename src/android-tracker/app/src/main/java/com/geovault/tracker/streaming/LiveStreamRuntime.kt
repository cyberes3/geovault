package com.geovault.tracker.streaming

import android.app.Service
import android.content.Intent
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.service.GeoVaultForegroundServiceShell
import com.geovault.tracker.LiveTrackStreamingService
import com.geovault.tracker.R
import com.geovault.tracker.StreamingSessionGuard
import com.geovault.tracker.StreamingSessionReuseDecision
import com.geovault.tracker.TrackingNotificationChannels
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.StreamingFailureClass
import com.geovault.tracker.policy.AdmissionPipeline
import com.geovault.tracker.policy.RemoteStreamIngressPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * Command handling, health, and liveness ticks for [LiveTrackStreamingService].
 * Socket connect/reconnect lives on [LiveStreamConnection]. This is the only writer of
 * [ConnectionHealth] via [reportHealth].
 */
internal class LiveStreamRuntime(
    private val service: Service,
    private val repository: LiveStreamSubscriptionRepository,
    val admission: AdmissionPipeline,
    val persist: LiveStreamPersistPort,
    val host: LiveStreamHostPort,
    val notifier: LiveStreamForeground,
    val foreground: GeoVaultForegroundServiceShell,
) {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var currentTrackerIds: Set<String> = emptySet()
    private var currentTrackerName: String? = null
    private var connectionPhase = ConnectionPhase.IDLE
    private var reconnectAttempt = 0
    private var lastFailureReason: String? = null
    private var hasConnectedThisProcess = false
    private val sessionGuard = StreamingSessionGuard.createDefault()
    private val stateLock = Any()
    private var intentionalStop = false
    private var connection: LiveStreamConnection
    private val ingress = LiveStreamIngress(
        admission = admission,
        currentTrackerIds = { currentTrackerIdsSnapshot() },
        isCurrentSocket = { sessionId, socket -> connection.isCurrentSocket(sessionId, socket) },
        currentSessionId = { connection.currentSessionId() },
    )

    init {
        connection = LiveStreamConnection(
            scope = serviceScope,
            stateLock = stateLock,
            sessionGuard = sessionGuard,
            ingress = ingress,
            host = object : LiveStreamConnection.Host {
                override fun currentTrackerIds(): Set<String> = currentTrackerIdsSnapshot()
                override fun reconnectAttempt(): Int = synchronized(stateLock) { reconnectAttempt }
                override fun authFailedReason(): String = service.getString(R.string.error_streaming_auth_failed)
                override fun unreachableReason(): String = service.getString(R.string.error_server_unreachable)
                override fun resolveFailureClass(reported: StreamingFailureClass): StreamingFailureClass {
                    return resolveAuthFailureClass(reported)
                }
                override fun onLifecycle(event: StreamEvent, trackerIds: Set<String>, reason: String?) {
                    applyLifecycleEvent(event, trackerIds, reason)
                }
                override fun failPermanently(trackerIds: Set<String>, reason: String) {
                    failPermanentlyLocked(trackerIds, reason)
                }
            },
        )
    }
    private val network = LiveStreamNetwork(
        service = service,
        onAvailableWhileWaiting = { triggerFastReconnectIfWaiting() },
    )
    private val liveness = LiveStreamLiveness(
        scope = serviceScope,
        onTick = {
            sendAppPingIfRunning()
            checkLivenessAndReconnectIfStale()
        },
    )

    fun reportHealth(health: ConnectionHealth) {
        repository.reportConnectionHealth(health)
    }

    fun commandFromAction(action: String?): LiveStreamCommand? {
        return when (action) {
            LiveTrackStreamingService.ACTION_START -> LiveStreamCommand.Apply
            LiveTrackStreamingService.ACTION_STOP -> LiveStreamCommand.Stop
            LiveTrackStreamingService.ACTION_RESHOW_FOREGROUND -> LiveStreamCommand.Reshow
            else -> null
        }
    }

    fun onCreate() {
        liveness.start()
        network.register()
    }

    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground()
        if (intent == null) {
            val (restoredTrackerIds, restoredTrackerName) = persist.read()
            if (restoredTrackerIds.isEmpty()) {
                GeoVaultCaptureLog.w(TAG, "Null intent received with no persisted stream targets; stopping streaming service")
                service.stopSelf()
                return Service.START_NOT_STICKY
            }
            GeoVaultCaptureLog.i(TAG, "Null intent restored live streaming targets count=${restoredTrackerIds.size}")
            return startStreamingTargets(restoredTrackerIds, restoredTrackerName)
        }
        return when (commandFromAction(intent.action)) {
            LiveStreamCommand.Apply -> {
                val snapshot = repository.state.value
                startStreamingTargets(snapshot.mergedTargets, snapshot.displayName)
            }
            LiveStreamCommand.Stop -> {
                stopStreamingSession()
                Service.START_NOT_STICKY
            }
            LiveStreamCommand.Reshow -> {
                val snapshot = synchronized(stateLock) {
                    Triple(connectionPhase, currentTrackerIds, currentTrackerName)
                }
                val shouldReshow = snapshot.second.isNotEmpty() &&
                    snapshot.first != ConnectionPhase.IDLE
                if (shouldReshow) {
                    promoteForeground(snapshot.third, snapshot.second.size)
                }
                Service.START_STICKY
            }
            null -> {
                GeoVaultCaptureLog.w(TAG, "Unexpected onStartCommand action=${intent.action}; stopping service")
                service.stopSelf()
                Service.START_NOT_STICKY
            }
        }
    }

    fun onTaskRemoved() {
        repository.clearAllLeases(ClearReason.TASK_REMOVED)
        persist.clear()
        stopStreamingSession()
    }

    fun onTimeout() {
        repository.clearAllLeases(ClearReason.TIMEOUT)
        stopStreamingSession()
    }

    fun onDestroy() {
        liveness.stop()
        network.unregister()
        disconnectWebSocket()
        val shouldReportUnexpected = synchronized(stateLock) {
            !intentionalStop && connectionPhase != ConnectionPhase.IDLE
        }
        if (shouldReportUnexpected) {
            applyLifecycleEvent(
                event = StreamEvent.RecoverableFailure,
                activeTrackerIds = currentTrackerIdsSnapshot(),
                failureReason = "Unexpected streaming service teardown",
            )
        }
        connection.shutdownClient()
        serviceJob.cancel()
    }

    private fun promoteForeground(trackerName: String? = currentNameSnapshot(), trackerCount: Int = currentTrackerIdsSnapshot().size) {
        runCatching {
            foreground.promote(
                notification = notifier.build(trackerName, trackerCount),
                fallback = notifier.buildFallback(),
            )
        }.onFailure { error ->
            GeoVaultCaptureLog.e(TAG, "Foreground promotion failed", error)
        }
    }

    private fun triggerFastReconnectIfWaiting() {
        val trackerIds = currentTrackerIdsSnapshot()
        if (trackerIds.isEmpty()) return
        val launched = connection.retryNowIfWaiting {
            connectionPhase == ConnectionPhase.FAILED_TRANSIENT
        }
        if (!launched) return
        GeoVaultCaptureLog.i(TAG, "Network became available while awaiting backoff; fast-reconnecting")
        applyLifecycleEvent(StreamEvent.RetryRequested, trackerIds)
    }

    private fun sendAppPingIfRunning() {
        val socket = connection.socketIf { connectionPhase == ConnectionPhase.RUNNING } ?: return
        runCatching { socket.send(APP_PING_PAYLOAD) }
            .onFailure { e -> GeoVaultCaptureLog.w(TAG, "Failed to send app-level ping", e) }
    }

    private fun checkLivenessAndReconnectIfStale() {
        val trackerIds = currentTrackerIdsSnapshot()
        if (trackerIds.isEmpty()) return
        val assessment = synchronized(stateLock) {
            if (connectionPhase != ConnectionPhase.RUNNING) return
            connection.assessUnlocked(
                requestedTrackerIds = trackerIds,
                currentTrackerIds = trackerIds,
                running = connectionPhase == ConnectionPhase.RUNNING,
            )
        }
        if (assessment.decision != StreamingSessionReuseDecision.STALE_ACTIVITY) return
        StreamingDiagnostics.logWatchdogReconnect(assessment.activityAgeMs ?: 0L)
        forceReconnectDueToStaleness()
    }

    private fun forceReconnectDueToStaleness() {
        val sessionId = connection.beginStaleReconnect(
            runningUnlocked = { connectionPhase == ConnectionPhase.RUNNING },
            hasTrackersUnlocked = { currentTrackerIds.isNotEmpty() },
        ) ?: return
        val trackerIds = currentTrackerIdsSnapshot()
        if (trackerIds.isEmpty()) return
        applyLifecycleEvent(StreamEvent.RetryRequested, trackerIds)
        connection.launchConnect(sessionId)
    }

    private fun startStreamingTargets(trackerIds: Set<String>, trackerName: String?): Int {
        val effectiveTrackerIds = trackerIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        promoteForeground(trackerName, effectiveTrackerIds.size)
        if (effectiveTrackerIds.isEmpty()) {
            RemoteStreamIngressPolicy.resetRemoteSession()
            if (trackerIds.isEmpty()) {
                persist.clear()
                applyLifecycleEvent(
                    event = StreamEvent.PermanentFailure,
                    activeTrackerIds = emptySet(),
                    failureReason = service.getString(R.string.no_tracker_selected_go_to_settings)
                )
            } else {
                persist.clear()
                applyLifecycleEvent(StreamEvent.StopRequested, emptySet())
            }
            terminateStreaming()
            return Service.START_NOT_STICKY
        }
        val previousTrackerIds = currentTrackerIdsSnapshot()
        val reused = synchronized(stateLock) {
            val current = connection.assessUnlocked(
                requestedTrackerIds = effectiveTrackerIds,
                currentTrackerIds = currentTrackerIds,
                running = connectionPhase == ConnectionPhase.RUNNING,
            )
            if (current.decision == StreamingSessionReuseDecision.REUSE ||
                current.decision == StreamingSessionReuseDecision.HOT_UPDATE
            ) {
                if (!connection.hasSocketUnlocked()) {
                    return@synchronized null
                }
                currentTrackerIds = effectiveTrackerIds
                currentTrackerName = trackerName
                applyLifecycleEvent(StreamEvent.Connected, effectiveTrackerIds)
                current.decision == StreamingSessionReuseDecision.HOT_UPDATE
            } else {
                null
            }
        }
        if (reused != null) {
            RemoteStreamIngressPolicy.resetTracks(previousTrackerIds - effectiveTrackerIds)
            if (reused) {
                StreamingDiagnostics.logRosterDeltaHotUpdate(
                    previousCount = previousTrackerIds.size,
                    nextCount = effectiveTrackerIds.size,
                )
            }
            persist.commit(effectiveTrackerIds, trackerName)
            host.cancelRetry()
            return Service.START_STICKY
        }

        applyLifecycleEvent(StreamEvent.StartRequested, effectiveTrackerIds)
        disconnectWebSocket()
        synchronized(stateLock) {
            currentTrackerIds = effectiveTrackerIds
            currentTrackerName = trackerName
        }
        connection.launchConnect()
        persist.commit(effectiveTrackerIds, trackerName)
        host.cancelRetry()
        return Service.START_STICKY
    }

    private fun stopStreamingSession() {
        intentionalStop = true
        repository.clearLeasesWithoutDispatch()
        persist.clear()
        host.cancelRetry()
        disconnectWebSocket()
        RemoteStreamIngressPolicy.resetRemoteSession()
        applyLifecycleEvent(StreamEvent.StopRequested, emptySet())
        terminateStreaming()
    }

    private fun terminateStreaming() {
        intentionalStop = true
        connection.cancelAndBumpGeneration()
        foreground.stopForeground(removeNotification = true)
        service.stopSelf()
    }

    private fun failPermanentlyLocked(trackerIds: Set<String>, failureReason: String) {
        persist.clear()
        host.cancelRetry()
        applyLifecycleEvent(
            event = StreamEvent.PermanentFailure,
            activeTrackerIds = trackerIds,
            failureReason = failureReason,
        )
        terminateStreaming()
    }

    private fun resolveAuthFailureClass(reported: StreamingFailureClass): StreamingFailureClass {
        if (reported != StreamingFailureClass.AUTH) return reported
        val attempt = synchronized(stateLock) { reconnectAttempt }
        return StreamingConfig.classifyAuthFailure(attempt)
    }

    private fun disconnectWebSocket() {
        connection.disconnect {
            currentTrackerIds = emptySet()
            currentTrackerName = null
        }
    }

    private fun applyLifecycleEvent(
        event: StreamEvent,
        activeTrackerIds: Set<String>,
        failureReason: String? = null,
    ) {
        val snapshot = synchronized(stateLock) {
            connectionPhase = nextConnectionPhase(event)
            lastFailureReason = when (event) {
                StreamEvent.RecoverableFailure,
                StreamEvent.PermanentFailure -> failureReason ?: lastFailureReason
                StreamEvent.StartRequested,
                StreamEvent.Connected,
                StreamEvent.StopRequested -> null
                StreamEvent.RetryRequested -> lastFailureReason
            }
            when (event) {
                StreamEvent.StartRequested,
                StreamEvent.Connected,
                StreamEvent.StopRequested,
                StreamEvent.PermanentFailure -> {
                    reconnectAttempt = 0
                    connection.resetRetryBudgetUnlocked()
                }
                StreamEvent.RecoverableFailure -> {
                    reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(8)
                }
                StreamEvent.RetryRequested -> Unit
            }
            if (event == StreamEvent.Connected) {
                hasConnectedThisProcess = true
            }
            lastFailureReason
        }
        reportHealth(
            ConnectionHealth(
                phase = connectionPhase,
                activeTargets = activeTrackerIds,
                failureReason = snapshot,
                hasConnectedThisProcess = hasConnectedThisProcess,
            )
        )
    }

    private fun nextConnectionPhase(event: StreamEvent): ConnectionPhase {
        return when (event) {
            StreamEvent.StartRequested -> ConnectionPhase.STARTING
            StreamEvent.RetryRequested -> ConnectionPhase.RECONNECTING
            StreamEvent.Connected -> ConnectionPhase.RUNNING
            StreamEvent.RecoverableFailure -> ConnectionPhase.FAILED_TRANSIENT
            StreamEvent.PermanentFailure -> ConnectionPhase.FAILED_PERMANENT
            StreamEvent.StopRequested -> ConnectionPhase.IDLE
        }
    }

    private fun currentTrackerIdsSnapshot(): Set<String> {
        return synchronized(stateLock) { currentTrackerIds }
    }

    private fun currentNameSnapshot(): String? {
        return synchronized(stateLock) { currentTrackerName }
    }

    companion object {
        private const val TAG = "LiveTrackStreaming"
        private const val APP_PING_PAYLOAD = """{"module":"live_track","type":"ping"}"""

        fun create(service: LiveTrackStreamingService): LiveStreamRuntime {
            val application = service.application
            val services = TrackerAppServices.from(application)
            val persist = SharedPrefsLiveStreamPersistPort(service)
            return LiveStreamRuntime(
                service = service,
                repository = services.liveStreamSubscriptionRepository(),
                admission = services.admissionPipeline(),
                persist = persist,
                host = DefaultLiveStreamHostPort(service, persist),
                notifier = LiveStreamForeground(
                    service,
                    TrackingNotificationChannels.STREAMING_CHANNEL_ID,
                ),
                foreground = GeoVaultForegroundServiceShell(
                    service = service,
                    notificationId = LiveTrackStreamingService.NOTIFICATION_ID,
                    foregroundServiceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    tag = "LiveStreamRuntime",
                ),
            )
        }
    }
}
