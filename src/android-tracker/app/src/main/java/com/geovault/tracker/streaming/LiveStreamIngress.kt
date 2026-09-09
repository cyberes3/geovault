package com.geovault.tracker.streaming

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.StreamingTrackPointParser
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.location.StreamingFailureClass
import com.geovault.tracker.policy.AdmissionOutcome
import com.geovault.tracker.policy.AdmissionPipeline
import com.geovault.tracker.policy.AdmissionProfile
import com.geovault.tracker.policy.TrackPointBus
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Parse → admit → [TrackPointBus]. The only remote [TrackPoint] publisher.
 */
internal class LiveStreamIngress(
    private val admission: AdmissionPipeline,
    private val currentTrackerIds: () -> Set<String>,
    private val isCurrentSocket: (sessionId: Long, socket: WebSocket) -> Boolean,
    private val currentSessionId: () -> Long,
) {
    fun publishRemotePoint(sessionId: Long, socket: WebSocket, point: TrackPoint) {
        if (!isCurrentSocket(sessionId, socket)) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update stream_point_drop reason=stale_socket track=${point.trackerId.trim()} " +
                    "session=$sessionId currentSession=${currentSessionId()} ts=${point.timeMs}"
            )
            return
        }
        GeoVaultCaptureLog.d(
            TAG,
            "map_update stream_point_received track=${point.trackerId.trim()} session=$sessionId " +
                "ts=${point.timeMs} lat=${point.latitude} lon=${point.longitude} acc=${point.accuracyMeters}"
        )
        val admitted = admission.admit(
            profile = AdmissionProfile.RemoteStream,
            event = point,
            subscriptionScope = currentTrackerIds(),
        )
        val acceptedEvent = (admitted as? AdmissionOutcome.Admitted)?.event ?: run {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update stream_point_rejected track=${point.trackerId.trim()} session=$sessionId ts=${point.timeMs}"
            )
            return
        }
        GeoVaultCaptureLog.d(
            TAG,
            "map_update stream_point_publish track=${acceptedEvent.trackerId.trim()} session=$sessionId " +
                "ts=${acceptedEvent.timeMs}"
        )
        TrackPointBus.publish(acceptedEvent)
    }

    fun listener(
        onOpened: (WebSocket, Response) -> Unit,
        onPong: (WebSocket) -> Unit,
        onDisconnect: (WebSocket, StreamingFailureClass, String?) -> Unit,
        sessionId: Long,
    ): WebSocketListener {
        return TrackersWebSocketListener(
            onOpened = onOpened,
            onPoint = { socket, point -> publishRemotePoint(sessionId, socket, point) },
            onPong = onPong,
            onDisconnect = onDisconnect,
        )
    }

    private class TrackersWebSocketListener(
        private val onOpened: (WebSocket, Response) -> Unit,
        private val onPoint: (WebSocket, TrackPoint) -> Unit,
        private val onPong: (WebSocket) -> Unit,
        private val onDisconnect: (WebSocket, StreamingFailureClass, String?) -> Unit,
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

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDisconnect(webSocket, classifyCloseCode(code), reason.takeIf { it.isNotBlank() })
        }

        private fun classifyCloseCode(code: Int): StreamingFailureClass {
            return when (code) {
                1008 -> StreamingFailureClass.AUTH
                1003, 1009, 1010, 1011, 1012, 1013, 1014 -> StreamingFailureClass.TRANSIENT
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

    private companion object {
        const val TAG = "LiveTrackStreaming"
    }
}
