package com.geovault.tracker.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface StationaryFreshnessActions {
    fun logEvent(name: String, details: String)
    fun onProbeTimeout()
}

data class StationaryPoorAccuracyProbeState(
    val poorAccuracyFixes: Int,
    val probeAgeMs: Long,
)

class StationaryFreshnessCoordinator(
    private val store: StationaryRegionStore,
    private val pingController: StationaryPingController,
    private val scope: CoroutineScope,
    private val actions: StationaryFreshnessActions,
) {
    var state: StationaryRegionState = StationaryRegionState()
        private set

    private var probeTimeoutJob: Job? = null

    val hasRegion: Boolean
        get() = state.hasRegion

    val radiusMeters: Float
        get() = state.radiusMeters

    val lastFreshnessPointAtMs: Long
        get() = state.lastFreshnessPointAtMs

    val probeActive: Boolean
        get() = state.probeActive

    val probeStartedAtMs: Long
        get() = state.probeStartedAtMs

    val poorAccuracyFixes: Int
        get() = state.poorAccuracyFixes

    fun resetSession() {
        state = StationaryRegionState()
        cancelProbeTimeout()
    }

    fun enterRegion(anchor: RecoveryAnchorState, nowMs: Long) {
        state = state.enter(anchor = anchor, nowMs = nowMs)
        store.save(state)
    }

    fun restore(trackerId: String, sessionBoundaryId: Long): StationaryRegionState? {
        val restored = store.load(trackerId = trackerId, sessionBoundaryId = sessionBoundaryId) ?: return null
        state = restored
        return restored
    }

    fun schedulePausedPing(reason: String, providerAvailable: Boolean) {
        pingController.onPaused(reason = reason, providerAvailable = providerAvailable)
    }

    fun onProviderPaused(reason: String) {
        pingController.onProviderPaused(reason = reason)
    }

    fun onProviderRestored(reason: String) {
        pingController.onProviderRestored(reason = reason)
    }

    fun onResumed(reason: String) {
        pingController.onResumed(reason = reason)
    }

    fun onStopped(reason: String) {
        pingController.onStopped(reason = reason)
    }

    fun startProbe(
        nowMs: Long,
        timeoutMs: Long,
        details: String,
    ) {
        state = state.startProbe(nowMs)
        store.save(state)
        probeTimeoutJob?.cancel()
        probeTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (state.probeActive && state.probeStartedAtMs == nowMs) {
                actions.logEvent("paused_freshness_probe_timeout", "ageMs=$timeoutMs")
                clearProbe(reason = "timeout")
                actions.onProbeTimeout()
            }
        }
        actions.logEvent("paused_freshness_probe_started", details)
    }

    fun recordPoorAccuracyFix(nowMs: Long): StationaryPoorAccuracyProbeState {
        state = state.recordPoorAccuracyFix()
        store.save(state)
        val probeAgeMs = nowMs - state.probeStartedAtMs.takeIf { it > 0L }.orDefault(nowMs)
        return StationaryPoorAccuracyProbeState(
            poorAccuracyFixes = state.poorAccuracyFixes,
            probeAgeMs = probeAgeMs,
        )
    }

    fun markFreshnessPointPersisted(nowMs: Long) {
        state = state.markFreshnessPointPersisted(nowMs)
        store.save(state)
    }

    fun clearProbe(
        reason: String,
        clearLastFreshnessTimestamp: Boolean = false,
    ) {
        if (state.probeActive || state.probeStartedAtMs > 0L) {
            actions.logEvent(
                "paused_freshness_probe_cleared",
                "reason=$reason active=${state.probeActive} startedAt=${state.probeStartedAtMs}"
            )
        }
        state = state.clearProbe(clearLastFreshnessTimestamp)
        store.save(state)
        cancelProbeTimeout()
    }

    fun clearRegion() {
        state = state.clear()
        store.clear()
        cancelProbeTimeout()
    }

    fun nextFreshnessDueAtMs(intervalMs: Long): Long? {
        return state.nextFreshnessDueAtMs(intervalMs)
    }

    private fun cancelProbeTimeout() {
        probeTimeoutJob?.cancel()
        probeTimeoutJob = null
    }

    private fun Long?.orDefault(defaultValue: Long): Long = this ?: defaultValue
}
