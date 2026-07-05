package com.geovault.tracker.policy

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.services.TrackingRuntimeStateStore

/**
 * Single ordered entry point for admitting a raw `REMOTE_STREAM` point onto the map.
 *
 * Point acceptance used to be decided independently in five places: the WebSocket-level track-id
 * filter (`TrackersWebSocketListener.onMessage`'s `.filter { it.trackId in liveFilter }`), the
 * old `RemoteTrackPointIngress` (invalid/local-echo gates), `RemoteStreamIngressPolicy`
 * (freshness/ordering), `TrackPointPolicyEngine` (positioning filter), and
 * [com.geovault.tracker.presentation.TrackerMapPointRouter] (visibility routing, called
 * downstream by the map ViewModel once a point reaches [TrackPointBus]). This class owns the
 * first three of those five stages —
 * [RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE], [RemoteTrackPointAdmissionStage.LOCAL_ECHO],
 * [RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING] — as one ordered pipeline the service calls
 * per point; [RemoteTrackPointAdmissionStage.VISIBILITY_ROUTING] and
 * [RemoteTrackPointAdmissionStage.PUBLISH] happen downstream in
 * `TrackerMapPointEventReducer.reduce()`, but every stage reports into the same
 * [RemoteTrackPointAdmissionDiagnostics] sink so a "not updating" report is diagnosable end to
 * end from capture logs.
 */
object RemoteTrackPointAdmissionPipeline {
    private const val TAG = "RemoteTrackPointAdmission"

    /**
     * @param subscriptionScope the socket's current filter set (i.e. what the service is
     *   presently subscribed to). Previously enforced silently, pre-parse, by the WebSocket
     *   listener with zero diagnostics — a track dropping out of scope produced no capture-log
     *   trail at all. Passed in explicitly (rather than read from a shared mutable field) so this
     *   stage stays a pure, directly testable function of its inputs.
     */
    fun process(
        event: TrackPointEvent,
        subscriptionScope: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackPointEvent? {
        val trackId = event.trackId.trim()
        GeoVaultCaptureLog.d(
            TAG,
            "map_update remote_admission_received track=$trackId source=${event.source} " +
                "ts=${event.timestampMs} lat=${event.lat} lon=${event.lon} quality=${event.quality}"
        )
        if (event.source != TrackPointSource.REMOTE_STREAM || trackId.isEmpty()) {
            RemoteTrackPointAdmissionDiagnostics.recordRejected(
                RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "invalid_payload", trackId
            )
            return null
        }
        if (trackId !in subscriptionScope) {
            RemoteTrackPointAdmissionDiagnostics.recordRejected(
                RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "not_subscribed", trackId
            )
            return null
        }
        val sanitizedEvent = sanitize(event) ?: run {
            RemoteTrackPointAdmissionDiagnostics.recordRejected(
                RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "invalid_payload", trackId
            )
            return null
        }
        if (isLocallyRecordedTrack(trackId)) {
            RemoteTrackPointAdmissionDiagnostics.recordRejected(
                RemoteTrackPointAdmissionStage.LOCAL_ECHO, "local_echo", trackId
            )
            return null
        }
        val accepted = RemoteStreamIngressPolicy.process(event = sanitizedEvent, nowMs = nowMs)
        if (accepted == null) return null
        RemoteTrackPointAdmissionDiagnostics.recordAccepted(RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING, trackId)
        GeoVaultCaptureLog.d(
            TAG,
            "map_update remote_admission_accept track=${accepted.trackId.trim()} " +
                "ts=${accepted.timestampMs} lat=${accepted.lat} lon=${accepted.lon}"
        )
        return accepted
    }

    fun resetForTests() {
        RemoteTrackPointAdmissionDiagnostics.resetForTests()
        RemoteStreamIngressPolicy.resetForTests()
    }

    private fun sanitize(event: TrackPointEvent): TrackPointEvent? {
        if (!event.lat.isFinite() || !event.lon.isFinite()) return null
        if (event.lat !in -90.0..90.0 || event.lon !in -180.0..180.0) return null
        val timestampMs = WireTimestampNormalizer.normalizeToMilliseconds(event.timestampMs) ?: return null
        return event.copy(timestampMs = timestampMs)
    }

    private fun isLocallyRecordedTrack(trackId: String): Boolean {
        if (trackId.isEmpty()) return false
        val runtime = TrackingRuntimeStateStore.state.value
        return runtime.locallyRecordedTrackerId == trackId
    }
}
