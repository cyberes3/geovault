package com.geovault.tracker.positioning

import com.geovault.tracker.policy.RemoteStreamIngressPolicy

internal class SessionResetCoordinator(private val rt: PositioningRuntime) {

    fun applyForStart(selectedTrackerId: String, sessionStartedAtMs: Long) {
        if (selectedTrackerId.isNotEmpty()) {
            rt.deps.locationIngestCoordinator.resetSession(selectedTrackerId)
        }
        rt.deps.pointFreshnessTracker.reset(sessionStartedAtMs = sessionStartedAtMs)
        rt.deps.repeatedOutlierSuppressor.reset()
        rt.deps.providerHealthController.reset()
        rt.deps.stationaryFreshnessCoordinator.resetSession()
        rt.state.resetForStart()
        rt.deps.lowAccuracyFallbackCoordinator.onTrackingStopped()
        rt.recovery.pausedFreshness.clearPausedFreshnessProbe(
            reason = "start_tracking",
            clearLastFreshnessTimestamp = true,
        )
        rt.deps.autoTrackingMotionEngine.reset(sessionStartedAtMs)
        rt.deps.autoTrackingMotionCoordinator.reset()
        rt.motion.clearSessionImuState()
    }

    fun applyForStop() {
        rt.deps.stationaryFreshnessCoordinator.onStopped(reason = "tracking_stopped")
        rt.recovery.pausedFreshness.clearPausedFreshnessProbe(
            reason = "tracking_stopped",
            clearLastFreshnessTimestamp = true,
        )
        rt.deps.lowAccuracyFallbackCoordinator.onTrackingStopped()
        rt.deps.repeatedOutlierSuppressor.reset()
        rt.deps.freshnessRecoveryController.reset()
        rt.deps.providerHealthController.reset()
        rt.deps.recoveryAnchorStore.clear()
        rt.deps.stationaryFreshnessCoordinator.clearRegion()
        rt.deps.pointFreshnessTracker.reset(sessionStartedAtMs = 0L)
        rt.state.resetForStop()
        rt.deps.autoTrackingMotionCoordinator.reset()
        rt.motion.stopAutoModeTick()
        rt.recovery.fastLock.stopFastGpsLockWindow(reason = "tracking_stopped")
        rt.motion.resetElasticDistanceOverride(reason = "tracking_stopped", reapplyRequest = false)
        // CROSS-SOURCE-ANCHOR-RESET: TrackPointCrossSourceState's per-track "last accepted" anchor
        // is advanced by local GPS fixes while recording but was never cleared when recording
        // stopped. Viewing the same tracker remotely afterward could have its first live points
        // rejected as "older than last known" until a fix newer than the final local fix arrived.
        rt.ports.selectedTrackerId().trim().takeIf(String::isNotEmpty)?.let { stoppedTrackerId ->
            RemoteStreamIngressPolicy.resetTrack(stoppedTrackerId)
        }
    }
}
