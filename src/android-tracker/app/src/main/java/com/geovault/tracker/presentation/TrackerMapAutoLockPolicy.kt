package com.geovault.tracker.presentation

sealed class TrackerMapAutoLockOnRecordingResult {
    data object None : TrackerMapAutoLockOnRecordingResult()
    data class SelectionLock(val trackerId: String) : TrackerMapAutoLockOnRecordingResult()
    data object LiveActiveFit : TrackerMapAutoLockOnRecordingResult()
}

object TrackerMapAutoLockPolicy {

    fun resolveAutoLockOnRecordingStart(
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String,
        selectedTrackerId: String,
    ): TrackerMapAutoLockOnRecordingResult {
        val lockId = displayedTrackerId.trim().ifBlank { selectedTrackerId.trim() }
        return when (mode) {
            TrackerMapDisplayMode.SINGLE_SESSION ->
                if (lockId.isEmpty()) {
                    TrackerMapAutoLockOnRecordingResult.None
                } else {
                    TrackerMapAutoLockOnRecordingResult.SelectionLock(lockId)
                }
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER ->
                TrackerMapAutoLockOnRecordingResult.LiveActiveFit
        }
    }

    fun resolveAutoSelectionLockForSingleStream(
        mode: TrackerMapDisplayMode,
        previousTargets: Set<String>,
        nextTargets: Set<String>,
        displayedTrackerId: String,
    ): String? {
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return null
        if (previousTargets == nextTargets) return null
        if (nextTargets.size != 1) return null
        val only = nextTargets.first().trim()
        if (only.isEmpty()) return null
        if (only != displayedTrackerId.trim()) return null
        return only
    }
}
