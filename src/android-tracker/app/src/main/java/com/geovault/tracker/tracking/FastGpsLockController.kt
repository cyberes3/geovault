package com.geovault.tracker.tracking


import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.os.VibrationEffect
import android.os.VibratorManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.geovault.common.logging.GeoVaultCaptureLog
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.RetrofitClient
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.R
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.TrackingRecoveryCoordinator
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.location.AutoTrackingMotionEngine
import com.geovault.tracker.location.AutoTrackingMotionState
import com.geovault.tracker.location.AutoTrackingEngineOutput
import com.geovault.tracker.location.AutoMotionRejectHandling
import com.geovault.tracker.location.AutoTrackingMotionCoordinator
import com.geovault.tracker.location.AutoTrackingMotionEvidenceGate
import com.geovault.tracker.location.LowAccuracyFallbackCoordinator
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.location.LowAccuracyFallbackLoopDecision
import com.geovault.tracker.location.NetworkStatusMonitor
import com.geovault.tracker.location.PausedFreshnessDecision
import com.geovault.tracker.location.PausedFreshnessDecisionReason
import com.geovault.tracker.location.PausedFreshnessPointFactory
import com.geovault.tracker.location.PausedFreshnessPolicy
import com.geovault.tracker.location.FreshnessRecoveryController
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.TrackerLocationMotionContext
import com.geovault.tracker.location.TrackerLocationPipeline
import com.geovault.tracker.location.FixIngestMode
import com.geovault.tracker.location.TrackerLocationPipelineInput
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RepeatedOutlierSuppressor
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.location.StationaryRegionStore
import com.geovault.tracker.location.StationaryFreshnessActions
import com.geovault.tracker.location.StationaryFreshnessCoordinator
import com.geovault.tracker.location.StationaryPingActions
import com.geovault.tracker.location.StationaryPingController
import com.geovault.tracker.location.StationaryPauseEligibilityPolicy
import com.geovault.tracker.location.SyncFailureClass
import com.geovault.tracker.location.TrackingControlEvent
import com.geovault.tracker.location.TrackingControlPlane
import com.geovault.tracker.location.TrackingControlState
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.location.TrackingLocationRequestInput
import com.geovault.tracker.location.TrackingLocationRequestPolicy
import com.geovault.tracker.location.TrackingPermissionGate
import com.geovault.tracker.location.TrackingSyncPolicy
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.policy.TrackPointEmissionDecision
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointPolicyEngine
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.runtime.RuntimeTelemetry
import com.geovault.tracker.runtime.RuntimeServiceEventType
import com.geovault.tracker.runtime.RuntimeTrigger
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.runtime.PositioningDiagnosticSnapshot
import com.geovault.tracker.runtime.TrackingServiceLifecycleGate
import com.geovault.tracker.runtime.TrackingRuntimeController
import com.geovault.tracker.sensor.SensorManagerSignificantMotionTrigger
import com.geovault.tracker.sensor.SignificantMotionResumeBridge
import com.geovault.tracker.services.LocationIngestCoordinator
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.LocationSessionCoordinator
import com.geovault.tracker.services.GpsRuntimeEvent
import com.geovault.tracker.services.GpsRuntimeState
import com.geovault.tracker.services.GpsRuntimeStateMachine
import com.geovault.tracker.services.QueueUploadConfig
import com.geovault.tracker.services.QueueUploadEngine
import com.geovault.tracker.services.QueueUploadOutcomePolicy
import com.geovault.tracker.services.QueueUploadResult
import com.geovault.tracker.services.QueueUploadScope
import com.geovault.tracker.services.QueueUploadSkipReason
import com.geovault.tracker.services.PointFreshnessTracker
import com.geovault.tracker.services.ProviderHealthController
import com.geovault.tracker.services.ProviderHealthDecision
import com.geovault.tracker.services.PositioningDensity
import com.geovault.tracker.services.PositioningPresetValues
import com.geovault.tracker.services.PositioningPresets
import com.geovault.tracker.services.RecordingRuntimeReducer
import com.geovault.tracker.services.RuntimeAccuracyHoldPolicy
import com.geovault.tracker.services.RuntimeEventPublisher
import com.geovault.tracker.services.TrackerPositioningRuntimeContext
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.services.TrackingNotificationPresenter
import com.geovault.tracker.services.PositioningPolicyConfig
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.services.RuntimeLocationGateInput
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.TrackingSessionCoordinator
import com.geovault.tracker.services.TrackingStatusAccuracyInput
import com.geovault.tracker.services.TrackingStatusAccuracyProjector
import com.geovault.tracker.services.TrackingRuntimeStateStore
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.RuntimeSnapshotProjector
import com.geovault.tracker.services.RuntimeSnapshotProjectionInput
import com.geovault.tracker.services.UploadLivenessState
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.settings.TrackerSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.random.Random


    internal fun TrackingServiceHost.maybeStartFastGpsLockWindow(
        measuredAccuracyMeters: Float?,
        rejectReason: TrackPointRejectReason? = null
    ) {
        val nowMs = System.currentTimeMillis()
        if (shouldSuppressFastLockForAutoMotion(rejectReason = rejectReason, nowMs = nowMs)) {
            return
        }
        val accuracyFilterMeters = currentPositioningRuntimeContext().effectiveAccuracyThresholdMeters
        if (
            !TrackingRuntimeOrchestrator.shouldAttemptFastLock(
                FastLockTriggerInput(
                    isTracking = isTracking,
                    isFastGpsLockWindowActive = isFastGpsLockWindowActive,
                    isFastGpsLockPriming = isFastGpsLockPriming,
                    gpsState = gpsRuntimeState,
                    rejectReason = rejectReason,
                    measuredAccuracyMeters = measuredAccuracyMeters,
                    accuracyFilterMeters = accuracyFilterMeters
                )
            )
        ) return
        isFastGpsLockPriming = true
        locationSessionCoordinator.getLastLocation(
            onSuccess = { last ->
                isFastGpsLockPriming = false
                if (!isTracking || isFastGpsLockWindowActive) return@getLastLocation
                if (isFreshAccurateLocation(last, accuracyFilterMeters)) {
                    transitionGpsState(GpsRuntimeEvent.FIX_ACCEPTED, "fast_lock_last_known_recovered")
                    lowAccuracyFallbackCoordinator.onAcceptedFix()
                    cancelLowAccuracyFallbackTimer(clearCandidate = true)
                    return@getLastLocation
                }
                startFastGpsLockBurst(measuredAccuracyMeters = measuredAccuracyMeters, accuracyFilterMeters = accuracyFilterMeters)
            },
            onFailure = { error ->
                GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Fast-lock last location lookup failed", error)
                isFastGpsLockPriming = false
                if (!isTracking || isFastGpsLockWindowActive) return@getLastLocation
                startFastGpsLockBurst(measuredAccuracyMeters = measuredAccuracyMeters, accuracyFilterMeters = accuracyFilterMeters)
            }
        )
    }

    internal fun TrackingServiceHost.shouldSuppressFastLockForAutoMotion(
        rejectReason: TrackPointRejectReason?,
        nowMs: Long,
    ): Boolean {
        val lastMotionEvidenceAtMs = autoTrackingMotionCoordinator.lastEvidenceWallClockMs
        val elapsedSinceEvidenceMs = lastMotionEvidenceAtMs
            .takeIf { it > 0L }
            ?.let { nowMs - it }
        val elapsedSinceModeChangeMs = lastAutoModeChangedAtMs
            .takeIf { it > 0L }
            ?.let { nowMs - it }
        if (
            !AutoMotionStabilityPolicy.shouldSuppressFastLock(
                rejectReason = rejectReason,
                nowMs = nowMs,
                lastMotionEvidenceAtMs = lastMotionEvidenceAtMs,
                lastModeChangedAtMs = lastAutoModeChangedAtMs,
                windowMs = TrackingServiceConstants.AUTO_MOTION_FAST_LOCK_SUPPRESS_WINDOW_MS,
            )
        ) {
            return false
        }
        runtimeTelemetry.event(
            name = "auto_motion_fast_lock_suppressed",
            details = "reason=$rejectReason elapsedSinceEvidenceMs=${elapsedSinceEvidenceMs ?: -1L} " +
                "elapsedSinceModeChangeMs=${elapsedSinceModeChangeMs ?: -1L}"
        )
        return true
    }

    internal fun TrackingServiceHost.startFastGpsLockBurst(measuredAccuracyMeters: Float?, accuracyFilterMeters: Float) {
        if (!isTracking || isFastGpsLockWindowActive) return
        isFastGpsLockWindowActive = true
        transitionGpsState(GpsRuntimeEvent.FAST_LOCK_STARTED, "fast_gps_lock_start")
        fastGpsLockStartCountThisSession++
        cancelLowAccuracyFallbackTimer(clearCandidate = false)
        resetElasticDistanceOverride(reason = "fast_gps_lock_start", reapplyRequest = false)
        resetFastGpsLockSamples()
        if (!applyCurrentLocationRequest("fast_gps_lock_start")) {
            isFastGpsLockWindowActive = false
            failActiveTrackingAndStop(service.getString(R.string.unable_to_start_location_updates))
            return
        }
        runtimeTelemetry.event(
            "fast_lock_start",
            "measuredAcc=${measuredAccuracyMeters ?: -1f} accuracyFilter=$accuracyFilterMeters"
        )
        fastGpsLockWindowJob?.cancel()
        val runGeneration = trackingGeneration
        fastGpsLockWindowJob = ingestScope.launch {
            delay(TrackingServiceConstants.FAST_GPS_LOCK_WINDOW_MS)
            if (!isTracking || runGeneration != trackingGeneration || !isFastGpsLockWindowActive) return@launch
            val best = selectBestFastGpsLockSample(
                desiredAccuracyMeters = accuracyFilterMeters,
                nowMs = System.currentTimeMillis(),
                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            )
            fastGpsLockTimeoutCountThisSession++
            transitionGpsState(GpsRuntimeEvent.FAST_LOCK_TIMEOUT, "fast_gps_lock_timeout")
            if (best != null) {
                val fallbackLocation = Location(best).apply {
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    val sourceProvider = best.provider?.takeIf { it.isNotBlank() } ?: "fused"
                    provider = "fast_lock_timeout:$sourceProvider"
                    extras = (extras ?: Bundle()).apply {
                        putBoolean(TrackingServiceConstants.EXTRAS_KEY_LOW_ACCURACY_FALLBACK, true)
                        putString(TrackingServiceConstants.EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER, sourceProvider)
                    }
                }
                if (
                    !shouldEmitFallbackForTransition(
                        previousAcceptedLocation = lastFilteredLocation,
                        fallbackCandidateLocation = fallbackLocation,
                        nowMs = fallbackLocation.time
                    )
                ) {
                    runtimeTelemetry.event("fast_lock_timeout_rejected", "reason=implausible_transition")
                } else if (!shouldPersistFallbackPoint(lastFilteredLocation, fallbackLocation)) {
                    runtimeTelemetry.event("fast_lock_timeout_skipped_persist", "reason=accuracy_uncertainty")
                } else {
                    lowAccuracyFallbackCoordinator.onFallbackEmitted(
                        candidateLatitude = fallbackLocation.latitude,
                        candidateLongitude = fallbackLocation.longitude,
                        candidateTimestampMs = fallbackLocation.time,
                    )
                    lowAccuracyFallbackCandidate = null
                    processLocationUpdateSerialized(
                        fallbackLocation,
                        bypassFilters = true
                    )
                }
            }
            stopFastGpsLockWindow(reason = "timeout")
        }
    }

    internal fun TrackingServiceHost.stopFastGpsLockWindow(reason: String) {
        if (!isFastGpsLockWindowActive && fastGpsLockWindowJob == null) return
        fastGpsLockWindowJob?.cancel()
        fastGpsLockWindowJob = null
        if (isFastGpsLockWindowActive) {
            fastGpsLockStopCountThisSession++
            runtimeTelemetry.event("fast_lock_stop", "reason=$reason samples=$fastGpsLockSampleCount")
        }
        isFastGpsLockWindowActive = false
        resetFastGpsLockSamples()
        if (
            isTracking &&
            gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
            gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER &&
            gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            reapplyLocationRequestIfActive("fast_lock_stop_$reason")
        }
    }

    internal fun TrackingServiceHost.resetFastGpsLockSamples() {
        fastGpsLockSampleCount = 0
        fastGpsLockPreferredSample = null
        fastGpsLockBestAccuracySample = null
        fastGpsLockFreshestSample = null
        fastGpsLockNewestSample = null
    }

    internal fun TrackingServiceHost.recordFastGpsLockSample(location: Location, nowMs: Long, nowElapsedRealtimeNanos: Long) {
        if (!isFastGpsLockWindowActive) return
        fastGpsLockSampleCount += 1
        val sample = Location(location)
        fastGpsLockNewestSample = sample
        fastGpsLockPreferredSample = selectPreferredFastGpsSample(
            currentBest = fastGpsLockPreferredSample,
            candidate = sample,
            desiredAccuracyMeters = currentPositioningRuntimeContext().effectiveAccuracyThresholdMeters,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
        if (
            fastGpsLockBestAccuracySample == null ||
            isMoreAccurateSample(sample, fastGpsLockBestAccuracySample)
        ) {
            fastGpsLockBestAccuracySample = sample
        }
        if (
            fastGpsLockFreshestSample == null ||
            isFresherSample(sample, fastGpsLockFreshestSample, nowMs, nowElapsedRealtimeNanos)
        ) {
            fastGpsLockFreshestSample = sample
        }
        val threshold = currentPositioningRuntimeContext().effectiveAccuracyThresholdMeters
        val earlyExitSampleWindow = fastGpsLockSampleCount in TrackingServiceConstants.FAST_GPS_LOCK_EARLY_EXIT_MIN_SAMPLES..TrackingServiceConstants.FAST_GPS_LOCK_MIN_SAMPLES
        if (earlyExitSampleWindow && isFreshAccurateLocation(sample, threshold)) {
            stopFastGpsLockWindow(reason = "early_lock_recovered")
            cancelLowAccuracyFallbackTimer(clearCandidate = true)
        }
        maybeLogFastGpsLockSummary(nowMs)
    }

    internal fun TrackingServiceHost.selectBestFastGpsLockSample(
        desiredAccuracyMeters: Float,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long
    ): Location? {
        fastGpsLockPreferredSample?.let { preferred ->
            if (isFreshAccurateLocation(preferred, desiredAccuracyMeters)) {
                return Location(preferred)
            }
        }
        fastGpsLockBestAccuracySample?.let { bestAccuracy ->
            if (isFreshAccurateLocation(bestAccuracy, desiredAccuracyMeters)) {
                return Location(bestAccuracy)
            }
        }
        fastGpsLockFreshestSample?.let { freshest ->
            val normalizedTs = CanonicalTimeNormalizer.normalizeTimestampMs(freshest.time, nowMs)
            val ageMs = CanonicalTimeNormalizer.ageMs(
                nowMs = nowMs,
                eventMs = normalizedTs,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                eventElapsedRealtimeNanos = freshest.elapsedRealtimeNanos
            )
            if (ageMs in 0..TrackingServiceConstants.FAST_GPS_LOCK_MAX_SAMPLE_AGE_MS) {
                return Location(freshest)
            }
        }
        return fastGpsLockPreferredSample?.let { Location(it) }
            ?: fastGpsLockBestAccuracySample?.let { Location(it) }
            ?: fastGpsLockNewestSample?.let { Location(it) }
    }

    internal fun TrackingServiceHost.isFreshAccurateLocation(location: Location?, accuracyFilterMeters: Float): Boolean {
        location ?: return false
        if (!location.hasAccuracy() || location.accuracy > accuracyFilterMeters) return false
        val nowMs = System.currentTimeMillis()
        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(location.time, nowMs)
        val ageMs = CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = normalizedTimestampMs,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            eventElapsedRealtimeNanos = location.elapsedRealtimeNanos
        )
        return ageMs in 0..TrackingServiceConstants.FAST_GPS_LOCK_MAX_LAST_LOCATION_AGE_MS
    }

    internal fun TrackingServiceHost.isMoreAccurateSample(candidate: Location, currentBest: Location?): Boolean {
        currentBest ?: return true
        if (!candidate.hasAccuracy()) return false
        if (!currentBest.hasAccuracy()) return true
        return candidate.accuracy < currentBest.accuracy
    }

    internal fun TrackingServiceHost.isFresherSample(
        candidate: Location,
        currentBest: Location?,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long
    ): Boolean {
        currentBest ?: return true
        val candidateAgeMs = CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = CanonicalTimeNormalizer.normalizeTimestampMs(candidate.time, nowMs),
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            eventElapsedRealtimeNanos = candidate.elapsedRealtimeNanos
        )
        val currentBestAgeMs = CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = CanonicalTimeNormalizer.normalizeTimestampMs(currentBest.time, nowMs),
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            eventElapsedRealtimeNanos = currentBest.elapsedRealtimeNanos
        )
        return candidateAgeMs < currentBestAgeMs
    }

    internal fun TrackingServiceHost.maybeLogFastGpsLockSummary(nowMs: Long) {
        if (!isFastGpsLockWindowActive) return
        if (nowMs - fastGpsLockLastSummaryAtMs < TrackingServiceConstants.FAST_GPS_LOCK_SUMMARY_INTERVAL_MS) return
        fastGpsLockLastSummaryAtMs = nowMs
        runtimeTelemetry.event(
            "fast_lock_summary",
            "samples=$fastGpsLockSampleCount starts=$fastGpsLockStartCountThisSession stops=$fastGpsLockStopCountThisSession timeouts=$fastGpsLockTimeoutCountThisSession"
        )
    }

    internal fun TrackingServiceHost.selectMoreAccurateLocation(currentBest: Location?, candidate: Location): Location {
        if (currentBest == null) return Location(candidate)
        val candidateAcc = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAcc = if (currentBest.hasAccuracy()) currentBest.accuracy else Float.MAX_VALUE
        return if (candidateAcc < currentAcc) Location(candidate) else currentBest
    }

    internal fun TrackingServiceHost.selectNewerTimestampLocation(currentNewest: Location?, candidate: Location): Location {
        if (currentNewest == null) return Location(candidate)
        return if (candidate.time > currentNewest.time) Location(candidate) else currentNewest
    }

    internal fun TrackingServiceHost.hasRecoveredFastGpsLock(
        quality: TrackPointQuality,
        measuredAccuracyMeters: Float?,
        accuracyFilterMeters: Float
    ): Boolean {
        if (quality != TrackPointQuality.HIGH_CONFIDENCE) return false
        val measured = measuredAccuracyMeters ?: return false
        return measured <= accuracyFilterMeters
    }

