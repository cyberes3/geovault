package com.geovault.tracker.policy

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.services.TrackingRuntimeStateStore
import java.util.concurrent.atomic.AtomicLong

data class RemoteTrackPointIngressDiagnostics(
    val droppedInvalidEvents: Long = 0L,
    val droppedLocalEchoEvents: Long = 0L,
    val droppedRemotePolicyEvents: Long = 0L,
)

object RemoteTrackPointIngress {
    private const val TAG = "RemoteTrackPointIngress"
    private const val WARNING_INTERVAL_MS = 30_000L

    private val droppedInvalidEvents = AtomicLong(0L)
    private val droppedLocalEchoEvents = AtomicLong(0L)
    private val droppedRemotePolicyEvents = AtomicLong(0L)
    private val lastDropWarningAtMs = AtomicLong(0L)

    fun process(event: TrackPointEvent, nowMs: Long = System.currentTimeMillis()): TrackPointEvent? {
        GeoVaultCaptureLog.d(
            TAG,
            "map_update remote_ingress_received track=${event.trackId.trim()} source=${event.source} " +
                "ts=${event.timestampMs} lat=${event.lat} lon=${event.lon} quality=${event.quality}"
        )
        val sanitizedEvent = sanitize(event) ?: run {
            val dropped = droppedInvalidEvents.incrementAndGet()
            warnRateLimited("map_update remote_ingress_drop reason=invalid dropped=$dropped")
            return null
        }
        if (isLocallyRecordedTrack(sanitizedEvent.trackId)) {
            val dropped = droppedLocalEchoEvents.incrementAndGet()
            warnRateLimited(
                "map_update remote_ingress_drop reason=local_echo track=${sanitizedEvent.trackId.trim()} dropped=$dropped"
            )
            return null
        }
        val accepted = RemoteStreamIngressPolicy.process(
            event = sanitizedEvent,
            nowMs = nowMs
        ) ?: run {
            val dropped = droppedRemotePolicyEvents.incrementAndGet()
            warnRateLimited(
                "map_update remote_ingress_drop reason=policy track=${sanitizedEvent.trackId.trim()} dropped=$dropped"
            )
            null
        }
        if (accepted != null) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update remote_ingress_accept track=${accepted.trackId.trim()} " +
                    "ts=${accepted.timestampMs} lat=${accepted.lat} lon=${accepted.lon}"
            )
        }
        return accepted
    }

    fun diagnostics(): RemoteTrackPointIngressDiagnostics {
        return RemoteTrackPointIngressDiagnostics(
            droppedInvalidEvents = droppedInvalidEvents.get(),
            droppedLocalEchoEvents = droppedLocalEchoEvents.get(),
            droppedRemotePolicyEvents = droppedRemotePolicyEvents.get(),
        )
    }

    fun resetForTests() {
        droppedInvalidEvents.set(0L)
        droppedLocalEchoEvents.set(0L)
        droppedRemotePolicyEvents.set(0L)
        lastDropWarningAtMs.set(0L)
        RemoteStreamIngressPolicy.resetForTests()
    }

    private fun sanitize(event: TrackPointEvent): TrackPointEvent? {
        if (event.source != TrackPointSource.REMOTE_STREAM) return null
        if (!event.lat.isFinite() || !event.lon.isFinite()) return null
        if (event.lat !in -90.0..90.0 || event.lon !in -180.0..180.0) return null
        val timestampMs = WireTimestampNormalizer.normalizeToMilliseconds(event.timestampMs) ?: return null
        return event.copy(timestampMs = timestampMs)
    }

    private fun isLocallyRecordedTrack(trackId: String): Boolean {
        val normalizedTrackId = trackId.trim()
        if (normalizedTrackId.isEmpty()) return false
        val runtime = TrackingRuntimeStateStore.state.value
        return runtime.locallyRecordedTrackerId == normalizedTrackId
    }

    private fun warnRateLimited(message: String) {
        val nowMs = System.currentTimeMillis()
        val previous = lastDropWarningAtMs.get()
        if (nowMs - previous < WARNING_INTERVAL_MS) return
        if (lastDropWarningAtMs.compareAndSet(previous, nowMs)) {
            runCatching { GeoVaultCaptureLog.w(TAG, message) }
        }
    }
}
