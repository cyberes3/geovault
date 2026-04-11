package com.geovault.tracker.data

/**
 * Single app-layer entry for launch- and resume-scale tracker bootstrap I/O.
 * Wraps [TrackerBootstrapOrchestrator]; callers should prefer this type over the orchestrator directly.
 */
class TrackerSessionBootstrap(
    private val orchestrator: TrackerBootstrapOrchestrator,
) {
    fun resetForSignedOutSession() {
        orchestrator.resetLaunchState()
    }

    suspend fun runLaunchBootstrap(): TrackerBootstrapOutcome = orchestrator.refreshForLaunch()

    suspend fun runResumeBootstrap(): TrackerBootstrapOutcome = orchestrator.refreshForResume()
}