internal fun TrackingServiceHost.selectPreferredFastGpsSample(
    currentBest: Location?,
    candidate: Location?,
    desiredAccuracyMeters: Float,
    nowMs: Long,
    nowElapsedRealtimeNanos: Long,
): Location? {
    if (candidate == null) return currentBest
    if (currentBest == null) return candidate
    fun ageMs(location: Location): Long {
        val normalized = CanonicalTimeNormalizer.normalizeTimestampMs(location.time, nowMs)
        return CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = normalized,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            eventElapsedRealtimeNanos = location.elapsedRealtimeNanos,
        )
    }

    fun isValid(location: Location): Boolean {
        if (!location.hasAccuracy() || location.accuracy < 0f) return false
        return ageMs(location) <= 120_000L
    }

    val candidateValid = isValid(candidate)
    val currentValid = isValid(currentBest)
    if (candidateValid != currentValid) {
        return if (candidateValid) candidate else currentBest
    }
    if (!candidateValid && !currentValid) {
        return if (ageMs(candidate) <= ageMs(currentBest)) candidate else currentBest
    }

    val candidateAcc = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
    val currentAcc = if (currentBest.hasAccuracy()) currentBest.accuracy else Float.MAX_VALUE
    val candidateAge = ageMs(candidate)
    val currentAge = ageMs(currentBest)
    val ageDeltaMs = candidateAge - currentAge
    val accuracyDelta = candidateAcc - currentAcc
    val candidateMeetsDesired = desiredAccuracyMeters > 0f && candidateAcc <= desiredAccuracyMeters
    val currentMeetsDesired = desiredAccuracyMeters > 0f && currentAcc <= desiredAccuracyMeters

    if (kotlin.math.abs(ageDeltaMs) <= 5_000L && candidateMeetsDesired != currentMeetsDesired) {
        return if (candidateMeetsDesired) candidate else currentBest
    }
    if (kotlin.math.abs(accuracyDelta) > 50f && kotlin.math.abs(ageDeltaMs) <= 30_000L) {
        return if (candidateAcc < currentAcc) candidate else currentBest
    }
    if (kotlin.math.abs(ageDeltaMs) > 30_000L && kotlin.math.abs(accuracyDelta) <= 50f) {
        return if (candidateAge < currentAge) candidate else currentBest
    }

    if (desiredAccuracyMeters > 0f && kotlin.math.abs(ageDeltaMs) <= 5_000L) {
        val candidateDistanceToDesired = kotlin.math.abs(candidateAcc - desiredAccuracyMeters)
        val currentDistanceToDesired = kotlin.math.abs(currentAcc - desiredAccuracyMeters)
        if (candidateDistanceToDesired != currentDistanceToDesired) {
            return if (candidateDistanceToDesired < currentDistanceToDesired) candidate else currentBest
        }
    }

    if (candidateAge != currentAge) {
        return if (candidateAge < currentAge) candidate else currentBest
    }
    if (candidateAcc != currentAcc) {
        return if (candidateAcc < currentAcc) candidate else currentBest
    }
    return candidate
}
