package com.geovault.tracker.positioning

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
    }
}
