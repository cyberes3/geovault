package com.geovault.tracker.positioning
import com.geovault.tracker.positioning.PositioningRuntime
import android.location.Location
import android.os.Bundle
import com.geovault.tracker.TrackingLocationPolicy
import com.geovault.tracker.location.FreshnessRecoveryDecision
import com.geovault.tracker.location.PositioningRecoveryConfig
import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.policy.TrackPointRejectReason
import com.geovault.tracker.positioning.PointEmissionTrouble
import com.geovault.tracker.positioning.PositioningContext
import com.geovault.tracker.positioning.config.GpsRuntimeState
import com.geovault.tracker.positioning.config.PositioningDensity
import com.geovault.tracker.positioning.config.PositioningPresetValues
import com.geovault.tracker.positioning.config.PositioningPresets
import com.geovault.tracker.services.LocationIngestResult
import com.geovault.tracker.services.TrackingMotionMode
import com.geovault.tracker.settings.TrackerSettings
import com.geovault.tracker.tracking.TrackingServiceConstants
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class PositioningContextBuilder(private val rt: PositioningRuntime) {
    fun effectivePositioningPreset(
        motionMode: TrackingMotionMode,
        settings: TrackerSettings = rt.deps.settingsRepository.getSettings(),
    ): PositioningPresetValues {
        return PositioningPresets.forMotionMode(
            motionMode,
            PositioningDensity.from(settings),
        )
    }

    fun resolvePointFreshnessIntervalSec(motionMode: TrackingMotionMode): Long {
        return rt.contextBuilder.effectivePositioningPreset(motionMode).locationIntervalSec
    }

    fun resolveActiveMotionMode(): TrackingMotionMode {
        return rt.deps.autoTrackingMotionEngine.snapshot().mode
    }

    fun startSparseTrackingObserver() {
        rt.state.sparseTrackingObserverJob?.cancel()
        rt.state.sparseTrackingObserverJob = rt.serviceScope.launch {
            rt.deps.settingsRepository.observeSettings()
                .map { it.sparseTracking }
                .distinctUntilChanged()
                .drop(1)
                .collect { rt.contextBuilder.onSparseTrackingChanged() }
        }
    }

    fun onSparseTrackingChanged() {
        rt.motion.resetElasticDistanceOverride(reason = "sparse_tracking_changed", reapplyRequest = false)
        rt.locationRequests.reapplyLocationRequestIfActive("sparse_tracking_changed")
        val probeIntervalMs = rt.contextBuilder.currentPositioningRuntimeContext().stationaryProbeIntervalMs
        if (
            rt.state.gpsRuntimeState == GpsRuntimeState.PAUSED_FOR_MOTION ||
            rt.state.gpsRuntimeState == GpsRuntimeState.WAITING_FOR_PROVIDER_PAUSED
        ) {
            rt.deps.stationaryPingController.reschedulePausedPing(
                newIntervalMs = probeIntervalMs,
                providerAvailable = rt.utilities.isGpsProviderEnabled(),
                reason = "sparse_tracking_changed",
            )
        }
        rt.deps.runtimeTelemetry.event(
            "sparse_tracking_changed",
            "probeIntervalMs=$probeIntervalMs sparse=${rt.deps.settingsRepository.getSettings().sparseTracking}"
        )
    }

    fun currentPositioningRecoveryConfig(): PositioningRecoveryConfig {
        return rt.contextBuilder.currentPositioningRuntimeContext(rt.deps.settingsRepository.getSettings()).recoveryConfig
    }

    fun currentPositioningRuntimeContext(
        settings: TrackerSettings = rt.deps.settingsRepository.getSettings(),
    ): PositioningContext {
        val motionMode = rt.contextBuilder.resolveActiveMotionMode()
        val preset = rt.contextBuilder.effectivePositioningPreset(motionMode, settings)
        val baseDistance = preset.distanceFilterMeters
        return PositioningConfig.resolveContext(
            state = rt.state,
            settings = settings,
            activeMotionMode = motionMode,
            pointFreshnessTracker = rt.deps.pointFreshnessTracker,
        ).let { ctx ->
            val effectiveDistance = rt.state.elasticDistanceOverrideMeters ?: baseDistance
            if (effectiveDistance == ctx.distanceFilterMeters) ctx
            else PositioningContext.build(
                settings = settings,
                activeMotionMode = motionMode,
                effectiveDistanceFilterMeters = effectiveDistance,
                localPointMaxGapMs = rt.deps.pointFreshnessTracker.maxAllowedPointGapMs(preset.locationIntervalSec),
                collectionPace = rt.state.collectionPace,
            )
        }
    }

    fun resolvePointEmissionTrouble(
        result: com.geovault.tracker.services.LocationIngestResult,
        nowMs: Long,
        motionMode: TrackingMotionMode,
        effectiveAccuracyThresholdMeters: Float,
    ): PointEmissionTrouble {
        if (result.accepted && result.pointPersisted) return PointEmissionTrouble.None
        val staleLocal = rt.deps.pointFreshnessTracker.shouldForceLocalRecovery(
            nowMs = nowMs,
            intervalSec = rt.contextBuilder.resolvePointFreshnessIntervalSec(motionMode),
        )
        if (!staleLocal) return PointEmissionTrouble.None
        val policyReason = result.policyMetrics?.reason
        val accuracyBlocked = result.rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
            result.rejectReason == TrackPointRejectReason.STALE ||
            result.lastAccuracyMeters == null ||
            result.lastAccuracyMeters > effectiveAccuracyThresholdMeters ||
            rt.state.gpsRuntimeState == GpsRuntimeState.FALLBACK_PENDING
        val reason = when {
            accuracyBlocked -> result.rejectReason?.name ?: "accuracy"
            policyReason != null -> policyReason
            result.adjustmentReason != null -> result.adjustmentReason
            else -> "stale_local_point"
        }
        return PointEmissionTrouble(
            active = true,
            accuracyBlocked = accuracyBlocked,
            reason = reason,
        )
    }

    fun maybeLogFreshnessProbeDecision(
        decision: FreshnessRecoveryDecision,
        result: com.geovault.tracker.services.LocationIngestResult,
        nowMs: Long,
        motionMode: TrackingMotionMode,
    ) {
        if (decision == FreshnessRecoveryDecision.Inactive) return
        if (!rt.deps.freshnessRecoveryController.shouldLog(decision)) return
        val eventName = when (decision) {
            is FreshnessRecoveryDecision.ProbeStarted -> "freshness_probe_started"
            is FreshnessRecoveryDecision.ProbeWait -> "freshness_probe_wait"
            is FreshnessRecoveryDecision.Blocked -> "freshness_probe_blocked"
            FreshnessRecoveryDecision.CommitAnchor -> "freshness_probe_commit"
            FreshnessRecoveryDecision.Inactive -> "freshness_probe_inactive"
        }
        rt.deps.runtimeTelemetry.event(
            eventName,
            "reason=${decision.telemetryValue} " +
                "filterReason=${result.policyMetrics?.reason ?: result.rejectReason ?: "none"} " +
                "localAgeMs=${rt.deps.pointFreshnessTracker.localPointAgeMs(nowMs) ?: -1L} " +
                "uploadAgeMs=${rt.deps.pointFreshnessTracker.uploadAgeMs(nowMs) ?: -1L} " +
                "maxGapMs=${rt.deps.pointFreshnessTracker.maxAllowedPointGapMs(rt.contextBuilder.resolvePointFreshnessIntervalSec(motionMode))}"
        )
    }

    fun buildFreshnessRecoveryLocation(
        anchor: RecoveryAnchorState,
        sourceLocation: Location,
        nowMs: Long,
        nowElapsedRealtimeNanos: Long,
    ): Location {
        val sourceProvider = sourceLocation.provider?.takeIf { it.isNotBlank() } ?: "gps"
        return anchor.toLocation(providerPrefix = "freshness_recovery").apply {
            time = nowMs
            elapsedRealtimeNanos = nowElapsedRealtimeNanos
            provider = "freshness_recovery:$sourceProvider"
            if (sourceLocation.hasAccuracy()) accuracy = sourceLocation.accuracy
            extras = (extras ?: Bundle()).apply {
                putBoolean(TrackingServiceConstants.EXTRAS_KEY_FRESHNESS_RECOVERY, true)
                putString(TrackingServiceConstants.EXTRAS_KEY_FRESHNESS_RECOVERY_SOURCE_PROVIDER, sourceProvider)
            }
        }
    }

    fun updateRecoveryAnchor(
        location: Location,
        source: String,
        motionMode: TrackingMotionMode,
    ) {
        val trackerId = rt.ports.selectedTrackerId()
        if (trackerId.isBlank()) return
        val anchor = RecoveryAnchorState.fromLocation(
            trackerId = trackerId,
            sessionBoundaryId = rt.state.sessionVisibleBoundaryId,
            location = location,
            radiusMeters = TrackingLocationPolicy.DEFAULT_STATIONARY_RADIUS_METERS,
            source = source,
            motionMode = motionMode,
        )
        rt.state.recoveryAnchorState = anchor
        rt.deps.recoveryAnchorStore.save(anchor)
    }

}
