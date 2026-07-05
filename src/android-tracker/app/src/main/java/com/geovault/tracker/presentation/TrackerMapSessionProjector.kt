package com.geovault.tracker.presentation

import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput
import com.geovault.tracker.services.TrackingRuntimeSnapshot

data class TrackerMapSessionIntent(
    val mode: TrackerMapDisplayMode,
    val runtime: TrackingRuntimeSnapshot,
    val displayedTrackerId: String,
    val displayedTrackerName: String,
    val rosterTrackerIds: Set<String>,
    val groupSelection: TrackerMapGroupModeSelection,
    val activeStreamedTrackerIds: Set<String>,
)

data class TrackerMapStreamingPlan(
    val mode: TrackerMapDisplayMode,
    val selectedTrackerId: String,
    val displayedTrackerId: String,
    val displayedTrackerName: String,
    val resolvedGroupId: String,
    val groupTrackerIds: Set<String>,
    val visibleRosterTrackerIds: Set<String>,
    val locallyRecordedTrackerIds: Set<String>,
    val remoteSubscriptionIds: Set<String>,
    val acceptedRemoteTrackerIds: Set<String>,
    val localOverlayTrackerIds: Set<String>,
    val trailReloadPlan: TrackerMapTrailReloadPlan,
)

object TrackerMapSessionProjector {
    fun project(input: TrackerMapSessionIntent): TrackerMapStreamingPlan {
        val selectedTrackerId = input.runtime.selectedTrackerId.trim()
        val runtimeRunning = input.runtime.localRecordingActive
        val locallyRecordedTrackerId = input.runtime.locallyRecordedTrackerId
        val displayedTrackerId = input.displayedTrackerId.trim().ifBlank { selectedTrackerId }
        val displayedTrackerName = input.displayedTrackerName.trim().ifBlank {
            input.runtime.selectedTrackerName.trim()
        }
        val groupTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(input.groupSelection.trackerIds)
        val rosterTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(input.rosterTrackerIds)
        val localTrackerIds = if (locallyRecordedTrackerId.isNotEmpty()) {
            setOf(locallyRecordedTrackerId)
        } else {
            emptySet()
        }
        // STREAMING TARGETING: build the per-mode requested set. In SINGLE_SESSION the dedicated
        // selected-tracker view is intentionally history-only (no streaming subscription when the
        // displayed tracker IS the selected one). For any other displayed tracker in SINGLE we
        // stream that one tracker. GROUP / ALL-QUEUE stream the full set, with only the
        // locally-recorded tracker excluded below — the local GPS feed is the source of truth
        // for our own active recording; everything else, including the selected tracker when
        // we are not recording, is fair game for streaming.
        val requestedRemoteTrackerIds: Set<String> = when (input.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                val id = displayedTrackerId
                when {
                    id.isEmpty() -> emptySet()
                    StreamingTargetPolicy.isHistoryOnlyView(id, selectedTrackerId) -> emptySet()
                    else -> setOf(id)
                }
            }
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupTrackerIds
            TrackerMapDisplayMode.ALL_QUEUE -> rosterTrackerIds
        }
        val remoteSubscriptionIds = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = requestedRemoteTrackerIds,
                locallyRecordedTrackerIds = localTrackerIds,
            )
        )
        // DEAD-CODE REMOVAL: accepted remote ids used to be computed via
        // `TrackerMapRemoteAcceptancePolicy.mergedAcceptedRemoteTrackerIds`, but that function's
        // result (`projectedIds + (activeIds intersect projectedIds)`) is always exactly
        // `projectedIds` — a set unioned with its own subset is unchanged. `activeStreamedTrackerIds`
        // was never actually able to widen or narrow the accepted set, so accepted ids are simply
        // the (already locally-recorded-excluded) remote subscription ids.
        val acceptedRemoteTrackerIds = remoteSubscriptionIds
        val localOverlayTrackerIds = when {
            localTrackerIds.isEmpty() -> emptySet()
            input.mode == TrackerMapDisplayMode.ALL_QUEUE -> localTrackerIds
            input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && locallyRecordedTrackerId in groupTrackerIds -> localTrackerIds
            input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                (displayedTrackerId.isEmpty() || displayedTrackerId == locallyRecordedTrackerId) -> localTrackerIds
            else -> emptySet()
        }
        val trailReloadPlan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = input.mode,
                runtimeRunning = runtimeRunning,
                selectedTrackerId = selectedTrackerId,
                locallyRecordedTrackerId = locallyRecordedTrackerId,
                activeTrackerId = displayedTrackerId,
                rosterTrackerIds = rosterTrackerIds,
                groupSelection = input.groupSelection,
            )
        )
        return TrackerMapStreamingPlan(
            mode = input.mode,
            selectedTrackerId = selectedTrackerId,
            displayedTrackerId = displayedTrackerId,
            displayedTrackerName = displayedTrackerName,
            resolvedGroupId = if (input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
                input.groupSelection.groupId.orEmpty()
            } else {
                ""
            },
            groupTrackerIds = groupTrackerIds,
            visibleRosterTrackerIds = rosterTrackerIds,
            locallyRecordedTrackerIds = localTrackerIds,
            remoteSubscriptionIds = remoteSubscriptionIds,
            acceptedRemoteTrackerIds = acceptedRemoteTrackerIds,
            localOverlayTrackerIds = localOverlayTrackerIds,
            trailReloadPlan = trailReloadPlan,
        )
    }
}
