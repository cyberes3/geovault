package com.geovault.tracker.policy

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.runtime.TrackerRuntimeStore

enum class AdmissionProfile {
    LocalRecording,
    RemoteStream,
}

sealed class AdmissionOutcome {
    data class Admitted(val event: TrackPoint) : AdmissionOutcome()
    data class Rejected(val reason: String) : AdmissionOutcome()
}

class AdmissionPipeline {
    fun admit(
        profile: AdmissionProfile,
        event: TrackPoint,
        subscriptionScope: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
    ): AdmissionOutcome {
        return when (profile) {
            AdmissionProfile.RemoteStream -> admitRemote(event, subscriptionScope, nowMs)
            AdmissionProfile.LocalRecording -> admitLocal(event)
        }
    }

    private fun admitRemote(
        event: TrackPoint,
        subscriptionScope: Set<String>,
        nowMs: Long,
    ): AdmissionOutcome {
        val trackId = event.trackerId.trim()
        GeoVaultCaptureLog.d(
            TAG,
            "map_update remote_admission_received track=$trackId source=${event.provenance} " +
                "ts=${event.timeMs} lat=${event.latitude} lon=${event.longitude} quality=${event.quality}"
        )
        if (event.provenance != TrackPointSource.REMOTE_STREAM || trackId.isEmpty()) {
            RemoteTrackPointAdmissionDiagnostics.recordRejected(
                RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "invalid_payload", trackId
            )
            return AdmissionOutcome.Rejected("invalid_payload")
        }
        if (trackId !in subscriptionScope) {
            RemoteTrackPointAdmissionDiagnostics.recordRejected(
                RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "not_subscribed", trackId
            )
            return AdmissionOutcome.Rejected("not_subscribed")
        }
        val sanitizedEvent = sanitize(event) ?: run {
            RemoteTrackPointAdmissionDiagnostics.recordRejected(
                RemoteTrackPointAdmissionStage.SUBSCRIPTION_SCOPE, "invalid_payload", trackId
            )
            return AdmissionOutcome.Rejected("invalid_payload")
        }
        if (isLocallyRecordedTrack(trackId)) {
            RemoteTrackPointAdmissionDiagnostics.recordRejected(
                RemoteTrackPointAdmissionStage.LOCAL_ECHO, "local_echo", trackId
            )
            return AdmissionOutcome.Rejected("local_echo")
        }
        val accepted = RemoteStreamIngressPolicy.process(event = sanitizedEvent, nowMs = nowMs)
            ?: return AdmissionOutcome.Rejected("remote_rejected")
        RemoteTrackPointAdmissionDiagnostics.recordAccepted(
            RemoteTrackPointAdmissionStage.FRESHNESS_ORDERING,
            trackId,
        )
        GeoVaultCaptureLog.d(
            TAG,
            "map_update remote_admission_accept track=${accepted.trackerId.trim()} " +
                "ts=${accepted.timeMs} lat=${accepted.latitude} lon=${accepted.longitude}"
        )
        return AdmissionOutcome.Admitted(accepted)
    }

    private fun admitLocal(event: TrackPoint): AdmissionOutcome {
        val recordedId = TrackerRuntimeStore.value.locallyRecordedTrackerId
        if (recordedId.isNotBlank() && event.trackerId.trim() == recordedId) {
            return AdmissionOutcome.Admitted(event)
        }
        return AdmissionOutcome.Rejected("not_local_recording")
    }

    companion object {
        private const val TAG = "AdmissionPipeline"

        fun resetForTests() {
            RemoteTrackPointAdmissionDiagnostics.resetForTests()
            RemoteStreamIngressPolicy.resetForTests()
        }

        private fun sanitize(event: TrackPoint): TrackPoint? {
            if (!event.latitude.isFinite() || !event.longitude.isFinite()) return null
            if (event.latitude !in -90.0..90.0 || event.longitude !in -180.0..180.0) return null
            val timestampMs = WireTimestampNormalizer.normalizeToMilliseconds(event.timeMs)
                ?: return null
            return event.copy(timeMs = timestampMs)
        }

        private fun isLocallyRecordedTrack(trackId: String): Boolean {
            if (trackId.isEmpty()) return false
            return TrackerRuntimeStore.value.recording.locallyRecordedTrackerId == trackId
        }
    }
}
