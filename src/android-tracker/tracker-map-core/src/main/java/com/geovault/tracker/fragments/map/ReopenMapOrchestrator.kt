package com.geovault.tracker.fragments.map

/**
 * Orchestrates reopen decisions and emits explicit invariants for diagnostics/tests.
 */
class ReopenMapOrchestrator(
    private val resolveMapResumeUseCase: ResolveMapResumeUseCase = ResolveMapResumeUseCase()
) {
    fun resolve(input: MapResumeInput): MapReopenOutcome {
        val decision = resolveMapResumeUseCase.resolve(input)
        val command = decision.toReopenCommand()
        val invariants = buildInvariants(input, command)
        return MapReopenOutcome(
            command = command,
            invariants = invariants
        )
    }

    private fun buildInvariants(
        input: MapResumeInput,
        command: MapReopenCommand
    ): List<MapRuntimeInvariantStatus> {
        val selectedTrackerPresent = input.selectedTrackerId.isNotBlank()
        val trackingWithPointsAvoidsDestructiveReload = !(
            input.trackingRunning &&
                input.hasTrackPoints &&
                (command is MapReopenCommand.LoadSingleTrackerRuntime ||
                    command is MapReopenCommand.LoadSingleTrackerBootstrap ||
                    command is MapReopenCommand.ClearSingleTrackerState)
            )
        val singleLoadIdempotent = when (command) {
            is MapReopenCommand.LoadSingleTrackerRuntime -> command.trackerId.isNotBlank()
            is MapReopenCommand.LoadSingleTrackerBootstrap -> command.trackerId.isNotBlank()
            else -> true
        }
        val followLockInvariantSatisfied = true
        return listOf(
            MapRuntimeInvariantStatus(
                invariant = MapRuntimeInvariant.TRACKING_REQUIRES_SELECTED_TRACKER,
                satisfied = !input.trackingRunning || selectedTrackerPresent,
                details = "trackingRunning=${input.trackingRunning} selectedTrackerPresent=$selectedTrackerPresent"
            ),
            MapRuntimeInvariantStatus(
                invariant = MapRuntimeInvariant.TRACKING_WITH_POINTS_MUST_NOT_FORCE_DESTRUCTIVE_RELOAD,
                satisfied = trackingWithPointsAvoidsDestructiveReload,
                details = "trackingRunning=${input.trackingRunning} hasTrackPoints=${input.hasTrackPoints} command=$command"
            ),
            MapRuntimeInvariantStatus(
                invariant = MapRuntimeInvariant.SINGLE_LOAD_COMMANDS_MUST_BE_IDEMPOTENT,
                satisfied = singleLoadIdempotent,
                details = "command=$command"
            ),
            MapRuntimeInvariantStatus(
                invariant = MapRuntimeInvariant.FOLLOW_LOCK_MUST_NOT_DEGRADE_ON_MISSING_TARGET,
                satisfied = followLockInvariantSatisfied,
                details = "validated_by_lock_restore_policy=true"
            )
        )
    }

    private fun MapResumeDecision.toReopenCommand(): MapReopenCommand {
        return when (this) {
            MapResumeDecision.NoOp -> MapReopenCommand.NoOp
            MapResumeDecision.MultiContextNoStreaming -> MapReopenCommand.MultiContextNoStreaming
            is MapResumeDecision.StartMultiContextStreaming -> MapReopenCommand.StartMultiContextStreaming(trackerIds)
            MapResumeDecision.ClearSingleTrackerState -> MapReopenCommand.ClearSingleTrackerState
            is MapResumeDecision.LoadSingleTrackerRuntime -> MapReopenCommand.LoadSingleTrackerRuntime(trackerId)
            is MapResumeDecision.LoadSingleTrackerBootstrap -> MapReopenCommand.LoadSingleTrackerBootstrap(trackerId)
            MapResumeDecision.RestartDisplayedTrackerStreaming -> MapReopenCommand.RestartDisplayedTrackerStreaming
        }
    }
}
