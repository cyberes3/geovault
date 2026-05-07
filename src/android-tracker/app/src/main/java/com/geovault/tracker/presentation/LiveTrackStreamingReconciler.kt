package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot

/**
 * Owns the map streaming request and delegates service ownership to [LiveTrackStreamingTargetCoordinator].
 *
 * COMBINED-RECONCILE: this class is intentionally pure with respect to dedupe — duplicate
 * back-to-back reconcile inputs are suppressed by the combined `distinctUntilChangedBy` flow in
 * [TrackerMapViewModel], and duplicate service-action dispatch is absorbed by
 * [LiveTrackStreamingTargetCoordinator]'s `lastAppliedIds` gate. The only state we keep here is
 * the map-ownership lease so a post-stop "restore selected tracker" hook can fire only when the
 * map (and not Tracker Params) was the active subscriber.
 */
class LiveTrackStreamingReconciler(
    private val appContext: Context,
) {
    private var mapStreamingLeaseActive: Boolean = false

    fun hasMapStreamingLease(): Boolean = mapStreamingLeaseActive

    fun consumeStoppedMapStreamingLease(): Boolean {
        val wasMapOwned = mapStreamingLeaseActive
        mapStreamingLeaseActive = false
        return wasMapOwned
    }

    /** Unconditional stop (e.g. map context reset). */
    fun stopForegroundStreaming() {
        mapStreamingLeaseActive = false
        LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = appContext,
            owner = LiveTrackStreamingOwner.Map,
            request = null,
        )
        LiveTrackStreamingTargetCoordinator.resetApplyGate()
    }

    fun reconcile(
        state: TrackerMapUiState,
        effectiveDisplayedId: String,
        effectiveDisplayedName: String,
        @Suppress("UNUSED_PARAMETER") streamRuntime: LiveStreamRuntimeSnapshot,
    ) {
        // streamRuntime is intentionally part of the signature (not re-read from a global) so the
        // reconciler observes whatever snapshot the caller paired with `state` — the combined
        // reconcile flow guarantees these come from a single coherent tick. The reconciler itself
        // does not branch on stream health today; that decision lives in the projector + the
        // coordinator's lastAppliedIds gate.
        val locallyRecordedTrackerId = state.runtime.locallyRecordedTrackerId
        val command = TrackerMapStreamingCoordinator.resolve(
            TrackerMapStreamingDecisionInput(
                mode = state.mode,
                streamTargetIds = state.streamTargetIds,
                displayedTrackerId = effectiveDisplayedId,
                displayedTrackerName = effectiveDisplayedName,
            )
        )
        when (command) {
            is TrackerMapStreamingCommand.Start -> {
                // STREAMING EXCLUSION: the only tracker that should never be streamed is the
                // locally-recorded one. Per-mode targeting is already encoded in command.trackerIds
                // by the projector; the only thing this layer adds to the per-owner request is
                // the locally-recorded id, so a parallel Params owner can't accidentally subscribe
                // to the actively-recorded tracker via its half of the merged plan.
                val result = LiveTrackStreamingTargetCoordinator.replaceRequest(
                    context = appContext,
                    owner = LiveTrackStreamingOwner.Map,
                    request = LiveTrackStreamingTargetRequest(
                        trackerIds = command.trackerIds,
                        trackerName = command.trackerName,
                        locallyRecordedTrackerId = locallyRecordedTrackerId,
                    ),
                )
                if (result is StreamingSubscriptionApplyResult.Applied) {
                    mapStreamingLeaseActive = command.trackerIds.isNotEmpty()
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
                }
            }
            TrackerMapStreamingCommand.NoOp -> {
                // Single-session with no resolved displayed id cannot own a map streaming lease;
                // Params may still keep streaming alone.
                if (state.mode == TrackerMapDisplayMode.SINGLE_SESSION && effectiveDisplayedId.trim().isEmpty()) {
                    LiveTrackStreamingTargetCoordinator.replaceRequest(
                        context = appContext,
                        owner = LiveTrackStreamingOwner.Map,
                        request = null,
                    )
                    mapStreamingLeaseActive = false
                }
            }
        }
    }
}
