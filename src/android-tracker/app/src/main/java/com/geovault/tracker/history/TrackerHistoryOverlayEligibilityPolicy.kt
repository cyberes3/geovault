package com.geovault.tracker.history

/** Overlay eligibility when composing trunk + live/queue overlay (time/session gates). */
object TrackerHistoryOverlayEligibilityPolicy {
    fun filterOverlayCandidates(
        trunkPoints: List<TrackerHistoryPoint>,
        overlayCandidates: List<TrackerHistoryPoint>,
        activeSessionStartMs: Long?,
    ): List<TrackerHistoryPoint> {
        if (overlayCandidates.isEmpty()) return overlayCandidates
        val latestTrunkTime = trunkPoints.maxOfOrNull { it.timestampMs }
        val trunkKeys = trunkPoints.map { it.key }.toSet()
        return overlayCandidates.filter { point ->
            val sessionOk = activeSessionStartMs == null ||
                point.startTimestampMs == null ||
                point.startTimestampMs == activeSessionStartMs
            val timeOk = latestTrunkTime == null ||
                point.timestampMs > latestTrunkTime ||
                (isActiveSessionLocalPoint(point, activeSessionStartMs) && point.key !in trunkKeys)
            sessionOk && timeOk
        }
    }

    private fun isActiveSessionLocalPoint(
        point: TrackerHistoryPoint,
        activeSessionStartMs: Long?,
    ): Boolean {
        if (activeSessionStartMs == null) return false
        if (point.startTimestampMs != activeSessionStartMs) return false
        return point.provenance == TrackerHistoryProvenance.LOCAL_QUEUE ||
            point.provenance == TrackerHistoryProvenance.LOCAL_LIVE
    }
}
