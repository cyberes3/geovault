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
        val displayedTrackerId = input.displayedTrackerId.trim().ifBlank { selectedTrackerId }
        val displayedTrackerName = input.displayedTrackerName.trim().ifBlank {
            input.runtime.selectedTrackerName.trim()
        }
        val groupTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(input.groupSelection.trackerIds)
        val rosterTrackerIds = StreamingTargetPolicy.normalizeTrackerIds(input.rosterTrackerIds)
        val localTrackerIds = if (runtimeRunning && selectedTrackerId.isNotEmpty()) {
            setOf(selectedTrackerId)
        } else {
            emptySet()
        }
        val requestedRemoteTrackerIds = when (input.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                displayedTrackerId.takeIf { it.isNotEmpty() }?.let(::setOf).orEmpty()
            }
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupTrackerIds
            TrackerMapDisplayMode.ALL_QUEUE -> rosterTrackerIds
        }
        val remoteSubscriptionIds = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = requestedRemoteTrackerIds,
                selectedTrackerId = selectedTrackerId,
                locallyRecordedTrackerIds = localTrackerIds,
            )
        )
        val acceptedRemoteTrackerIds = TrackerMapRemoteAcceptancePolicy
            .mergedAcceptedRemoteTrackerIds(
                streamTargetIds = remoteSubscriptionIds,
                activeStreamedTrackerIds = input.activeStreamedTrackerIds,
            )
            .let { acceptedIds ->
                StreamingTargetPolicy.remoteSubscriptionTargets(
                    StreamingTargetPolicyInput(
                        requestedTrackerIds = acceptedIds,
                        selectedTrackerId = selectedTrackerId,
                        locallyRecordedTrackerIds = localTrackerIds,
                    )
                )
            }
        val localOverlayTrackerIds = when {
            localTrackerIds.isEmpty() -> emptySet()
            input.mode == TrackerMapDisplayMode.ALL_QUEUE -> localTrackerIds
            input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && selectedTrackerId in groupTrackerIds -> localTrackerIds
            input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
                (displayedTrackerId.isEmpty() || displayedTrackerId == selectedTrackerId) -> localTrackerIds
            else -> emptySet()
        }
        val trailReloadPlan = TrackerMapTrailReloadCoordinator.resolvePlan(
            TrackerMapTrailReloadInput(
                mode = input.mode,
                runtimeRunning = runtimeRunning,
                selectedTrackerId = selectedTrackerId,
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
