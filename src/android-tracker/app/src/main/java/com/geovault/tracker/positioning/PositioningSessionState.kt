package com.geovault.tracker.positioning

import android.location.Location
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingControlState
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.UploadLivenessState
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

internal class PositioningSessionState {
    @Volatile
    var isTracking: Boolean = false

    @Volatile
    var startupInProgress: Boolean = false

    @Volatile
    var startupReadyForEvents: Boolean = false

    var controlState: TrackingControlState = TrackingControlState()
    var startupForegroundPromoted: Boolean = false
    var sessionVisibleBoundaryId: Long = 0L
    var sessionBoundaryForBacklogId: Long = 0L
    var lastFilteredLocation: Location? = null
    var latestObservedRawLocation: Location? = null
    var lowAccuracyFallbackCandidate: Location? = null
    var lowAccuracyFallbackTimerArmedAtMs: Long = 0L
    var lowAccuracyFallbackEmitCountThisSession: Int = 0
    var lowAccuracyFallbackArmCountThisSession: Int = 0
    var lowAccuracyFallbackCancelCountThisSession: Int = 0
    var lowAccuracyFallbackRejectedFixCountThisSession: Int = 0
    var lowAccuracyFallbackLastRejectSummaryAtMs: Long = 0L
    var lastLowAccuracyFallbackWaitReason: String? = null
    var lowAccuracyFallbackJob: Job? = null
    var lastLoggedPointEmissionTrouble: PointEmissionTrouble = PointEmissionTrouble.None
    var lastAccuracyHoldLogKey: String? = null
    var lastLocationFilterLogSignature: String? = null
    var lastPositioningDiagnosticSnapshotKey: String? = null
    var lastAutoModeChangedAtMs: Long = 0L
    var autoModeTickJob: Job? = null
    var locationRequestReapplyRetryJob: Job? = null
    var lastAppliedLocationRequestKey: LocationRequestKey? = null
    var lastLocationRequestAppliedAtMs: Long = 0L
    var lastFixDeliveryAtMs: Long = 0L
    var fixDeliveryWatchdogJob: Job? = null
    var elasticDistanceOverrideMeters: Float? = null
    var elasticitySpeedBucket: Int = 0
    var lastSpeedReferenceLocation: Location? = null
    var isFastGpsLockWindowActive: Boolean = false
    var isFastGpsLockPriming: Boolean = false
    var fastGpsLockWindowJob: Job? = null
    var fastGpsLockSampleCount: Int = 0
    var fastGpsLockPreferredSample: Location? = null
    var fastGpsLockBestAccuracySample: Location? = null
    var fastGpsLockFreshestSample: Location? = null
    var fastGpsLockNewestSample: Location? = null
    var fastGpsLockStartCountThisSession: Int = 0
    var fastGpsLockStopCountThisSession: Int = 0
    var fastGpsLockTimeoutCountThisSession: Int = 0
    var fastGpsLockLastSummaryAtMs: Long = 0L
    var sigMotionSensorStartTime: Long = 0L
    var watchdogJob: Job? = null
    var consecutiveStationaryPoints: Int = 0
    var stationaryAnchorLocation: Location? = null
    var consecutivePushFailures: Int = 0
    var lastSyncFailureClass: SyncFailureClass = SyncFailureClass.NONE

    @Volatile
    var gpsRuntimeState: GpsRuntimeState = GpsRuntimeState.INACTIVE

    var trackingGeneration: Int = 0
    val runtimeSnapshotLock = Any()
    var runtimeSnapshot: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot()
    val startupStateLock = Any()
    var recoveryAnchorState: RecoveryAnchorState? = null
    var uploadLivenessState: UploadLivenessState = UploadLivenessState()
    val localTrackPointOrderingCounter = AtomicLong(0L)

    var recoveryHeartbeatJob: Job? = null
    var retryJob: Job? = null
    var backlogUploaderJob: Job? = null
    var preflightJob: Job? = null
    var sparseTrackingObserverJob: Job? = null
    var gpsProviderReceiverRegistered: Boolean = false

