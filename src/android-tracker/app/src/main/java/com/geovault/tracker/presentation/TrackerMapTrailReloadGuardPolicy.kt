package com.geovault.tracker.presentation

data class TrailReloadGuardInput(
    val force: Boolean,
    val mode: TrackerMapDisplayMode,
    val trailSize: Int,
    val runtimeRunning: Boolean,
    val activeStreamedTrackerIds: Set<String>,
    val displayedTrackerId: String,
)

object TrackerMapTrailReloadGuardPolicy {
    fun shouldProceed(input: TrailReloadGuardInput): Boolean {
        if (input.force) return true
        if (input.trailSize == 0) return true
        val activeId = input.displayedTrackerId.trim()
        if (activeId.isEmpty()) return true
        if (input.runtimeRunning) return false
        if (activeId in input.activeStreamedTrackerIds) return false
        return true
    }
}
