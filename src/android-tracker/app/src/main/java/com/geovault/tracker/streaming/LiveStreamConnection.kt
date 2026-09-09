package com.geovault.tracker.streaming

import android.os.SystemClock
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.common.net.GeoVaultHttp
import com.geovault.tracker.StreamingSessionAssessment
import com.geovault.tracker.StreamingSessionGuard
import com.geovault.tracker.location.StreamingFailureClass
import com.geovault.tracker.policy.RemoteStreamIngressPolicy
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal enum class StreamEvent {
    StartRequested,
    RetryRequested,
    Connected,
    RecoverableFailure,
    PermanentFailure,
    StopRequested,
}

/**
 * OkHttp client, session generation, connect/open/close, and reconnect delay/budget.
 * Watchdog, failure, close, and mid-backoff retry bump generation under [stateLock].
 * [StreamEvent.Connected] is only applied when [webSocket] is non-null in that same lock.
 */
internal class LiveStreamConnection(
    private val scope: CoroutineScope,
    private val stateLock: Any,
    private val sessionGuard: StreamingSessionGuard,
    private val ingress: LiveStreamIngress,
    private val host: Host,
) {
    interface Host {
        fun currentTrackerIds(): Set<String>
        fun reconnectAttempt(): Int
        fun authFailedReason(): String
        fun unreachableReason(): String
        fun resolveFailureClass(reported: StreamingFailureClass): StreamingFailureClass
        fun onLifecycle(event: StreamEvent, trackerIds: Set<String>, reason: String? = null)
        fun failPermanently(trackerIds: Set<String>, reason: String)
    }

    private var webSocket: WebSocket? = null
    private var wsHttpClient: OkHttpClient? = null
    private val connectionSessionId = AtomicLong(0L)
    private var connectJob: Job? = null
    private var lastAccessToken: String? = null
    private var forceRefreshNextToken: Boolean = false
    private var retryStreakStartElapsedMs: Long = 0L

    fun currentSessionId(): Long = connectionSessionId.get()

    fun hasSocketUnlocked(): Boolean = webSocket != null

    fun isCurrentSocket(sessionId: Long, socket: WebSocket): Boolean {
        return synchronized(stateLock) {
            sessionId == connectionSessionId.get() && webSocket === socket
        }
    }

    fun socketIf(unlockedPredicate: () -> Boolean): WebSocket? {
        return synchronized(stateLock) {
            if (!unlockedPredicate()) null else webSocket
        }
    }

    fun assessUnlocked(
        requestedTrackerIds: Set<String>,
        currentTrackerIds: Set<String>,
        running: Boolean,
    ): StreamingSessionAssessment {
        return sessionGuard.assess(
            requestedTrackerIds = requestedTrackerIds,
            currentTrackerIds = currentTrackerIds,
            hasSocket = webSocket != null,
            running = running,
        )
    }

    fun launchConnect() {
        synchronized(stateLock) {
            connectJob?.cancel()
            val sessionId = connectionSessionId.incrementAndGet()
            connectJob = scope.launch { connect(sessionId) }
        }
    }

    fun retryNowIfWaiting(isMidBackoffUnlocked: () -> Boolean): Boolean {
        return synchronized(stateLock) {
            if (!isMidBackoffUnlocked() || webSocket != null) return false
            connectJob?.cancel()
            val sessionId = connectionSessionId.incrementAndGet()
            connectJob = scope.launch { connect(sessionId) }
            true
        }
    }

    fun beginStaleReconnect(runningUnlocked: () -> Boolean, hasTrackersUnlocked: () -> Boolean): Long? {
        val socket = synchronized(stateLock) {
            if (!hasTrackersUnlocked() || !runningUnlocked()) {
                return null
            }
            connectJob?.cancel()
            webSocket.also {
                webSocket = null
                sessionGuard.markDisconnected()
            }
        }
        runCatching { socket?.close(1000, "watchdog_stale_reconnect") }
        return connectionSessionId.incrementAndGet()
    }

    fun launchConnect(sessionId: Long) {
        synchronized(stateLock) {
            connectJob?.cancel()
            connectJob = scope.launch { connect(sessionId) }
        }
    }

    fun disconnect(clearTrackersUnlocked: () -> Unit) {
        val socket = synchronized(stateLock) {
            connectJob?.cancel()
            connectJob = null
            webSocket.also {
                webSocket = null
                sessionGuard.markDisconnected()
                clearTrackersUnlocked()
            }
        }
        runCatching { socket?.close(1000, null) }
    }

    fun cancelAndBumpGeneration() {
        synchronized(stateLock) {
            connectJob?.cancel()
            connectJob = null
            connectionSessionId.incrementAndGet()
        }
    }

    fun resetRetryBudgetUnlocked() {
        retryStreakStartElapsedMs = 0L
    }

    fun markPongIfCurrent(sessionId: Long, socket: WebSocket) {
        synchronized(stateLock) {
            if (sessionId == connectionSessionId.get() && webSocket === socket) {
                sessionGuard.markLivenessReceived()
            }
        }
    }

    fun shutdownClient() {
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

    suspend fun connect(sessionId: Long) {
        if (!scope.isActive || host.currentTrackerIds().isEmpty()) return
        val forceRefresh = synchronized(stateLock) {
            val refresh = forceRefreshNextToken
            forceRefreshNextToken = false
            refresh to lastAccessToken
        }
        val token = withContext(Dispatchers.IO) {
            runCatching {
                val session = GeoVaultAuthSession.get()
                if (forceRefresh.first) {
                    session.refreshAccessToken(forceRefresh.second)
                } else {
                    session.getValidAccessToken(null)
                }
            }.getOrNull()
        }
        if (token.isNullOrBlank()) {
            val reason = host.authFailedReason()
            GeoVaultCaptureLog.e(TAG, reason)
            val attempt = host.reconnectAttempt()
            scheduleReconnect(
                sessionId = sessionId,
                failureClass = StreamingConfig.classifyAuthFailure(attempt),
                failureReason = reason,
            )
            return
        }
        synchronized(stateLock) { lastAccessToken = token }
        val parsed = GeoVaultAuthSession.get().serverUrl()
        if (parsed == null) {
            val reason = host.unreachableReason()
            GeoVaultCaptureLog.e(TAG, reason)
            host.failPermanently(host.currentTrackerIds(), reason)
            return
        }
        val httpUrl = parsed.resolve("/ws/extensions/live-track/trackers-live/")
        val wsUrl = when {
            httpUrl.startsWith("https://") -> "wss://" + httpUrl.removePrefix("https://")
            httpUrl.startsWith("http://") -> "ws://" + httpUrl.removePrefix("http://")
            else -> httpUrl
        }
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        val listener = ingress.listener(
            sessionId = sessionId,
            onOpened = { openedSocket, response ->
                handleSocketOpened(sessionId, openedSocket, response)
            },
            onPong = { socket -> markPongIfCurrent(sessionId, socket) },
            onDisconnect = { socket, failureClass, reasonHint ->
                val effectiveClass = host.resolveFailureClass(failureClass)
                val reason = when (effectiveClass) {
                    StreamingFailureClass.AUTH,
                    StreamingFailureClass.PERMANENT -> host.authFailedReason()
                    StreamingFailureClass.TRANSIENT -> reasonHint ?: host.unreachableReason()
                }
                if (effectiveClass == StreamingFailureClass.AUTH ||
                    effectiveClass == StreamingFailureClass.PERMANENT
                ) {
                    GeoVaultCaptureLog.e(TAG, reason)
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
            RemoteStreamIngressPolicy.resetRemoteSession()
            val socket = webSocketClient().newWebSocket(request, listener)
            val accepted = synchronized(stateLock) {
                if (sessionId == connectionSessionId.get() && host.currentTrackerIds().isNotEmpty()) {
                    webSocket = socket
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                socket.close(1000, "stale_session")
            }
        } catch (e: Exception) {
            GeoVaultCaptureLog.e(TAG, "WebSocket connect failed", e)
            scheduleReconnect(
                sessionId = sessionId,
                failureClass = StreamingFailureClass.TRANSIENT,
                failureReason = e.message ?: host.unreachableReason(),
            )
        }
    }

    fun scheduleReconnect(sessionId: Long, failureClass: StreamingFailureClass, failureReason: String) {
        if (sessionId != connectionSessionId.get()) return
        val trackerIdsSnapshot = host.currentTrackerIds()
        if (trackerIdsSnapshot.isEmpty()) return
        if (failureClass == StreamingFailureClass.AUTH) {
            synchronized(stateLock) { forceRefreshNextToken = true }
        }
        if (failureClass == StreamingFailureClass.PERMANENT) {
            host.failPermanently(trackerIdsSnapshot, failureReason)
            return
        }
        val streakAgeMs = synchronized(stateLock) {
            if (retryStreakStartElapsedMs == 0L) {
                retryStreakStartElapsedMs = SystemClock.elapsedRealtime()
            }
            SystemClock.elapsedRealtime() - retryStreakStartElapsedMs
        }
        if (streakAgeMs > StreamingConfig.maxTransientRetryDurationMs) {
            GeoVaultCaptureLog.w(TAG, "Transient retry budget exhausted after ${streakAgeMs}ms; escalating to permanent")
            host.failPermanently(trackerIdsSnapshot, failureReason)
            return
        }
        host.onLifecycle(StreamEvent.RecoverableFailure, trackerIdsSnapshot, failureReason)
        synchronized(stateLock) {
            connectJob?.cancel()
            connectJob = scope.launch {
                val delayMs = StreamingConfig.nextReconnectDelayMs(
                    reconnectAttempt = host.reconnectAttempt(),
                    failureClass = failureClass,
                    jitterFraction = StreamingConfig.retryJitterFraction,
                )
                delay(delayMs)
                val retryTrackerIds = host.currentTrackerIds()
                if (sessionId == connectionSessionId.get() && retryTrackerIds.isNotEmpty()) {
                    host.onLifecycle(StreamEvent.RetryRequested, retryTrackerIds)
                    connect(sessionId)
                }
            }
        }
    }

    private fun handleSocketOpened(sessionId: Long, socket: WebSocket, response: Response) {
        if (response.code != WS_UPGRADE_HTTP_CODE) {
            GeoVaultCaptureLog.w(TAG, "Unexpected onOpen response code=${response.code}; closing")
            runCatching { socket.close(1002, "bad_upgrade") }
            handleSocketDisconnected(
                sessionId = sessionId,
                socket = socket,
                failureClass = StreamingFailureClass.PERMANENT,
                failureReason = host.unreachableReason(),
            )
            return
        }
        val acceptedTrackerIds = synchronized(stateLock) {
            val accepted = sessionId == connectionSessionId.get() &&
                webSocket === socket &&
                host.currentTrackerIds().isNotEmpty()
            if (accepted) {
                sessionGuard.markConnected()
                host.currentTrackerIds()
            } else {
                null
            }
        }
        if (acceptedTrackerIds != null) {
            RemoteStreamIngressPolicy.markConnected(System.currentTimeMillis())
            host.onLifecycle(StreamEvent.Connected, acceptedTrackerIds)
        } else {
            socket.close(1000, "stale_session")
        }
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

    private fun webSocketClient(): OkHttpClient {
        synchronized(stateLock) {
            val existingClient = wsHttpClient
            if (existingClient != null) {
                return existingClient
            }
            return GeoVaultHttp.webSocketClient(
                readTimeoutSec = TimeUnit.MILLISECONDS.toSeconds(StreamingConfig.webSocketReadTimeoutMs),
                pingIntervalSec = TimeUnit.MILLISECONDS.toSeconds(StreamingConfig.webSocketPingIntervalMs),
            ).also { builtClient -> wsHttpClient = builtClient }
        }
    }

    private companion object {
        const val TAG = "LiveTrackStreaming"
        const val WS_UPGRADE_HTTP_CODE = 101
    }
}
