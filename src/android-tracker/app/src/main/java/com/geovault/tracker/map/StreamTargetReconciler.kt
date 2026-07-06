package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.streaming.LiveStreamSubscriptionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the reconcile-token bookkeeping and the single decision point that turns the current
 * [TrackerMapUiState] into a WebSocket streaming lease via
 * [com.geovault.tracker.presentation.LiveTrackStreamingReconciler]. Kept as its own collaborator,
 * distinct from [StreamRosterResolver], because "what should be on screen" (roster resolution)
 * and "does the lease reflect that right now" (reconcile) react to different signals: ui-state
 * changes flow straight into [reconcileStreaming] via the combined flow in
 * `MapStreamingSubsystem.startCollectors`, whereas [StreamRosterResolver.refreshStreamTargets]
 * must also run on tracker-management-store fingerprint ticks that don't necessarily change
 * ui state at all.
 */
internal class StreamTargetReconciler(private val rt: TrackerMapRuntime) {
    // Explicit invalidation signal for the combined-reconcile flow in
    // `MapStreamingSubsystem.startCollectors` -- see [reconcileToken].
    private val reconcileTokenMutable = MutableStateFlow(0L)
    internal val reconcileToken: StateFlow<Long> = reconcileTokenMutable.asStateFlow()

    internal fun bumpReconcileToken() {
        reconcileTokenMutable.value = reconcileTokenMutable.value + 1L
    }

    /**
     * COMBINED-RECONCILE: stable string key that captures every input the reconciler reads. Two
     * adjacent ticks with the same key are deduped; any change here triggers exactly one
     * reconcile call.
     */
    internal fun reconcileSeedKey(
        state: TrackerMapUiState,
        streamRuntime: LiveStreamSubscriptionState,
        token: Long,
    ): String {
        // Runs once per emission of the combined (uiState, streamRuntime, token) flow, which
        // includes every accepted track point (via TrackPointReducer's trail mutation) -- see
        // TrackerMapStreamingPlanCache for why this must not re-scan the roster per point.
        val plan = rt.streamingPlanCache.resolve(state, rt::projectSession)
        val streamIdsSignature = state.streamTargetIds.toList().sorted().joinToString(separator = ",")
        val activeIdsSignature = streamRuntime.activeTargets
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(separator = ",")
        val trackingActiveOrStarting = state.runtime.localRecordingActive
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        return "${state.mode}|$trackingActiveOrStarting|$streamIdsSignature|${plan.displayedTrackerId}|" +
            "$selectedTrackerId|${plan.displayedTrackerName}|" +
            "${streamRuntime.wantsSubscription}|${streamRuntime.connection.name}|$activeIdsSignature|" +
            "${streamRuntime.failureReason.orEmpty()}|$token"
    }

    internal fun reconcileStreaming(state: TrackerMapUiState) {
        // The immediately-preceding `reconcileSeedKey` call (from the same combined-flow tick)
        // already resolved this exact state's plan, so this reuses that cached result instead of
        // re-scanning the roster a second time for the same tick. The plan's own
        // `remoteSubscriptionIds` -- not `state.streamTargetIds` -- is what actually drives the
        // lease decision below; see [LiveTrackStreamingReconciler] for why.
        val plan = rt.streamingPlanCache.resolve(state, rt::projectSession)
        rt.dependencies.streamingReconciler.reconcile(
            mode = plan.mode,
            remoteSubscriptionIds = plan.remoteSubscriptionIds,
            locallyRecordedTrackerId = state.runtime.locallyRecordedTrackerId,
            effectiveDisplayedId = plan.displayedTrackerId,
            effectiveDisplayedName = plan.displayedTrackerName,
        )
    }
}