    val collectionPace: RecordingPace
        get() = RecordingPace.from(gpsRuntimeState)

    /**
     * Clears session-scoped mutable state for a new recording session: adaptive filters,
     * upload liveness, recovery anchor, upload failure counters, ordering counter, and
     * in-flight session [Job] handles.
     */
    fun resetForStart() {
        cancelSessionJobs()
        resetAdaptiveStateForStart()
        consecutivePushFailures = 0
        lastSyncFailureClass = SyncFailureClass.NONE
        localTrackPointOrderingCounter.set(0)
        uploadLivenessState = UploadLivenessState()
        recoveryAnchorState = null
    }

    /**
     * Clears session diagnostics, boundaries, upload posture, locations, anchor, and
     * cancels session [Job] handles when recording stops.
     */
    fun resetForStop() {
        cancelSessionJobs()
        resetDiagnosticsForStop()
        consecutivePushFailures = 0
        lastSyncFailureClass = SyncFailureClass.NONE
        sessionVisibleBoundaryId = 0L
        sessionBoundaryForBacklogId = 0L
    }

    fun cancelSessionJobs() {
        lowAccuracyFallbackJob?.cancel()
        lowAccuracyFallbackJob = null
        fastGpsLockWindowJob?.cancel()
        fastGpsLockWindowJob = null
        autoModeTickJob?.cancel()
        autoModeTickJob = null
        locationRequestReapplyRetryJob?.cancel()
        locationRequestReapplyRetryJob = null
        fixDeliveryWatchdogJob?.cancel()
        fixDeliveryWatchdogJob = null
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun resetAdaptiveStateForStart() {
        lastLoggedPointEmissionTrouble = PointEmissionTrouble.None
        lastAccuracyHoldLogKey = null
        lastLocationFilterLogSignature = null
        lastPositioningDiagnosticSnapshotKey = null
        lastAutoModeChangedAtMs = 0L
        lowAccuracyFallbackCandidate = null
        lowAccuracyFallbackTimerArmedAtMs = 0L
        lowAccuracyFallbackEmitCountThisSession = 0
        lowAccuracyFallbackArmCountThisSession = 0
        lowAccuracyFallbackCancelCountThisSession = 0
        lowAccuracyFallbackRejectedFixCountThisSession = 0
        lowAccuracyFallbackLastRejectSummaryAtMs = 0L
        lastLowAccuracyFallbackWaitReason = null
        isFastGpsLockWindowActive = false
        isFastGpsLockPriming = false
        fastGpsLockSampleCount = 0
        fastGpsLockPreferredSample = null
        fastGpsLockBestAccuracySample = null
        fastGpsLockFreshestSample = null
        fastGpsLockNewestSample = null
        fastGpsLockStartCountThisSession = 0
        fastGpsLockStopCountThisSession = 0
        fastGpsLockTimeoutCountThisSession = 0
        fastGpsLockLastSummaryAtMs = 0L
        consecutiveStationaryPoints = 0
        stationaryAnchorLocation = null
        lastFilteredLocation = null
        latestObservedRawLocation = null
        lastFixDeliveryAtMs = 0L
        lastLocationRequestAppliedAtMs = 0L
        lastSpeedReferenceLocation = null
        elasticDistanceOverrideMeters = null
        elasticitySpeedBucket = 0
    }

    private fun resetDiagnosticsForStop() {
        lastLoggedPointEmissionTrouble = PointEmissionTrouble.None
        lastAccuracyHoldLogKey = null
        lastLocationFilterLogSignature = null
        lastPositioningDiagnosticSnapshotKey = null
        lastFixDeliveryAtMs = 0L
        lastLocationRequestAppliedAtMs = 0L
        recoveryAnchorState = null
        uploadLivenessState = UploadLivenessState()
        lowAccuracyFallbackCandidate = null
        lastLowAccuracyFallbackWaitReason = null
        lastFilteredLocation = null
        latestObservedRawLocation = null
    }
}
