package com.geovault.tracker.positioning.recovery
import com.geovault.tracker.positioning.FallbackPersistencePolicy
import com.geovault.tracker.positioning.FallbackTransitionPolicy
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import android.os.Bundle
import com.geovault.tracker.location.LowAccuracyFallbackLoopDecision
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.runtime.PositioningDiagnosticEvent
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class LowAccuracyFallbackSubsystem(private val rt: PositioningRuntime) {
    fun selectLowAccuracyFallbackCandidate(
        rejectedLocation: Location,
        nowMs: Long,
        motionMode: TrackingMotionMode,
    ): Location {
        val anchor = rt.state.lastFilteredLocation
        val useAnchor = anchor != null &&
            rt.deps.pointFreshnessTracker.shouldForceLocalRecovery(
                nowMs = nowMs,
                intervalSec = rt.contextBuilder.resolvePointFreshnessIntervalSec(motionMode),
            )
        rt.deps.runtimeTelemetry.event(
            "fallback_candidate_selected",
            "source=${if (useAnchor) "anchor" else "rejected_fix"} " +
                "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                "rejectedAccuracy=${if (rejectedLocation.hasAccuracy()) rejectedLocation.accuracy else -1f} " +
                "anchorAccuracy=${if (anchor?.hasAccuracy() == true) anchor.accuracy else -1f}"
        )
        if (useAnchor) {
            return Location(anchor).apply {
                time = nowMs
                elapsedRealtimeNanos = rt.deps.clock.elapsedRealtimeNanos()
                provider = "low_accuracy_fallback_anchor:${rejectedLocation.provider ?: "gps"}"
                if (rejectedLocation.hasAccuracy()) accuracy = rejectedLocation.accuracy
            }
        }
        return Location(rejectedLocation)
    }

    fun ensureLowAccuracyFallbackTimerRunning() {
        if (rt.state.lowAccuracyFallbackJob?.isActive == true) return
        val runGeneration = rt.state.trackingGeneration
        rt.state.lowAccuracyFallbackJob = rt.serviceScope.launch(Dispatchers.IO) {
            rt.state.lowAccuracyFallbackTimerArmedAtMs = rt.deps.clock.wallTimeMs()
            while (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                val timeoutSec = TrackerSettings.clampLowAccuracyFallbackTimeoutSec(
                    rt.deps.settingsRepository.getSettings().lowAccuracyFallbackTimeoutSec
                )
                delay(timeoutSec * 1000L)
                if (!rt.state.isTracking || runGeneration != rt.state.trackingGeneration) break
                if (
                    rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
                    rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
                ) {
                    rt.recovery.fallback.logFallbackWait(reason = "gps_paused state=${rt.state.gpsRuntimeState}")
                    continue
                }
                val candidate = rt.state.lowAccuracyFallbackCandidate
                if (candidate == null) {
                    rt.recovery.fallback.logFallbackWait(reason = "no_candidate")
                    continue
                }
                val settings = rt.deps.settingsRepository.getSettings()
                val loopDecision = rt.deps.lowAccuracyFallbackCoordinator.evaluateLoop(
                        fallbackEligible = settings.lowAccuracyFallbackEnabled,
                        hasCandidate = true
                )
                if (loopDecision != LowAccuracyFallbackLoopDecision.COMMIT_ANCHOR) {
                    rt.recovery.fallback.logFallbackWait(reason = loopDecision.telemetryValue)
                    continue
                }
                rt.state.lastLowAccuracyFallbackWaitReason = null
                val fallbackLocation = Location(candidate).apply {
                    provider = "low_accuracy_fallback:${candidate.provider ?: "gps"}"
                    time = rt.deps.clock.wallTimeMs()
                    elapsedRealtimeNanos = rt.deps.clock.elapsedRealtimeNanos()
                    extras = (extras ?: Bundle()).apply {
                        putBoolean(TrackingServiceConstants.EXTRAS_KEY_LOW_ACCURACY_FALLBACK, true)
                        putString(TrackingServiceConstants.EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER, candidate.provider ?: "gps")
                    }
                }
                if (
                    !rt.recovery.fallback.shouldEmitFallbackForTransition(
                        previousAcceptedLocation = rt.state.lastFilteredLocation,
                        fallbackCandidateLocation = fallbackLocation,
                        nowMs = fallbackLocation.time
                    )
                ) {
                    rt.deps.runtimeTelemetry.event("fallback_rejected", "reason=implausible_transition")
                    continue
                }
                rt.deps.lowAccuracyFallbackCoordinator.onFallbackEmitted(
                    candidateLatitude = candidate.latitude,
                    candidateLongitude = candidate.longitude,
                    candidateTimestampMs = candidate.time
                )
                rt.state.lowAccuracyFallbackEmitCountThisSession++
                rt.collection.transitionGpsState(GpsRuntimeEvent.FALLBACK_EMITTED, "fallback_emitted")
                rt.state.lowAccuracyFallbackTimerArmedAtMs = rt.deps.clock.wallTimeMs()
                val shouldPersistFallback = rt.recovery.fallback.shouldPersistFallbackPoint(rt.state.lastFilteredLocation, fallbackLocation) ||
                    rt.deps.pointFreshnessTracker.shouldForceLocalRecovery(
                        nowMs = fallbackLocation.time,
                        intervalSec = rt.contextBuilder.resolvePointFreshnessIntervalSec(rt.contextBuilder.resolveActiveMotionMode()),
                    )
                if (!shouldPersistFallback) {
                    rt.deps.runtimeTelemetry.event("fallback_skipped_persist", "reason=accuracy_uncertainty")
                    continue
                }
                rt.fixIngest.processLocationUpdateSerialized(
                    location = fallbackLocation,
                    bypassFilters = true
                )
            }
            rt.state.lowAccuracyFallbackTimerArmedAtMs = 0L
            rt.state.lowAccuracyFallbackJob = null
            if (rt.state.isTracking && runGeneration == rt.state.trackingGeneration) {
                rt.deps.lowAccuracyFallbackCoordinator.onFallbackTimerStopped()
            }
        }
    }

    fun cancelLowAccuracyFallbackTimer(clearCandidate: Boolean) {
        if (rt.state.lowAccuracyFallbackJob != null) {
            rt.state.lowAccuracyFallbackCancelCountThisSession++
        }
        rt.state.lowAccuracyFallbackJob?.cancel()
        rt.state.lowAccuracyFallbackJob = null
        rt.state.lowAccuracyFallbackTimerArmedAtMs = 0L
        if (clearCandidate) {
            rt.deps.lowAccuracyFallbackCoordinator.onTrackingStopped()
        } else {
            rt.deps.lowAccuracyFallbackCoordinator.onFallbackTimerStopped()
        }
        if (clearCandidate) {
            rt.state.lowAccuracyFallbackCandidate = null
        }
    }

    fun logFallbackWait(reason: String) {
        if (rt.state.lastLowAccuracyFallbackWaitReason == reason) return
        rt.state.lastLowAccuracyFallbackWaitReason = reason
        val (eventName, details) = PositioningDiagnosticEvent.fallbackWait(reason)
        rt.deps.runtimeTelemetry.event(eventName, details)
    }

    fun maybeLogFallbackRejectSummary(nowMs: Long) {
        if (nowMs - rt.state.lowAccuracyFallbackLastRejectSummaryAtMs < TrackingServiceConstants.FALLBACK_REJECT_SUMMARY_INTERVAL_MS) return
        rt.state.lowAccuracyFallbackLastRejectSummaryAtMs = nowMs
        rt.deps.runtimeTelemetry.event(
            "fallback_reject_summary",
            "rejected=${rt.state.lowAccuracyFallbackRejectedFixCountThisSession} " +
                "armed=${rt.state.lowAccuracyFallbackArmCountThisSession} " +
                "emitted=${rt.state.lowAccuracyFallbackEmitCountThisSession}"
        )
    }

    fun shouldEmitFallbackForTransition(
        previousAcceptedLocation: Location?,
        fallbackCandidateLocation: Location,
        nowMs: Long,
    ): Boolean = FallbackTransitionPolicy.shouldEmitFallbackForTransition(
        previousAcceptedLocation,
        fallbackCandidateLocation,
        nowMs,
        rt.deps.clock.elapsedRealtimeNanos(),
    )

    fun shouldPersistFallbackPoint(
        previousAcceptedLocation: Location?,
        fallbackLocation: Location,
    ): Boolean = FallbackPersistencePolicy.shouldPersistFallbackPoint(previousAcceptedLocation, fallbackLocation)
}
