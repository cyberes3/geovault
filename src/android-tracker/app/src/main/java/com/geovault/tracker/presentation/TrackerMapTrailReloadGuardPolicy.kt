package com.geovault.tracker.presentation

data class TrailReloadGuardInput(
    val mode: TrackerMapDisplayMode,
    val trailSize: Int,
    val runtimeRunning: Boolean,
    val displayedTrackerId: String,
    val trailReloadPlan: TrackerMapTrailReloadPlan,
)

object TrackerMapTrailReloadGuardPolicy {
    fun shouldProceed(input: TrailReloadGuardInput): Boolean {
        if (input.trailSize == 0) return true
        val activeId = input.displayedTrackerId.trim()
        if (activeId.isEmpty()) return true
        if (input.trailReloadPlan.source == TrackerMapTrailSource.MULTI_SERVER) return true
        // ACTIVE-RECORDING PROTECTION: unconditional — no caller-supplied "force" reload
        // reason may bypass this. A SINGLE_QUEUE source while `runtimeRunning` means the
        // displayed trail already IS the tracker currently being recorded, kept live
        // point-by-point by `handleTrackPointEvent`; re-deriving it from the same queue via
        // a forced reload (MapContextChange, RosterChanged, etc.) used to skip straight
        // past this check and race that live stream for no benefit. Only a genuine source
        // change (recording stopped, or the display switching to a different tracker) may
        // proceed while recording.
        return !(input.runtimeRunning && input.trailReloadPlan.source == TrackerMapTrailSource.SINGLE_QUEUE)
    }
}
