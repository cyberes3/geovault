package com.geovault.tracker.presentation

data class TrailReloadGuardInput(
    val force: Boolean,
    val mode: TrackerMapDisplayMode,
    val trailSize: Int,
    val runtimeRunning: Boolean,
    val activeStreamedTrackerIds: Set<String>,
    val displayedTrackerId: String,
    val trailReloadPlan: TrackerMapTrailReloadPlan,
)

object TrackerMapTrailReloadGuardPolicy {
    fun shouldProceed(input: TrailReloadGuardInput): Boolean {
        if (input.force) return true
        if (input.trailSize == 0) return true
        val activeId = input.displayedTrackerId.trim()
        if (activeId.isEmpty()) return true
        if (input.trailReloadPlan.source == TrackerMapTrailSource.MULTI_SERVER) return true
        if (input.runtimeRunning && input.trailReloadPlan.source == TrackerMapTrailSource.SINGLE_QUEUE) return false
        if (activeId in input.activeStreamedTrackerIds && input.trailReloadPlan.source == TrackerMapTrailSource.SINGLE_SERVER) {
            return false
        }
        return true
    }
}
