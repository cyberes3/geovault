package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot

/**
 * Owns the map streaming request and delegates service ownership to [LiveTrackStreamingTargetCoordinator].
 * Deduplicates identical logical states; delegates map decisions to [TrackerMapStreamingCoordinator].
 */
class LiveTrackStreamingReconciler(
    private val appContext: Context,
) {
    private var lastStreamingServiceSeed: String? = null
    private var mapStreamingLeaseActive: Boolean = false

    fun invalidateDedupe() {
        lastStreamingServiceSeed = null
        LiveTrackStreamingTargetCoordinator.resetApplyGate()
    }

    fun hasMapStreamingLease(): Boolean = mapStreamingLeaseActive

    fun consumeStoppedMapStreamingLease(): Boolean {
        val wasMapOwned = mapStreamingLeaseActive
        mapStreamingLeaseActive = false
        return wasMapOwned
    }

    /** Unconditional stop (e.g. map context reset); clears dedupe so the next reconcile can start fresh. */
    fun stopForegroundStreaming() {
        mapStreamingLeaseActive = false
        LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = appContext,
            owner = LiveTrackStreamingOwner.Map,
            request = null,
        )
        lastStreamingServiceSeed = null
        LiveTrackStreamingTargetCoordinator.resetApplyGate()
    }

    fun reconcile(
        state: TrackerMapUiState,
        effectiveDisplayedId: String,
        effectiveDisplayedName: String,
        streamRuntime: LiveStreamRuntimeSnapshot,
    ) {
        val streamIdsSignature = state.streamTargetIds.toList().sorted().joinToString(separator = ",")
        val activeIdsSignature = streamRuntime.activeTrackerIds.map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(separator = ",")
        val trackingActiveOrStarting = state.runtime.localRecordingActive
        val selectedTrackerId = state.runtime.selectedTrackerId.trim()
        val seed =
            "${state.mode}|$trackingActiveOrStarting|$streamIdsSignature|$effectiveDisplayedId|" +
                "$selectedTrackerId|$effectiveDisplayedName|" +
                "${streamRuntime.hasSubscriptionIntent}|${streamRuntime.lifecycleState.name}|$activeIdsSignature|" +
                "${streamRuntime.failureReason.orEmpty()}"
        if (seed == lastStreamingServiceSeed) return
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = state.mode,
                streamTargetIds = state.streamTargetIds,
                displayedTrackerId = effectiveDisplayedId,
                displayedTrackerName = effectiveDisplayedName,
                selectedTrackerId = selectedTrackerId,
                trackingRunning = trackingActiveOrStarting,
            )
        )
        when (command) {
            is TrackerMapStreamingCommand.Start -> {
                val locallyRecordedTrackerId = selectedTrackerId
                    .takeIf { trackingActiveOrStarting }
                val result = LiveTrackStreamingTargetCoordinator.replaceRequest(
                    context = appContext,
                    owner = LiveTrackStreamingOwner.Map,
                    request = LiveTrackStreamingTargetRequest(
                        trackerIds = command.trackerIds,
                        trackerName = command.trackerName,
                        locallyRecordedTrackerId = locallyRecordedTrackerId,
                        excludedTrackerIds = StreamingTargetPolicy.selectedTrackerExclusion(selectedTrackerId),
                    ),
                )
                if (result is StreamingSubscriptionApplyResult.Applied) {
                    mapStreamingLeaseActive = command.trackerIds.isNotEmpty()
                    lastStreamingServiceSeed = seed
                } else {
                    lastStreamingServiceSeed = null
                }
            }
            TrackerMapStreamingCommand.Stop -> {
                val result = LiveTrackStreamingTargetCoordinator.replaceRequest(
                    context = appContext,
                    owner = LiveTrackStreamingOwner.Map,
                    request = null,
                )
                if (result is StreamingSubscriptionApplyResult.Applied) {
                    mapStreamingLeaseActive = false
                    lastStreamingServiceSeed = seed
                } else {
                    lastStreamingServiceSeed = null
                }
            }
            TrackerMapStreamingCommand.NoOp -> {
                // Single-session with no resolved displayed id cannot own a map streaming lease; Params may keep streaming alone.
                if (state.mode == TrackerMapDisplayMode.SINGLE_SESSION && effectiveDisplayedId.trim().isEmpty()) {
                    LiveTrackStreamingTargetCoordinator.replaceRequest(
                        context = appContext,
                        owner = LiveTrackStreamingOwner.Map,
                        request = null,
                    )
                    mapStreamingLeaseActive = false
                }
                lastStreamingServiceSeed = seed
            }
        }
    }
}
