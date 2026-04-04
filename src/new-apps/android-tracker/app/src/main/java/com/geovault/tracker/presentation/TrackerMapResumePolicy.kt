package com.geovault.tracker.presentation

enum class TrackerMapViewContext {
    SINGLE_TRACKER,
    GROUP
}

data class TrackerMapResumeInput(
    val trackingRunning: Boolean,
    val mapReady: Boolean,
    val showAllTrackers: Boolean,
    val mapViewContext: TrackerMapViewContext,
    val activeStreamedTrackerIds: Set<String>,
    val currentGroupTrackIds: Set<String>,
    val selectedTrackerId: String,
    val displayedTrackerId: String,
    val hasTrailPoints: Boolean,
    val hasPendingInitialTracker: Boolean = false,
    val backgroundedDurationMs: Long,
)

sealed class TrackerMapResumeDecision {
    data object NoOp : TrackerMapResumeDecision()
    data object MultiContextNoStreaming : TrackerMapResumeDecision()
    data class StartMultiContextStreaming(val trackerIds: Set<String>) : TrackerMapResumeDecision()
    data object ClearSingleTrackerState : TrackerMapResumeDecision()
    data class LoadSingleTrackerRuntime(val trackerId: String) : TrackerMapResumeDecision()
    data class LoadSingleTrackerBootstrap(val trackerId: String) : TrackerMapResumeDecision()
    data object RestartDisplayedTrackerStreaming : TrackerMapResumeDecision()
}

enum class TrackerMapRuntimeInvariant {
    TRACKING_REQUIRES_SELECTED_TRACKER,
    TRACKING_WITH_POINTS_MUST_NOT_FORCE_DESTRUCTIVE_RELOAD,
    SINGLE_LOAD_COMMANDS_MUST_BE_IDEMPOTENT,
}

data class TrackerMapRuntimeInvariantStatus(
    val invariant: TrackerMapRuntimeInvariant,
    val satisfied: Boolean,
    val details: String,
)

data class TrackerMapReopenOutcome(
    val decision: TrackerMapResumeDecision,
    val invariants: List<TrackerMapRuntimeInvariantStatus>,
)

class TrackerMapResolveResumeUseCase {
    fun resolve(input: TrackerMapResumeInput): TrackerMapResumeDecision {
        if (!input.mapReady) return TrackerMapResumeDecision.NoOp

        if (input.trackingRunning) {
            val selectedTrackerId = input.selectedTrackerId.takeIf { it.isNotBlank() }
            val streamedWithoutSelected = input.activeStreamedTrackerIds.filterTo(mutableSetOf()) { id ->
                id.isNotBlank() && id != selectedTrackerId
            }
            if (input.mapViewContext == TrackerMapViewContext.GROUP || input.showAllTrackers) {
                val fallbackGroupIds = input.currentGroupTrackIds.filterTo(mutableSetOf()) { id ->
                    id.isNotBlank() && id != selectedTrackerId
                }
                return when {
                    streamedWithoutSelected.isNotEmpty() ->
                        TrackerMapResumeDecision.StartMultiContextStreaming(streamedWithoutSelected)
                    input.mapViewContext == TrackerMapViewContext.GROUP && fallbackGroupIds.isNotEmpty() ->
                        TrackerMapResumeDecision.StartMultiContextStreaming(fallbackGroupIds)
                    !selectedTrackerId.isNullOrEmpty() ->
                        TrackerMapResumeDecision.LoadSingleTrackerRuntime(selectedTrackerId)
                    else -> TrackerMapResumeDecision.MultiContextNoStreaming
                }
            }

            val displayedTrackerId = input.displayedTrackerId.takeIf { it.isNotBlank() }
            val activeSingleTrackerId = displayedTrackerId ?: selectedTrackerId.orEmpty()
            if (activeSingleTrackerId.isEmpty() && !input.hasPendingInitialTracker) {
                return TrackerMapResumeDecision.ClearSingleTrackerState
            }
            if (input.hasTrailPoints && displayedTrackerId == null && !selectedTrackerId.isNullOrEmpty()) {
                return TrackerMapResumeDecision.NoOp
            }
            if (input.hasTrailPoints && displayedTrackerId == activeSingleTrackerId) {
                return TrackerMapResumeDecision.NoOp
            }
            if (displayedTrackerId != null && displayedTrackerId in streamedWithoutSelected) {
                return TrackerMapResumeDecision.RestartDisplayedTrackerStreaming
            }
            if (activeSingleTrackerId.isNotEmpty() && activeSingleTrackerId == selectedTrackerId) {
                return TrackerMapResumeDecision.LoadSingleTrackerRuntime(activeSingleTrackerId)
            }
            return TrackerMapResumeDecision.NoOp
        }

        if (input.mapViewContext == TrackerMapViewContext.GROUP || input.showAllTrackers) {
            return when {
                input.activeStreamedTrackerIds.isNotEmpty() ->
                    TrackerMapResumeDecision.StartMultiContextStreaming(input.activeStreamedTrackerIds)
                input.mapViewContext == TrackerMapViewContext.GROUP && input.currentGroupTrackIds.isNotEmpty() ->
                    TrackerMapResumeDecision.StartMultiContextStreaming(input.currentGroupTrackIds)
                else -> TrackerMapResumeDecision.MultiContextNoStreaming
            }
        }

        val activeTrackerId = resolveActiveSingleTrackerId(
            trackingRunning = input.trackingRunning,
            displayedTrackerId = input.displayedTrackerId,
            selectedTrackerId = input.selectedTrackerId
        )
        if (activeTrackerId.isEmpty() && !input.hasPendingInitialTracker) {
            return TrackerMapResumeDecision.ClearSingleTrackerState
        }
        val displayedTrackerId = input.displayedTrackerId.takeIf { it.isNotBlank() }
        if (activeTrackerId.isNotEmpty() && displayedTrackerId != activeTrackerId) {
            val isStreamBootstrap = activeTrackerId in input.activeStreamedTrackerIds
            return if (isStreamBootstrap) {
                TrackerMapResumeDecision.LoadSingleTrackerBootstrap(activeTrackerId)
            } else {
                TrackerMapResumeDecision.LoadSingleTrackerRuntime(activeTrackerId)
            }
        }
        if (!input.hasTrailPoints && activeTrackerId.isNotEmpty()) {
            val isStreamBootstrap = activeTrackerId in input.activeStreamedTrackerIds
            return if (isStreamBootstrap) {
                TrackerMapResumeDecision.LoadSingleTrackerBootstrap(activeTrackerId)
            } else {
                TrackerMapResumeDecision.LoadSingleTrackerRuntime(activeTrackerId)
            }
        }
        if (input.hasTrailPoints &&
            activeTrackerId.isNotEmpty() &&
            input.backgroundedDurationMs >= BACKFILL_MIN_BACKGROUND_MS
        ) {
            val isStreamBootstrap = activeTrackerId in input.activeStreamedTrackerIds
            return if (isStreamBootstrap) {
                TrackerMapResumeDecision.LoadSingleTrackerBootstrap(activeTrackerId)
            } else {
                TrackerMapResumeDecision.LoadSingleTrackerRuntime(activeTrackerId)
            }
        }
        return TrackerMapResumeDecision.RestartDisplayedTrackerStreaming
    }

