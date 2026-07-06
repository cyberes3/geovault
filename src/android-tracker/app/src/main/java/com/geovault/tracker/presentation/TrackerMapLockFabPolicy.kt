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

/**
 * Resolves what the *primary* map lock FAB does and displays, per [TrackerMapDisplayMode]:
 *  - SINGLE_SESSION with a displayed tracker: toggles the selection lock on that tracker. The
 *    secondary FAB ([TrackerMapLiveActiveFitPolicy]) layers live active fit on top of this one
 *    once it's locked.
 *  - ALL_QUEUE / GROUP_PLACEHOLDER: toggles live active fit directly -- there's no per-tracker
 *    selection lock concept at this level, so this FAB owns that flag standalone.
 *  - Otherwise (SINGLE_SESSION with nothing displayed): toggles follow lock (GPS).
 *
 * Exactly one of these three is ever the "primary" behavior for a given mode/context; the FAB
 * itself is a single icon whose meaning and active-state boolean come entirely from whichever
 * variant this resolves to.
 */
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
