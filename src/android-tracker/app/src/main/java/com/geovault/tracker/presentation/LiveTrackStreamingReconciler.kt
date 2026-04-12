package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.MapStreamingServiceHelper

/**
 * Single owner of [MapStreamingServiceHelper] start/stop for map live streaming.
 * Deduplicates identical logical states; delegates decisions to [TrackerMapStreamingCoordinator].
 */
class LiveTrackStreamingReconciler(
    private val appContext: Context,
) {
    private var lastStreamingServiceSeed: String? = null

    fun invalidateDedupe() {
        lastStreamingServiceSeed = null
    }

    /** Unconditional stop (e.g. map context reset); clears dedupe so the next reconcile can start fresh. */
    fun stopForegroundStreaming() {
        MapStreamingServiceHelper.stopStreaming(appContext)
        lastStreamingServiceSeed = null
    }

    fun reconcile(state: TrackerMapUiState, effectiveDisplayedId: String, effectiveDisplayedName: String) {
        val streamIdsSignature = state.streamTargetIds.toList().sorted().joinToString(separator = ",")
        val seed =
            "${state.mode}|${state.runtime.isRunning}|$streamIdsSignature|$effectiveDisplayedId|" +
                "${state.runtime.selectedTrackerId}|$effectiveDisplayedName"
        if (seed == lastStreamingServiceSeed) return
        lastStreamingServiceSeed = seed
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = state.mode,
                streamTargetIds = state.streamTargetIds,
                displayedTrackerId = effectiveDisplayedId,
                displayedTrackerName = effectiveDisplayedName,
                selectedTrackerId = state.runtime.selectedTrackerId,
                trackingRunning = state.runtime.isRunning,
            )
        )
        when (command) {
            is TrackerMapStreamingCommand.Start -> {
                MapStreamingServiceHelper.startStreaming(
                    context = appContext,
                    trackerIds = command.trackerIds,
                    trackerName = command.trackerName,
                )
            }
            TrackerMapStreamingCommand.Stop -> {
                MapStreamingServiceHelper.stopStreaming(appContext)
            }
            TrackerMapStreamingCommand.NoOp -> Unit
        }
    }
}
