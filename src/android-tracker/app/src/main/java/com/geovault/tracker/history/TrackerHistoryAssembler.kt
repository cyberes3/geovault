package com.geovault.tracker.history

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.presentation.TrackerMapRecentDataWindowFilterPolicy
import com.geovault.tracker.presentation.TrackerSessionWindowContext

data class TrackerHistoryComposeInput(
    val key: TrackerHistoryKey,
    val trunk: TrackerHistorySourceBatch?,
    val overlayBatches: List<TrackerHistorySourceBatch>,
    val activeSessionStartMs: Long? = null,
    val clearBoundary: TrackerHistoryClearBoundary? = null,
    val nowMs: Long = System.currentTimeMillis(),
    val previousSnapshot: TrackerHistorySnapshot? = null,
)

object TrackerHistoryAssembler {
    private const val TAG = "TrackerHistoryAssembler"

    fun compose(input: TrackerHistoryComposeInput): TrackerHistoryTransactionResult {
        val trackerId = input.key.normalizedTrackerId
        if (trackerId.isEmpty()) {
            return fallback(input, reason = "blank_tracker")
        }
        val trunkPoints = filterPointsForRecentDataWindow(
            points = input.trunk
                ?.points
                .orEmpty()
                .filter { it.trackerId.trim() == trackerId },
            input = input,
        ).sortedBy { it.timestampMs }
        val trunkKeys = trunkPoints.map { it.key }.toSet()
        val eligibleOverlay = filterPointsForRecentDataWindow(
            points = input.overlayBatches
                .flatMap { it.points }
                .filter { it.trackerId.trim() == trackerId }
                .filter { it.key !in trunkKeys }
                .filter { point -> point.isAfterClearBoundary(input.clearBoundary) },
            input = input,
        )
            .dedupeOverlay()
            .sortedBy { it.timestampMs }
        val points = (trunkPoints + eligibleOverlay)
            .distinctBy { it.key }
            .sortedBy { it.timestampMs }
        if (points.isEmpty() && input.previousSnapshot != null && input.clearBoundary == null) {
            val pendingOverlayPoints = input.overlayBatches.sumOf { it.points.size }
            if (pendingOverlayPoints > 0) {
                GeoVaultCaptureLog.w(
                    TAG,
                    "map_update history_compose_overlay_filtered_empty tracker=$trackerId " +
                        "window=${input.key.window.normalizedKey} overlay_pts=$pendingOverlayPoints " +
                        "session=${input.activeSessionStartMs ?: -1}",
                )
            }
            GeoVaultCaptureLog.d(
                TAG,
                "map_update history_compose_skip tracker=$trackerId window=${input.key.window.normalizedKey} " +
                    "trunk_batches=${if (input.trunk != null) 1 else 0} overlay_batches=${input.overlayBatches.size} " +
                    "action=defer_empty",
            )
            return TrackerHistoryTransactionResult(
                snapshot = input.previousSnapshot.copy(isLoading = false),
                committed = false,
                reason = "empty_snapshot_deferred",
            )
        }
        val snapshot = TrackerHistorySnapshot(
            key = input.key,
            trunk = trunkPoints,
            overlay = eligibleOverlay,
            points = points,
            committedAtMs = input.nowMs,
            generation = maxOf(input.trunk?.generation ?: 0L, input.nowMs),
            degradedLocalOnly = input.trunk?.degradedLocalOnly == true,
            complete = input.trunk?.complete ?: false,
        )
        GeoVaultCaptureLog.i(
            TAG,
            "map_update history_compose tracker=$trackerId window=${input.key.window.normalizedKey} " +
                "trunk=${trunkPoints.size} overlay=${eligibleOverlay.size} result=${points.size} " +
                "complete=${snapshot.complete} degraded=${snapshot.degradedLocalOnly}"
        )
        return TrackerHistoryTransactionResult(
            snapshot = snapshot,
            committed = true,
            reason = "composed",
        )
    }

    private fun fallback(input: TrackerHistoryComposeInput, reason: String): TrackerHistoryTransactionResult {
        val previous = input.previousSnapshot ?: TrackerHistorySnapshot(
            key = input.key,
            trunk = emptyList(),
            overlay = emptyList(),
            points = emptyList(),
            committedAtMs = input.nowMs,
            generation = input.nowMs,
        )
        return TrackerHistoryTransactionResult(
            snapshot = previous,
            committed = false,
            reason = reason,
        )
    }

    /**
     * Uses the same rules as map render ([TrackerMapRecentDataWindowFilterPolicy]) so history
     * snapshots match what the user selected (current session, session, rolling windows).
     */
    private fun filterPointsForRecentDataWindow(
        points: List<TrackerHistoryPoint>,
        input: TrackerHistoryComposeInput,
    ): List<TrackerHistoryPoint> {
        if (points.isEmpty()) return points
        val window = input.key.window
        if (window.isAll) return points
        val context = TrackerSessionWindowContext(
            windowKey = window.normalizedKey,
            nowMs = input.nowMs,
            currentSessionStartMs = input.activeSessionStartMs,
        )
        val filteredKeys = TrackerMapRecentDataWindowFilterPolicy
            .apply(points.map { it.toQueuedLocation() }, context)
            .map { loc ->
                TrackerHistoryPointKey.from(
                    trackerId = loc.trackerId,
                    timestampMs = loc.time,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    startTimestampMs = loc.startTimestampMs,
                )
            }
            .toSet()
        return points.filter { it.key in filteredKeys }
    }

    private fun TrackerHistoryPoint.isAfterClearBoundary(boundary: TrackerHistoryClearBoundary?): Boolean {
        if (boundary == null) return true
        if (trackerId.trim() != boundary.trackerId.trim()) return true
        val afterClearTime = timestampMs >= boundary.clearedAtMs
        val matchesActiveSession = boundary.activeSessionStartMs != null &&
            startTimestampMs == boundary.activeSessionStartMs
        return afterClearTime || matchesActiveSession
    }

    private fun List<TrackerHistoryPoint>.dedupeOverlay(): List<TrackerHistoryPoint> {
        val priority = mapOf(
            TrackerHistoryProvenance.LOCAL_QUEUE to 4,
            TrackerHistoryProvenance.LOCAL_LIVE to 3,
            TrackerHistoryProvenance.RUNTIME_HEAD to 2,
            TrackerHistoryProvenance.REMOTE_STREAM to 1,
            TrackerHistoryProvenance.SERVER_GEOMETRY to 0,
        )
        return groupBy { it.key }
            .values
            .map { points ->
                points.maxBy { priority[it.provenance] ?: 0 }
            }
    }
}
