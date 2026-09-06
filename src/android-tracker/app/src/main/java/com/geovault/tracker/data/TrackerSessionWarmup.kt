package com.geovault.tracker.data

/**
 * UI-scoped launch/resume warmup for tracker lists. Not process bootstrap —
 * [com.geovault.common.bootstrap.GeoVaultAppBootstrap] owns process start.
 */
class TrackerSessionWarmup(
    private val orchestrator: TrackerBootstrapOrchestrator,
) {
    fun resetForSignedOutSession() {
        orchestrator.resetLaunchState()
    }

    suspend fun runLaunchWarmup(): TrackerBootstrapOutcome = orchestrator.refreshForLaunch()

    suspend fun runResumeWarmup(): TrackerBootstrapOutcome = orchestrator.refreshForResume()
}
