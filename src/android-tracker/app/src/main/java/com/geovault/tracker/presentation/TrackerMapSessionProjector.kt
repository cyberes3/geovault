package com.geovault.tracker.presentation

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
        val groupTrackerIds = normalizedIds(input.groupSelection.trackerIds)
        val rosterTrackerIds = normalizedIds(input.rosterTrackerIds)
        val localTrackerIds = if (runtimeRunning && selectedTrackerId.isNotEmpty()) {
            setOf(selectedTrackerId)
        } else {
            emptySet()
        }
        val remoteSubscriptionIds = when (input.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> {
                displayedTrackerId.takeIf { it.isNotEmpty() && it !in localTrackerIds }?.let(::setOf).orEmpty()
            }
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> groupTrackerIds - localTrackerIds
            TrackerMapDisplayMode.ALL_QUEUE -> rosterTrackerIds - localTrackerIds
        }
        val acceptedRemoteTrackerIds = TrackerMapRemoteAcceptancePolicy
            .mergedAcceptedRemoteTrackerIds(
                streamTargetIds = remoteSubscriptionIds,
                activeStreamedTrackerIds = input.activeStreamedTrackerIds,
            ) - localTrackerIds
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

    private fun normalizedIds(ids: Collection<String>): Set<String> {
        return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
    }
}
