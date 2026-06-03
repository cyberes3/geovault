package com.geovault.tracker.positioning.recovery
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import android.os.Bundle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.AutoMotionStabilityPolicy
import com.geovault.tracker.R
import com.geovault.tracker.policy.CanonicalTimeNormalizer
import com.geovault.tracker.policy.TrackPointQuality
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.services.FastLockTriggerInput
import com.geovault.tracker.services.TrackingRuntimeOrchestrator
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class FastGpsLockSubsystem(private val rt: PositioningRuntime) {
    fun maybeStartFastGpsLockWindow(
        measuredAccuracyMeters: Float?,
        rejectReason: TrackPointRejectReason? = null
    ) {
        val nowMs = rt.deps.clock.wallTimeMs()
        if (shouldSuppressFastLockForAutoMotion(rejectReason = rejectReason, nowMs = nowMs)) {
            return
        }
        val accuracyFilterMeters = rt.contextBuilder.currentPositioningRuntimeContext().effectiveAccuracyThresholdMeters
        if (
            !TrackingRuntimeOrchestrator.shouldAttemptFastLock(
                FastLockTriggerInput(
                    isTracking = rt.state.isTracking,
                    isFastGpsLockWindowActive = rt.state.isFastGpsLockWindowActive,
                    isFastGpsLockPriming = rt.state.isFastGpsLockPriming,
                    gpsState = rt.state.gpsRuntimeState,
                    rejectReason = rejectReason,
                    measuredAccuracyMeters = measuredAccuracyMeters,
                    accuracyFilterMeters = accuracyFilterMeters
                )
            )
        ) return
        rt.state.isFastGpsLockPriming = true
        rt.deps.locationSessionCoordinator.getLastLocation(
            onSuccess = { last ->
                rt.state.isFastGpsLockPriming = false
                if (!rt.state.isTracking || rt.state.isFastGpsLockWindowActive) return@getLastLocation
                if (isFreshAccurateLocation(last, accuracyFilterMeters)) {
                    rt.collection.transitionGpsState(GpsRuntimeEvent.FIX_ACCEPTED, "fast_lock_last_known_recovered")
                    rt.deps.lowAccuracyFallbackCoordinator.onAcceptedFix()
                    rt.recovery.fallback.cancelLowAccuracyFallbackTimer(clearCandidate = true)
                    return@getLastLocation
                }
                startFastGpsLockBurst(measuredAccuracyMeters = measuredAccuracyMeters, accuracyFilterMeters = accuracyFilterMeters)
            },
            onFailure = { error ->
                GeoVaultCaptureLog.e(TrackingServiceConstants.TAG, "Fast-lock last location lookup failed", error)
                rt.state.isFastGpsLockPriming = false
                if (!rt.state.isTracking || rt.state.isFastGpsLockWindowActive) return@getLastLocation
                startFastGpsLockBurst(measuredAccuracyMeters = measuredAccuracyMeters, accuracyFilterMeters = accuracyFilterMeters)
            }
        )
    }

    fun shouldSuppressFastLockForAutoMotion(
        rejectReason: TrackPointRejectReason?,
        nowMs: Long,
    ): Boolean {
        val lastMotionEvidenceAtMs = rt.deps.autoTrackingMotionCoordinator.lastEvidenceWallClockMs
        val elapsedSinceEvidenceMs = lastMotionEvidenceAtMs
            .takeIf { it > 0L }
            ?.let { nowMs - it }
        val elapsedSinceModeChangeMs = rt.state.lastAutoModeChangedAtMs
            .takeIf { it > 0L }
            ?.let { nowMs - it }
        if (
            !AutoMotionStabilityPolicy.shouldSuppressFastLock(
                rejectReason = rejectReason,
                nowMs = nowMs,
                lastMotionEvidenceAtMs = lastMotionEvidenceAtMs,
                lastModeChangedAtMs = rt.state.lastAutoModeChangedAtMs,
                windowMs = TrackingServiceConstants.AUTO_MOTION_FAST_LOCK_SUPPRESS_WINDOW_MS,
            )
        ) {
            return false
        }
        rt.deps.runtimeTelemetry.event(
            name = "auto_motion_fast_lock_suppressed",
            details = "reason=$rejectReason elapsedSinceEvidenceMs=${elapsedSinceEvidenceMs ?: -1L} " +
                "elapsedSinceModeChangeMs=${elapsedSinceModeChangeMs ?: -1L}"
        )
        return true
    }

    fun startFastGpsLockBurst(measuredAccuracyMeters: Float?, accuracyFilterMeters: Float) {
        if (!rt.state.isTracking || rt.state.isFastGpsLockWindowActive) return
        rt.state.isFastGpsLockWindowActive = true
        rt.collection.transitionGpsState(GpsRuntimeEvent.FAST_LOCK_STARTED, "fast_gps_lock_start")
        rt.state.fastGpsLockStartCountThisSession++
        rt.recovery.fallback.cancelLowAccuracyFallbackTimer(clearCandidate = false)
        rt.motion.resetElasticDistanceOverride(reason = "fast_gps_lock_start", reapplyRequest = false)
        rt.recovery.fastLock.resetFastGpsLockSamples()
        if (!rt.locationRequests.applyCurrentLocationRequest("fast_gps_lock_start")) {
            rt.state.isFastGpsLockWindowActive = false
            rt.foreground.failActiveTrackingAndStop(rt.ports.service.getString(R.string.unable_to_start_location_updates))
            return
        }
        rt.deps.runtimeTelemetry.event(
            "fast_lock_start",
            "measuredAcc=${measuredAccuracyMeters ?: -1f} accuracyFilter=$accuracyFilterMeters"
        )
        rt.state.fastGpsLockWindowJob?.cancel()
        val runGeneration = rt.state.trackingGeneration
        rt.state.fastGpsLockWindowJob = rt.ingestScope.launch {
            delay(TrackingServiceConstants.FAST_GPS_LOCK_WINDOW_MS)
            if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration || !rt.state.isFastGpsLockWindowActive) return@launch
            val best = selectBestFastGpsLockSample(
                desiredAccuracyMeters = accuracyFilterMeters,
                nowMs = rt.deps.clock.wallTimeMs(),
                nowElapsedRealtimeNanos = rt.deps.clock.elapsedRealtimeNanos()
            )
            rt.state.fastGpsLockTimeoutCountThisSession++
            rt.collection.transitionGpsState(GpsRuntimeEvent.FAST_LOCK_TIMEOUT, "fast_gps_lock_timeout")
            if (best != null) {
                val fallbackLocation = Location(best).apply {
                    time = rt.deps.clock.wallTimeMs()
                    elapsedRealtimeNanos = rt.deps.clock.elapsedRealtimeNanos()
                    val sourceProvider = best.provider?.takeIf { it.isNotBlank() } ?: "fused"
                    provider = "fast_lock_timeout:$sourceProvider"
                    extras = (extras ?: Bundle()).apply {
                        putBoolean(TrackingServiceConstants.EXTRAS_KEY_LOW_ACCURACY_FALLBACK, true)
                        putString(TrackingServiceConstants.EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER, sourceProvider)
                    }
                }
                if (
                    !rt.recovery.fallback.shouldEmitFallbackForTransition(
                        previousAcceptedLocation = rt.state.lastFilteredLocation,
                        fallbackCandidateLocation = fallbackLocation,
                        nowMs = fallbackLocation.time
                    )
                ) {
                    rt.deps.runtimeTelemetry.event("fast_lock_timeout_rejected", "reason=implausible_transition")
                } else if (!rt.recovery.fallback.shouldPersistFallbackPoint(rt.state.lastFilteredLocation, fallbackLocation)) {
                    rt.deps.runtimeTelemetry.event("fast_lock_timeout_skipped_persist", "reason=accuracy_uncertainty")
                } else {
                    rt.deps.lowAccuracyFallbackCoordinator.onFallbackEmitted(
                        candidateLatitude = fallbackLocation.latitude,
                        candidateLongitude = fallbackLocation.longitude,
                        candidateTimestampMs = fallbackLocation.time,
                    )
                    rt.state.lowAccuracyFallbackCandidate = null
                    rt.fixIngest.processLocationUpdateSerialized(
                        fallbackLocation,
                        bypassFilters = true
                    )
                }
            }
            rt.recovery.fastLock.stopFastGpsLockWindow(reason = "timeout")
        }
    }

    fun stopFastGpsLockWindow(reason: String) {
        if (!rt.state.isFastGpsLockWindowActive && rt.state.fastGpsLockWindowJob == null) return
        rt.state.fastGpsLockWindowJob?.cancel()
        rt.state.fastGpsLockWindowJob = null
        if (rt.state.isFastGpsLockWindowActive) {
            rt.state.fastGpsLockStopCountThisSession++
            rt.deps.runtimeTelemetry.event("fast_lock_stop", "reason=$reason samples=${rt.state.fastGpsLockSampleCount}")
        }
        rt.state.isFastGpsLockWindowActive = false
        rt.recovery.fastLock.resetFastGpsLockSamples()
        if (
            rt.state.isTracking &&
            rt.state.gpsRuntimeState != GpsRuntimeState.PAUSED_FOR_MOTION &&
            rt.state.gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER &&
            rt.state.gpsRuntimeState != GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            rt.locationRequests.reapplyLocationRequestIfActive("fast_lock_stop_$reason")
        }
    }

    fun resetFastGpsLockSamples() {
        rt.state.fastGpsLockSampleCount = 0
        rt.state.fastGpsLockPreferredSample = null
        rt.state.fastGpsLockBestAccuracySample = null
        rt.state.fastGpsLockFreshestSample = null
        rt.state.fastGpsLockNewestSample = null
    }

    fun recordFastGpsLockSample(location: Location, nowMs: Long, nowElapsedRealtimeNanos: Long) {
        if (!rt.state.isFastGpsLockWindowActive) return
        rt.state.fastGpsLockSampleCount += 1
        val sample = Location(location)
        rt.state.fastGpsLockNewestSample = sample
        rt.state.fastGpsLockPreferredSample = selectPreferredFastGpsSample(
            currentBest = rt.state.fastGpsLockPreferredSample,
            candidate = sample,
            desiredAccuracyMeters = rt.contextBuilder.currentPositioningRuntimeContext().effectiveAccuracyThresholdMeters,
            nowMs = nowMs,
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos
        )
        if (
            rt.state.fastGpsLockBestAccuracySample == null ||
            isMoreAccurateSample(sample, rt.state.fastGpsLockBestAccuracySample)
        ) {
            rt.state.fastGpsLockBestAccuracySample = sample
        }
        if (
            rt.state.fastGpsLockFreshestSample == null ||
            isFresherSample(sample, rt.state.fastGpsLockFreshestSample, nowMs, nowElapsedRealtimeNanos)
        ) {
            rt.state.fastGpsLockFreshestSample = sample
        }
        val threshold = rt.contextBuilder.currentPositioningRuntimeContext().effectiveAccuracyThresholdMeters
        val earlyExitSampleWindow = rt.state.fastGpsLockSampleCount in TrackingServiceConstants.FAST_GPS_LOCK_EARLY_EXIT_MIN_SAMPLES..TrackingServiceConstants.FAST_GPS_LOCK_MIN_SAMPLES
        if (earlyExitSampleWindow && isFreshAccurateLocation(sample, threshold)) {
            rt.recovery.fastLock.stopFastGpsLockWindow(reason = "early_lock_recovered")
            rt.recovery.fallback.cancelLowAccuracyFallbackTimer(clearCandidate = true)
        }
        maybeLogFastGpsLockSummary(nowMs)
    }

    fun selectBestFastGpsLockSample(
        desiredAccuracyMeters: Float,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long
    ): Location? {
        rt.state.fastGpsLockPreferredSample?.let { preferred ->
            if (isFreshAccurateLocation(preferred, desiredAccuracyMeters)) {
                return Location(preferred)
            }
        }
        rt.state.fastGpsLockBestAccuracySample?.let { bestAccuracy ->
            if (isFreshAccurateLocation(bestAccuracy, desiredAccuracyMeters)) {
                return Location(bestAccuracy)
            }
        }
        rt.state.fastGpsLockFreshestSample?.let { freshest ->
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
        return rt.state.fastGpsLockPreferredSample?.let { Location(it) }
            ?: rt.state.fastGpsLockBestAccuracySample?.let { Location(it) }
            ?: rt.state.fastGpsLockNewestSample?.let { Location(it) }
    }

    fun isFreshAccurateLocation(location: Location?, accuracyFilterMeters: Float): Boolean {
        location ?: return false
        if (!location.hasAccuracy() || location.accuracy > accuracyFilterMeters) return false
        val nowMs = rt.deps.clock.wallTimeMs()
        val normalizedTimestampMs = CanonicalTimeNormalizer.normalizeTimestampMs(location.time, nowMs)
        val ageMs = CanonicalTimeNormalizer.ageMs(
            nowMs = nowMs,
            eventMs = normalizedTimestampMs,
            nowElapsedRealtimeNanos = rt.deps.clock.elapsedRealtimeNanos(),
            eventElapsedRealtimeNanos = location.elapsedRealtimeNanos
        )
        return ageMs in 0..TrackingServiceConstants.FAST_GPS_LOCK_MAX_LAST_LOCATION_AGE_MS
    }

    fun isMoreAccurateSample(candidate: Location, currentBest: Location?): Boolean {
        currentBest ?: return true
        if (!candidate.hasAccuracy()) return false
        if (!currentBest.hasAccuracy()) return true
        return candidate.accuracy < currentBest.accuracy
    }

    fun isFresherSample(
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

    fun maybeLogFastGpsLockSummary(nowMs: Long) {
        if (!rt.state.isFastGpsLockWindowActive) return
        if (nowMs - rt.state.fastGpsLockLastSummaryAtMs < TrackingServiceConstants.FAST_GPS_LOCK_SUMMARY_INTERVAL_MS) return
        rt.state.fastGpsLockLastSummaryAtMs = nowMs
        rt.deps.runtimeTelemetry.event(
            "fast_lock_summary",
            "samples=${rt.state.fastGpsLockSampleCount} " +
                "starts=${rt.state.fastGpsLockStartCountThisSession} " +
                "stops=${rt.state.fastGpsLockStopCountThisSession} " +
                "timeouts=${rt.state.fastGpsLockTimeoutCountThisSession}"
        )
    }

    fun selectMoreAccurateLocation(currentBest: Location?, candidate: Location): Location {
        if (currentBest == null) return Location(candidate)
        val candidateAcc = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAcc = if (currentBest.hasAccuracy()) currentBest.accuracy else Float.MAX_VALUE
        return if (candidateAcc < currentAcc) Location(candidate) else currentBest
    }

    fun selectNewerTimestampLocation(currentNewest: Location?, candidate: Location): Location {
        if (currentNewest == null) return Location(candidate)
        return if (candidate.time > currentNewest.time) Location(candidate) else currentNewest
    }

    fun hasRecoveredFastGpsLock(
        quality: TrackPointQuality,
        measuredAccuracyMeters: Float?,
        accuracyFilterMeters: Float
    ): Boolean {
        if (quality != TrackPointQuality.HIGH_CONFIDENCE) return false
        val measured = measuredAccuracyMeters ?: return false
        return measured <= accuracyFilterMeters
    }

    fun selectPreferredFastGpsSample(
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

}
