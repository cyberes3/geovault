package com.geovault.tracker.presentation

data class LiveActiveFitInput(
    val mode: TrackerMapDisplayMode,
    val followLockArmed: Boolean,
    val liveActiveFitEnabled: Boolean,
    val hasTrailPoints: Boolean,
    val isSelectedDefaultTracker: Boolean,
    /**
     * True when there's at least one other tracker/position on screen besides the locked one --
     * the user's own GPS puck, or a locally-recorded tracker different from the one displayed.
     * Fitting bounds around a single point is no different from centering on it, so the toggle
     * has nothing to offer when this is false.
     */
    val hasMultipleTrackersOnMap: Boolean,
)

data class LiveActiveFitVisibility(
    val showButton: Boolean,
    val buttonEnabled: Boolean,
)

/**
 * Policy for the secondary live-active-fit FAB in single-tracker mode, and for how live active
 * fit composes with a selection lock.
 *
 * Group / all-queue bounds are owned by [TrackerMapGroupBoundsResolver].
 *
 * COMPOSITION RULE (the one non-obvious rule the whole map-lock system hinges on): in
 * [TrackerMapDisplayMode.SINGLE_SESSION], live active fit is a *modifier* of an existing
 * selection lock -- "keep re-fitting bounds around the locked tracker" -- not an alternative to
 * it, which is why [resolveLockArmed]/[resolveVisibility] only ever surface the secondary FAB
 * while already locked. [composesWithSelectionLock] is the single source of truth for that
 * relationship; both the state mutation
 * ([com.geovault.tracker.map.MapContextSubsystem.setLiveActiveFit]) and the camera precedence
 * ([TrackerMapCameraDirectivePolicy]) must agree with it, or a lock and a live-fit toggle can
 * silently fight over -- or drop -- each other's state. In every other mode the primary lock FAB
 * owns live active fit directly and there is no separate selection lock to preserve.
 */
object TrackerMapLiveActiveFitPolicy {

    /**
     * Whether enabling live active fit in [mode] should preserve an already-engaged selection
     * lock (SINGLE_SESSION) rather than replace it (every other mode, where live active fit is a
     * standalone toggle with no selection-lock concept behind it).
     */
    fun composesWithSelectionLock(mode: TrackerMapDisplayMode): Boolean =
        mode == TrackerMapDisplayMode.SINGLE_SESSION

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
            input.hasTrailPoints &&
            input.hasMultipleTrackersOnMap
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
