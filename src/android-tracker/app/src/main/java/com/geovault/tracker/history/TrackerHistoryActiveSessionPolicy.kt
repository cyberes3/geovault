package com.geovault.tracker.history

object TrackerHistoryActiveSessionPolicy {
    fun staleTrunkReason(batch: TrackerHistorySourceBatch, activeSessionStartMs: Long?): String? {
        val sessionStart = activeSessionStartMs?.takeIf { it > 0L } ?: return null
        if (batch.sourceKind != TrackerHistorySourceKind.FILTERED_SERVER_TRUNK) return null
        if (!batch.window.isCurrentSession && !batch.window.isSession) return null
        if (batch.points.isEmpty()) return null
        val maxPointTimestamp = batch.points.maxOf { it.timestampMs }
        if (maxPointTimestamp < sessionStart) return "stale_trunk_before_active_session"
        val sessionTaggedPoints = batch.points.filter { it.startTimestampMs != null }
        if (sessionTaggedPoints.isNotEmpty() && sessionTaggedPoints.none { it.startTimestampMs == sessionStart }) {
            return "stale_trunk_mismatched_session"
        }
        return null
    }
}
