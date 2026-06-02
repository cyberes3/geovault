package com.geovault.tracker.history

sealed class TrackerHistoryTrunkPrepareResult {
    data class Commit(
        val batch: TrackerHistorySourceBatch,
        val clipped: Boolean,
    ) : TrackerHistoryTrunkPrepareResult()

    data class Reject(val reason: String) : TrackerHistoryTrunkPrepareResult()
}

object TrackerHistoryActiveSessionPolicy {
    fun prepareTrunkForCommit(
        batch: TrackerHistorySourceBatch,
        activeSessionStartMs: Long?,
    ): TrackerHistoryTrunkPrepareResult {
        val sessionStart = activeSessionStartMs?.takeIf { it > 0L } ?: return TrackerHistoryTrunkPrepareResult.Commit(batch, clipped = false)
        if (batch.sourceKind != TrackerHistorySourceKind.FILTERED_SERVER_TRUNK) {
            return TrackerHistoryTrunkPrepareResult.Commit(batch, clipped = false)
        }
        if (!batch.window.isCurrentSession && !batch.window.isSession) {
            return TrackerHistoryTrunkPrepareResult.Commit(batch, clipped = false)
        }
        if (batch.points.isEmpty()) {
            return TrackerHistoryTrunkPrepareResult.Reject("empty_trunk")
        }
        var clippedPoints = batch.points.filter { it.timestampMs >= sessionStart }
        val sessionTaggedPoints = clippedPoints.filter { it.startTimestampMs != null }
        if (sessionTaggedPoints.isNotEmpty()) {
            clippedPoints = clippedPoints.filter { it.startTimestampMs == sessionStart }
        }
        if (clippedPoints.isEmpty()) {
            val maxPointTimestamp = batch.points.maxOf { it.timestampMs }
            val reason = if (maxPointTimestamp < sessionStart) {
                "stale_trunk_before_active_session"
            } else {
                "stale_trunk_mismatched_session"
            }
            return TrackerHistoryTrunkPrepareResult.Reject(reason)
        }
        if (clippedPoints.size == batch.points.size) {
            return TrackerHistoryTrunkPrepareResult.Commit(batch, clipped = false)
        }
        return TrackerHistoryTrunkPrepareResult.Commit(
            batch = batch.copy(points = clippedPoints),
            clipped = true,
        )
    }
}
