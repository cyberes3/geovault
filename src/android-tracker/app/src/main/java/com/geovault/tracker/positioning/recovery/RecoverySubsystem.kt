package com.geovault.tracker.positioning.recovery

import android.location.Location
import com.geovault.tracker.location.LowAccuracyFallbackArmDecision
import com.geovault.tracker.positioning.PositioningRuntime
import com.geovault.tracker.positioning.config.GpsRuntimeEvent
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings

internal class RecoverySubsystem(private val rt: PositioningRuntime) {
    val fastLock = FastGpsLockSubsystem(rt)
    val fallback = LowAccuracyFallbackSubsystem(rt)
    val pausedFreshness = PausedFreshnessSubsystem(rt)
    val jobs = RecoveryJobsSubsystem(rt)

    fun handleRejectedFixRecovery(
        result: LocationIngestResult,
        rejectedLocation: Location,
        settings: TrackerSettings,
        motionMode: TrackingMotionMode,
        effectiveAccuracyThresholdMeters: Float,
        nowMs: Long,
    ) {
        val outlierDecision = rt.deps.repeatedOutlierSuppressor.evaluate(
            candidate = rejectedLocation,
            anchor = rt.state.lastFilteredLocation,
            effectiveAccuracyThresholdMeters = effectiveAccuracyThresholdMeters,
            nowMs = nowMs,
        )
        val repeatedOutlierSuppressed = outlierDecision.suppress
        if (repeatedOutlierSuppressed) {
            rt.deps.runtimeTelemetry.event(
                "repeated_outlier_suppressed",
                "reason=${outlierDecision.reason} repeats=${outlierDecision.repeatCount} " +
                    "accuracy=${if (rejectedLocation.hasAccuracy()) rejectedLocation.accuracy else -1f} " +
                    "lat=${rejectedLocation.latitude} lon=${rejectedLocation.longitude}"
            )
        }
        val fastLockSuppressed = repeatedOutlierSuppressed || fastLock.shouldSuppressFastLockForAutoMotion(
            rejectReason = result.rejectReason,
            nowMs = nowMs,
        )
        if (!fastLockSuppressed) {
            fastLock.maybeStartFastGpsLockWindow(
                measuredAccuracyMeters = if (rejectedLocation.hasAccuracy()) rejectedLocation.accuracy else null,
                rejectReason = result.rejectReason
            )
        }
        if (settings.lowAccuracyFallbackEnabled && !repeatedOutlierSuppressed) {
            rt.collection.transitionGpsState(GpsRuntimeEvent.FIX_REJECTED, "rejected_for_lock:${result.rejectReason}")
            rt.state.lowAccuracyFallbackRejectedFixCountThisSession++
            fallback.maybeLogFallbackRejectSummary(nowMs)
            rt.state.lowAccuracyFallbackCandidate = fallback.selectLowAccuracyFallbackCandidate(
                rejectedLocation = rejectedLocation,
                nowMs = nowMs,
                motionMode = motionMode,
            )
            val armDecision = rt.deps.lowAccuracyFallbackCoordinator.onRejectedFixForLock(
                fallbackEligible = true,
                candidateLatitude = rejectedLocation.latitude,
                candidateLongitude = rejectedLocation.longitude,
                candidateTimestampMs = rejectedLocation.time
            )
            if (armDecision == LowAccuracyFallbackArmDecision.START_TIMER) {
                rt.collection.transitionGpsState(GpsRuntimeEvent.FALLBACK_TIMER_ARMED, "fallback_timer_armed")
                rt.state.lowAccuracyFallbackArmCountThisSession++
                rt.state.lowAccuracyFallbackTimerArmedAtMs = nowMs
                fallback.ensureLowAccuracyFallbackTimerRunning()
            }
        }
    }
}
