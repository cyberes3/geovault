package com.geovault.tracker.presentation

data class TrackerMapLockFabInput(
    val mode: TrackerMapDisplayMode,
    val displayedTrackerId: String,
    val selectionLockTrackerId: String,
    val liveActiveFitEnabled: Boolean,
    val followLockEnabled: Boolean,
)

sealed class TrackerMapLockFabBehavior {
    data class SelectionLock(
        val displayedTrackerId: String,
        val isLocked: Boolean,
    ) : TrackerMapLockFabBehavior()

    data class LiveActiveFit(val isEnabled: Boolean) : TrackerMapLockFabBehavior()
    data class FollowLock(val isEnabled: Boolean) : TrackerMapLockFabBehavior()
}

object TrackerMapLockFabPolicy {
    fun resolve(input: TrackerMapLockFabInput): TrackerMapLockFabBehavior {
        val displayedTrackerId = input.displayedTrackerId.trim()
        return when {
            input.mode == TrackerMapDisplayMode.SINGLE_SESSION && displayedTrackerId.isNotEmpty() ->
                TrackerMapLockFabBehavior.SelectionLock(
                    displayedTrackerId = displayedTrackerId,
                    isLocked = input.selectionLockTrackerId.trim() == displayedTrackerId,
                )
            input.mode == TrackerMapDisplayMode.ALL_QUEUE ||
                input.mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER ->
                TrackerMapLockFabBehavior.LiveActiveFit(input.liveActiveFitEnabled)
            else -> TrackerMapLockFabBehavior.FollowLock(input.followLockEnabled)
        }
    }
}
