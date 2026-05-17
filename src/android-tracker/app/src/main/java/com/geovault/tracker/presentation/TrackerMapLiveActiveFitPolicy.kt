package com.geovault.tracker.presentation

data class LiveActiveFitInput(
    val mode: TrackerMapDisplayMode,
    val runtimeRunning: Boolean,
    val followLockArmed: Boolean,
    val liveActiveFitEnabled: Boolean,
    val hasTrailPoints: Boolean,
    val isSelectedDefaultTracker: Boolean,
)

data class LiveActiveFitVisibility(
    val showButton: Boolean,
    val buttonEnabled: Boolean,
)

/**
 * UI policy for the secondary live-active-fit FAB in single-tracker mode.
 *
 * Group / all-queue bounds are owned by [TrackerMapGroupBoundsResolver].
 */
object TrackerMapLiveActiveFitPolicy {

    /**
     * Resolves the "lock armed" gate for the secondary live-active-fit FAB.
     *
     * The secondary FAB now exists only in SINGLE_SESSION (ALL_QUEUE and GROUP_PLACEHOLDER both
     * have their lock FAB own live-active-fit directly), so the gate is simply whether the
     * displayed single tracker is selection-locked.
     */
    fun resolveLockArmed(singleTrackerLocked: Boolean): Boolean = singleTrackerLocked

    fun resolveVisibility(input: LiveActiveFitInput): LiveActiveFitVisibility {
        val singleTrackerVisible = input.mode == TrackerMapDisplayMode.SINGLE_SESSION &&
            input.hasTrailPoints
        if (!singleTrackerVisible || input.isSelectedDefaultTracker) {
            return LiveActiveFitVisibility(showButton = false, buttonEnabled = false)
        }
        val toggleEnabled = input.followLockArmed
        return LiveActiveFitVisibility(
            showButton = toggleEnabled,
            buttonEnabled = toggleEnabled,
        )
    }
}
