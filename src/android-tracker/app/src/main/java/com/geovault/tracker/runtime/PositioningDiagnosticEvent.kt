package com.geovault.tracker.runtime

import com.geovault.tracker.services.GpsRuntimeState
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.location.SyncFailureClass

data class PositioningDiagnosticSnapshot(
    val gpsState: GpsRuntimeState,
    val motionMode: TrackingMotionMode,
    val providerHealth: String,
    val localAgeMs: Long?,
    val uploadAgeMs: Long?,
    val recoveryProbe: String,
    val stationaryRegion: String,
    val queueCount: Int,
    val uploadFailureClass: SyncFailureClass,
) {
    fun toTelemetryDetails(): String {
        return "gpsState=$gpsState motionMode=$motionMode providerHealth=$providerHealth " +
            "localAgeMs=${localAgeMs ?: -1L} uploadAgeMs=${uploadAgeMs ?: -1L} " +
            "recoveryProbe=$recoveryProbe stationaryRegion=$stationaryRegion " +
            "queueCount=$queueCount uploadFailureClass=$uploadFailureClass"
    }
}

object PositioningDiagnosticEvent {
    fun providerHealth(decision: ProviderHealthDecision): Pair<String, String> {
        return "provider_health" to "reason=${decision.telemetryValue}"
    }

    fun fallbackWait(reason: String): Pair<String, String> {
        return "fallback_wait" to "reason=$reason"
    }

    fun pointEmissionTrouble(
        active: Boolean,
        reason: String,
        accuracyBlocked: Boolean,
        localAgeMs: Long?,
        uploadAgeMs: Long?,
        gpsState: GpsRuntimeState,
    ): Pair<String, String> {
        val name = if (active) "point_emission_trouble_started" else "point_emission_trouble_ended"
        return name to "reason=$reason accuracyBlocked=$accuracyBlocked " +
            "localAgeMs=${localAgeMs ?: -1L} uploadAgeMs=${uploadAgeMs ?: -1L} gpsState=$gpsState"
    }

    fun snapshot(snapshot: PositioningDiagnosticSnapshot): Pair<String, String> {
        return "positioning_diagnostic_snapshot" to snapshot.toTelemetryDetails()
    }

    fun queueUploadResult(result: QueueUploadResult, scope: QueueUploadScope): Pair<String, String> {
        return "queue_upload_result" to "scope=$scope failureClass=${result.failureClass} " +
            "batchesAttempted=${result.batchesAttempted} batchesSent=${result.batchesSent} " +
            "rowsDeleted=${result.rowsDeleted} visibleRowsSent=${result.visibleRowsSent} " +
            "interrupted=${result.interruptedByFailure} " +
            "skippedReason=${result.skippedReason?.telemetryValue ?: "none"} " +
            "failureReason=${result.failureReason?.telemetryValue ?: "none"} " +
            "httpStatus=${result.httpStatusCode ?: -1} exception=${result.exceptionClass ?: "none"}"
    }
}