    private fun resolveActiveSingleTrackerId(
        trackingRunning: Boolean,
        displayedTrackerId: String,
        selectedTrackerId: String,
    ): String {
        if (trackingRunning) return selectedTrackerId
        return displayedTrackerId.takeIf { it.isNotBlank() } ?: selectedTrackerId
    }

    companion object {
        const val BACKFILL_MIN_BACKGROUND_MS = 15_000L
    }
}

class TrackerMapReopenOrchestrator(
    private val resolver: TrackerMapResolveResumeUseCase = TrackerMapResolveResumeUseCase()
) {
    fun resolve(input: TrackerMapResumeInput): TrackerMapReopenOutcome {
        val decision = resolver.resolve(input)
        return TrackerMapReopenOutcome(
            decision = decision,
            invariants = buildInvariants(input, decision)
        )
    }

    private fun buildInvariants(
        input: TrackerMapResumeInput,
        decision: TrackerMapResumeDecision,
    ): List<TrackerMapRuntimeInvariantStatus> {
        val selectedTrackerPresent = input.selectedTrackerId.isNotBlank()
        val trackingWithPointsAvoidsDestructiveReload = !(
            input.trackingRunning &&
                input.hasTrailPoints &&
                (decision is TrackerMapResumeDecision.LoadSingleTrackerRuntime ||
                    decision is TrackerMapResumeDecision.LoadSingleTrackerBootstrap ||
                    decision is TrackerMapResumeDecision.ClearSingleTrackerState)
            )
        val singleLoadIdempotent = when (decision) {
            is TrackerMapResumeDecision.LoadSingleTrackerRuntime -> decision.trackerId.isNotBlank()
            is TrackerMapResumeDecision.LoadSingleTrackerBootstrap -> decision.trackerId.isNotBlank()
            else -> true
        }
        return listOf(
            TrackerMapRuntimeInvariantStatus(
                invariant = TrackerMapRuntimeInvariant.TRACKING_REQUIRES_SELECTED_TRACKER,
                satisfied = !input.trackingRunning || selectedTrackerPresent,
                details = "trackingRunning=${input.trackingRunning} selectedTrackerPresent=$selectedTrackerPresent",
            ),
            TrackerMapRuntimeInvariantStatus(
                invariant = TrackerMapRuntimeInvariant.TRACKING_WITH_POINTS_MUST_NOT_FORCE_DESTRUCTIVE_RELOAD,
                satisfied = trackingWithPointsAvoidsDestructiveReload,
                details = "trackingRunning=${input.trackingRunning} hasTrackPoints=${input.hasTrailPoints} decision=$decision",
            ),
            TrackerMapRuntimeInvariantStatus(
                invariant = TrackerMapRuntimeInvariant.SINGLE_LOAD_COMMANDS_MUST_BE_IDEMPOTENT,
                satisfied = singleLoadIdempotent,
                details = "decision=$decision",
            ),
        )
    }
}
